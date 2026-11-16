# ShopBackend — working agreement

This runs a **live phone system for real shops**. A mistake here does not fail a build; it drops a
customer mid-conversation, or takes a shop's line down until someone notices. Bias every decision
accordingly.

## The system in one paragraph

Kotlin/Ktor backend + Asterisk 22 (PJSIP) + chan_quectel driving EC25 LTE modems, on one edge box
(`ssh phone@192.168.0.158`, systemd `shopbackend`; moved from the old edge `192.168.0.192` on
2026-11-16, see docs/elitedesk-migration.md). Manager phones run the **ShopManager Android
app** (separate repo: `C:\Users\krest\AndroidStudioProjects\ShopManager`), reaching the backend over
a Headscale tailnet. chan_quectel source lives **only on the edge** at
`/home/phone/asterisk-chan-quectel` (git remote `origin` = our fork, `upstream` = RoEdAl).

---

## RULE 1 — recovery code gets a review pass, automatically

**Every call-dropping incident this system has had came from the code meant to protect it** — the
watchdogs, reapers and health checks, never the call path. See
[docs/recovery-paths-audit-2026-09-13.md](docs/recovery-paths-audit-2026-09-13.md).

**Trigger:** any edit touching automated recovery / self-healing / watchdog logic. In practice:

```
*Watchdog*  *Monitor*  *Provisioner*  *Alerter*  SipCommandDirector  AmiClient  SystemHealth*
SipService.kt  SipEngine.kt  TailnetManager*        (app repo)
at_response.c  monitor_thread.c  chan_quectel.c  at_command.c   (chan_quectel, on the edge)
```
…or any new code that can hang up a call, restart a modem/service, reload a module, rewrite live
config, bounce a socket, or command another device to restart.

**Then, before calling the work done:**
1. Walk the checklist in [docs/recovery-code-rules.md](docs/recovery-code-rules.md).
2. Run a review pass over the diff specifically for that checklist (a subagent is fine).
3. State to Kresten, in the response: **trigger, evidence strength (STRONG/WEAK), blast radius, the
   live-call guard, and what happens to a customer if it fires wrongly.**

**Opt-out:** if Kresten says to defer/skip testing or review, do so — and say plainly what is
unreviewed so the debt is visible.

### "review changes" — go deep, cost is not a consideration

When Kresten says **"review changes"** (or "review this"), his time to fix a production fault is far
more expensive than tokens. Do the thorough version, not a quick read:

- Fan out **parallel subagents** over the diff — correctness, the recovery-code checklist below,
  blast radius, concurrency/lifecycle, and error/inconclusive paths — rather than one shallow pass.
- **Verify every serious claim yourself** before reporting it. Subagents are fast and sometimes
  wrong; read the cited lines. Report findings as CONFIRMED (you read it) or SUSPECTED.
- Check the **runtime evidence too**, not just the code: what the logs on the edge say this code
  actually did. Static analysis finds what *could* fire; the logs show what *does*.
- Rank by **blast radius x likelihood** and say plainly what you would fix first — and what is fine.
  A short list of real problems beats a long list of observations.

The one rule that matters most: **weak evidence (a timeout, a missed poll, an unparsed response, a
failed query) must never authorise destroying working state.** "I could not determine" is a third
state, distinct from "no", and it must fail closed.

## RULE 2 — ask before anything that can drop live calls

Confirm with Kresten first, every time:
- `module reload chan_quectel.so` — restarts **every trunk whose config changed** (a no-op for a
  device when nothing changed, and it never loads new code — see below)
- restarting Asterisk, or the backend (its startup re-provision reloads chan_quectel)
- `quectel restart|reset|remove`, or writing `quectel_shops.conf`
- anything touching `/etc/asterisk`, astdb, or registrar contacts
- publishing an app release, or pushing config to phones

Before any of these, check first: `sudo asterisk -rx "core show channels"`. Act when idle.

Safe and encouraged: reading logs, `pjsip show *`, `quectel show *`, building/staging artifacts,
and querying a modem on its **free** tty (chan_quectel holds only the `:1.2` interface).

## RULE 3 — commit, don't push

Commit freely; **do not `git push`** unless Kresten explicitly asks. Deploying to the edge is
separate and fine (subject to Rule 2). Note `origin` on the edge chan_quectel repo is **our fork**,
`upstream` is RoEdAl's — never push to upstream.

---

## Debugging habits that would have saved hours

- **Ask what we deployed recently before blaming hardware.** The CLCC reaper shipped Aug 27; the
  "dropped calls" reports followed. That correlation sat unexamined while we chased handsets.
- **For a mid-call drop, check the server first:** `grep "CLCC reap" /var/log/asterisk/dropcatch`
  (a reap logged `channel:yes` is always a bug), then the hangup cause — `cause=31` at a constant
  ~15s is our own code; `cause=16` is a real hangup. Only then suspect the phone.
- **Packet counts are not audio.** RTP flowing proves delivery, not content.
- **Beware symptoms that point away from the cause.** A reaped call left the *caller's* handset
  showing a running timer on a dead line for minutes — it looked exactly like a phone fault.
- **`module reload chan_quectel.so` does not load new chan_quectel code** — only an Asterisk restart
  does. Verify what is *running*: `sudo grep chan_quectel /proc/$(pidof asterisk)/maps` must not say
  `(deleted)`. A deploy that checks the file on disk will report success on the old code.
- **A modem that goes mute with no USB drop** after an SMS: check for an orphaned SMS prompt first
  (failure mode 6 in docs/telephony-reliability.md).
- Never leave a detached `rasterisk`/`pjsip set logger on`; that filled the disk for 8 days once.
  Reset `core set debug 0` when done.

## Reference

- [docs/recovery-code-rules.md](docs/recovery-code-rules.md) — rules + checklist for destructive code
- [docs/telephony-reliability.md](docs/telephony-reliability.md) — known failure modes and fixes
- [docs/phone-server-setup.md](docs/phone-server-setup.md) — edge setup; §7b modem radio config
  (UAC required; **all three settings — UAC + carrier profile + VoLTE — on every modem**)
- [docs/manager-phone-setup.md](docs/manager-phone-setup.md) — per-handset Android settings
