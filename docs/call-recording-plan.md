# Call recording for evidence — plan

**Status: decisions made 2026-10-06, implementation in progress. Nothing deployed yet.**

Goal: let the admin record calls from a specific threatening customer, and let only the admin play
them back.

## Decisions (Kresten, 2026-10-06)

| Question | Decision |
|---|---|
| Which channels | **All three** — phone, in person, and SMS |
| Announce the recording | **No.** Covert. |
| Inbound / outbound calls | **Both** ("I want it all recorded") |
| Purpose | **Handover to police or social services so he gets help.** Not litigation. |

Consequences of those decisions, which this document now follows:

- **Covert recording is lawful here**, so the decision does not undermine the goal. Penal Code
  § 263 criminalises recording conversations you are *not* a party to; the shop is a party to its
  own calls. The residual issue is GDPR transparency (Art 13) — a compliance matter, not a
  criminal one — and "documenting threats against staff, handed to the authorities" is a strong
  legitimate interest. Raise it once with whoever handles data protection; it is not re-litigated
  below.
- **No notice playback.** §1's dialplan hook drops the `Playback(rec-notice)` line. The
  announcement WAV is not generated.
- **The purpose is handover, not court.** That lowers the bar on chain-of-custody formality but
  *raises* the value of §4's export bundle: a social worker or officer wants a clear, labelled
  package, not a folder of WAVs. Keep the hashing — it is nearly free and makes the package
  credible.
- **"In person" cannot be captured by the phone system.** Covered by §8 below, which is new.

---

## 0. Read this first — three things that change the shape of the job

### 0.1 The threats are in person, so call recording may capture the wrong thing

You said he is **showing up and physically threatening staff**. Call recording captures phone
audio only. If the threats happen across the counter, this feature will produce hours of unrelated
audio and none of the incident you actually need to prove.

What tends to carry weight for a police report (*anmeldelse*) over threats — Danish Penal Code
§ 266 — is a contemporaneous account: date, time, location, exactly what was said, who witnessed
it, signed by the staff member while it is fresh. That is a form, not a feature.

**You may already be holding the best evidence you have.** The system stores every SMS body, and
as of 2026-10-04 it keeps them for 730 days. If any of his threats arrived by text, that is
already written down, timestamped, and attributable to his number.

Suggested order:

1. **Now, no code:** have staff write up each incident, and export his existing SMS + call history
   from the system. Takes minutes and is useful today.
2. **Now, no code:** report it to the police. Recording calls does not need to block this, and the
   police may tell you exactly what they want — which beats guessing.
3. **Then** add call recording, for the phone threats specifically.
4. **Consider** whether CCTV at the counter is the real answer for in-person threats. Different
   project, different rules (Datatilsynet has specific guidance on *tv-overvågning*), but it
   matches the actual behaviour you described.

I am not a lawyer and this is not legal advice — § 266 and the GDPR points below are the
constraints that shape the design, and they should be confirmed with one.

### 0.2 Recording is legal here — the announcement question is settled (no notice)

As I understand it, recording a conversation **you are a party to** is not the offence in Denmark —
Penal Code § 263 targets recording conversations you are *not* part of. The shop is a party to its
own calls, so the criminal exposure is not the blocker.

GDPR is. A voice recording is personal data, and a recording of someone whose mental health is in
question may touch special-category data. So:

- **Lawful basis:** legitimate interest (Art 6(1)(f)) — documenting threats to protect staff — is
  the strongest fit, and is a better basis here than consent, since consent you can withdraw is
  useless for evidence.
- **Transparency (Art 13):** the caller must be told. In practice that means a short announcement
  at the start of the call.
- **Data minimisation:** record the minimum that achieves the purpose. That is the single most
  important design consequence — see §1.
- A **DPIA** is worth doing, and it is cheap when the scope is one number.

**Decided: no announcement.** Recorded here for the record — this was raised, considered, and
settled by Kresten on 2026-10-06 on the grounds that the purpose is getting the person help via
police/social services rather than suing him. Lawful under § 263 as a party to the call; the GDPR
transparency point is the residual item for his data-protection advisor.

### 0.3 "Only the admin" is not currently enforceable — fix that first

Three findings from reading the auth code. These matter because recordings of a vulnerable person
are the most sensitive data this system would ever hold.

| # | Finding | Evidence |
|---|---|---|
| 1 | **Session cookies are not signed or encrypted.** `install(Sessions) { cookie<AdminSession>("ADMIN_SESSION") }` has no `transform {}`. In Ktor a bare `cookie<T>(name)` serialises the session as plaintext and does not authenticate it, so an `ADMIN_SESSION` cookie can be hand-written by anyone who can reach the port. | CODE-READ in [ShopBackend.kt](../src/main/kotlin/ShopBackend.kt). **Not empirically confirmed** — I started to test it with a forged cookie and the attempt was correctly blocked as credential exploration. I did not route around it. Say the word and I will verify it properly. |
| 2 | **Hardcoded fallback `admin` / `1234`.** If `ADMIN_PASSWORD` is unset, login falls back to a BCrypt hash of `1234` compiled into the binary. On the edge `ADMIN_PASSWORD` **is** set, so the fallback is inert there — but it survives any deploy where the env var goes missing. | [WebAdmin.kt](../src/main/kotlin/WebAdmin.kt) `post("/login")` |
| 3 | **One shared admin account, so no record of who listened.** `AdminSession` carries only a username, and there is a single credential. For evidence you want to be able to say who accessed what and when. | `data class AdminSession(val username: String)` |

Scope note: the backend listens on the tailnet, not the public internet, so the exposure is
"anyone on the tailnet" — your laptop, the control-plane box, and the manager handsets. Not nothing.

Also worth knowing: `web_admin_access` on a manager only sets a flag in the app's `/me` response;
the manager still has to pass `/login`. So no manager can reach admin pages **unless they have been
given the admin password**. If any of them has it, they can already read everything, and recordings
would be no exception. That is a policy question, not a code one.

**Recommendation: do §3 below before storing a single recording.**

---

## 1. Design: record one number, not everyone

The important decision. Record only the specific caller, keyed by phone number.

Why:

- **Proportionality.** Recording every customer to catch one is hard to defend under data
  minimisation and would be the first thing challenged.
- **Evidence quality.** A short, targeted set of recordings of one person beats thousands of files.
- **It fits what already exists.** The inbound dialplan already asks the backend what to do with
  each caller, by number, and already supports a per-number decision — that is exactly how the
  blacklist works. No new mechanism is needed.
- **Storage is a rounding error.** 8 kHz mono is ~0.5–1 MB/minute; 174 GB is free.

### Where it hooks

The generated inbound context already makes one decision per call:

```
exten => s,1,NoOp(Inbound call for shop 1 from ${CALLERID(num)})
 same => n,Set(SHOP_ID=1)
 same => n,Set(VERDICT=${CURL(.../call/inbound, ... &from=${URIENCODE(${CALLERID(num)})} ...)})
 same => n,GotoIf($["${VERDICT}" = "reject"]?rejected,1)
 ...
 same => n,Dial(${TARGETS},30)
```

The backend already receives the caller's number there and already returns a routing verdict. So:

1. The `/call/inbound` response gains a recording decision (a second variable, or an extra verdict
   token — a **separate variable is safer**, so existing verdict handling is untouched).
2. If recording is on for that number, the dialplan plays the notice, then starts `MixMonitor`
   **before** `Dial`, so both legs are captured once bridged.

```
 same => n,ExecIf($["${REC}" = "1"]?MixMonitor(${REC_FILE},b))
 same => n,Dial(${TARGETS},30)
```

No notice playback: covert by decision. One line, not two, which also means the recorded call is
not delayed and sounds identical to any other.

`app_mixmonitor.so` is loaded and core-supported.

### Permissions, which happen to already work

`/var/spool/asterisk/monitor` is `drwxr-x--- asterisk:asterisk`, Asterisk runs as `asterisk`, and
the backend user `phone` is in group `asterisk` (137). So Asterisk can write and the backend can
read, with no permission changes. Recordings should go in a **subdirectory** of that, not the shared
spool root.

**Outbound calls are in scope too** ("I want it all recorded"), and they are a *different* dialplan
path — the manager dials out through the shop's trunk, so there is no `from-gsm-shopN` context and
no inbound verdict call. That path needs its own `MixMonitor` hook keyed on the dialled number
rather than the caller id. Treat it as a separate, smaller piece of stage 3.

---

## 2. Data model

```sql
-- Which numbers are recorded, and why. Mirrors phone_blacklist deliberately.
CREATE TABLE call_recording_rule (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    phone_e164  TEXT NOT NULL,
    active      INTEGER NOT NULL DEFAULT 1,
    reason      TEXT NOT NULL,     -- required: the documented purpose, for the DPIA
    created_at  INTEGER NOT NULL,
    created_by  TEXT NOT NULL,
    expires_at  INTEGER            -- see §5: recording must not run forever
);

CREATE TABLE call_recording (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    call_id       INTEGER,         -- -> voice_call.id
    phone_e164    TEXT NOT NULL,
    shop_id       INTEGER NOT NULL,
    path          TEXT NOT NULL,
    started_at    INTEGER NOT NULL,
    duration_ms   INTEGER,
    sha256        TEXT,            -- integrity, see §4
    preserved     INTEGER NOT NULL DEFAULT 0,  -- 1 = evidence, exempt from auto-delete
    deleted_at    INTEGER
);

-- Chain of custody: every enable, playback, download and delete.
CREATE TABLE call_recording_access (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    recording_id  INTEGER,
    action        TEXT NOT NULL,   -- enable|disable|play|download|delete|preserve
    actor         TEXT NOT NULL,
    at            INTEGER NOT NULL,
    note          TEXT
);
```

`reason` is deliberately `NOT NULL`: if you cannot state the purpose, you should not be recording.

---

## 3. Admin-only, enforced

In order. **1 and 2 are prerequisites, not nice-to-haves.**

1. **Sign the session cookies.** Add a `transform {}` with
   `SessionTransportTransformerMessageAuthentication` keyed from an env secret, so a cookie cannot
   be forged. Small change; it invalidates existing sessions (everyone logs in again), which is
   why it wants an idle moment.
2. **Remove the `admin`/`1234` fallback.** Refuse to start, loudly, if `ADMIN_PASSWORD` is unset,
   rather than silently accepting a known password.
3. **Put recordings behind a second gate**, not just `AdminSession`: re-enter the admin password to
   open the recordings page, with a short-lived grant. A stolen laptop session should not equal
   access to this audio.
4. **Serve audio only through an authenticated route** that streams from disk — never a static
   path, never a guessable URL. Filenames should be opaque (random id), not
   `+45XXXXXXXX-2026-10-06.wav`.
5. **Log every access** to `call_recording_access`, including failed attempts.
6. **Keep it out of the owner portal and the mobile API entirely.** The owner portal has its own
   session and the app has JWT auth; neither should gain any recording route. Worth an explicit
   test that asserts a 404/403 for both.

Honest limitation: with one shared admin credential, "who listened" is only as good as "who knows
the password". Per-admin accounts would fix that properly; that is a bigger piece of work and
probably not worth blocking this on — but the access log is still worth having, and it is what a
court would ask for.

---

## 4. Evidence integrity

If this audio is ever going to matter, it needs to be hard to argue with:

- **Hash on completion.** SHA-256 the file as soon as MixMonitor closes it, store it in
  `call_recording.sha256`. A later re-hash proves the file has not changed.
- **Write-once in practice.** The backend should never rewrite a recording; it only reads.
- **Keep the metadata even after the audio is deleted** (`deleted_at` set, row retained) so the
  record of what existed survives.
- **Export as a bundle:** the WAV, plus a text manifest (number, shop, start, duration, SHA-256,
  and the access log for that file). That is what you hand over, not a bare audio file.

---

## 5. Retention — deliberately separate from everything else

Recordings must **not** inherit the 730-day customer window. Two opposite risks: deleting the thing
you needed, and hoarding sensitive audio you can no longer justify.

- **Default: 90 days**, own setting, independent of the other two windows.
- **`preserved = 1` is exempt.** When a recording is actual evidence, the admin marks it preserved
  and it is never auto-deleted. Preserving is itself an audited action.
- **Recording rules expire.** `expires_at` defaults to ~90 days: this is a response to a specific
  situation, not a permanent change to how the shop treats that person. The admin can renew.
- The auto-delete follows the rules in [recovery-code-rules.md](recovery-code-rules.md), as the
  customer-retention sweep now does: a positive assertion of age, never an inference from missing
  data, and it announces what it deleted.

---

## 6. Implementation stages

Each stage is independently useful and independently revertible.

| Stage | What | Risk | Needs |
|---|---|---|---|
| 0 | Incident write-ups + export his existing SMS/call history | none | no code |
| 1 | Session cookie signing; drop the `1234` fallback | low — logs everyone out | backend restart (Rule 2) |
| 2 | Schema + admin UI to turn recording on for one number, with reason and expiry. **No recording yet.** | low | backend restart |
| 3 | Generate the notice WAV; add the dialplan hook behind the per-number flag | **touches the live call path** | dialplan regen + an idle moment; see below |
| 4 | Playback UI, second password gate, access log, hashing | low | backend restart |
| 5 | Retention sweep + preserve flag + export bundle | low | backend restart |

**Stage 3 is the one to be careful with.** It edits the inbound dialplan, which every customer call
traverses. Mitigations: the recording lines are `ExecIf` on a variable that is empty for every
caller but the one, so the default path is byte-for-byte unchanged in behaviour; test on Testshop 1
first (it has a dialplan but no real traffic); and `MixMonitor` failing must not break the call —
verify that a bad path or full disk degrades to "call proceeds, no recording", never "call drops".
A test asserting the generated dialplan for a non-recorded shop is unchanged belongs here.

## 8. The in-person channel

The phone system cannot record what happens across the counter. Two ways to cover it, and they
are not alternatives - the first is worth doing regardless.

### 8.1 Incident log (backend, no app release)

Staff record each incident: when, which shop, who was present, what was said and done, free text,
optionally linked to the customer record. Timestamped at write time so it is contemporaneous.

This is what police and social services actually work from, and it is the cheapest thing here -
one table, one form, one export. It also gives the phone and SMS incidents context that audio
alone lacks ("he said X, then hung up and came to the shop").

### 8.2 Voice memo in the manager app (app repo - bigger)

You said you want to hand over **the voice**. For in-person incidents that means the manager's
handset recording it, since nothing else is in the room.

A "record incident" button in ShopManager that captures audio on the phone and uploads it to the
backend alongside the incident-log entry. Same lawfulness reasoning as the calls: the staff member
is a party to the conversation. Caveats worth knowing:

- It lives in the **app repo** (`C:\Users\krest\AndroidStudioProjects\ShopManager`), so it needs a
  release and an OTA - a much longer loop than a backend change.
- It needs `RECORD_AUDIO`, which the app already holds for calls.
- A shop recording captures whoever else is nearby. Staff are party to their own conversation, but
  unrelated customers in earshot are not - keep recordings short and incident-scoped rather than
  leaving it running.
- Upload must survive a bad tailnet link, so it needs a local queue and retry. That is the part
  that makes it "bigger", not the recording itself.

**Recommendation:** build 8.1 now with the rest; treat 8.2 as a follow-up once the backend side is
proven.

## 9. SMS - you already have this

Every SMS body is stored in `sms_message`, and since 2026-10-04 retention is 730 days. Nothing
needs building to *capture* it; what is missing is a way to get it out. Covered by the export
bundle in section 4: for a given number, produce his full SMS history plus call log plus any
recordings and incident-log entries, as one labelled package.

This is the fastest evidence you can have, and it exists today.

## 10. Stage 3 as built (2026-10-06)

The dialplan hook, committed but **not deployed**. Two lines added before `Dial`, at both dial
sites (the main ring path and the menu's `operator` extension, which is reached by `Goto` and so
never executes the main path's lines):

```
 same => n,Set(REC=${CURL(<base>/api/internal/telephony/call/record-decision,secret=<secret>&shopId=1&from=${URIENCODE(${CALLERID(num)})}&uniqueid=${UNIQUEID})})
 same => n,ExecIf($["${REC:0:1}" = "/"]?MixMonitor(${REC},b))
```

### Why a separate endpoint

`/call/inbound`'s verdict drives exact-match `GotoIf` branches for reject and menu routing - it is
the most load-bearing string in the call path. Smuggling a second value into it (a delimiter plus
`CUT`) would put **routing** at risk in order to add a **recording**. One extra localhost round
trip is the cheaper trade, and it leaves the proven verdict parsing untouched.

### Verified against the running Asterisk, not assumed

| Claim | How |
|---|---|
| `!=` / `=` are valid in `$[]` | Asterisk's own shipped configs on the box use `$["${isnresult}" != ""]` |
| `b` = record only while bridged | `core show application MixMonitor` on the live box: "Only save audio to the file while the channel is bridged" - the conversation, not the ringing |
| Both processes can share the directory | `asterisk`'s primary group is `asterisk`; `phone` is a member, so a `2770 phone:asterisk` dir is writable by Asterisk and readable by the backend. No manual deploy step. |

### Two problems found in review and fixed

1. **An error body would have become a filename.** A bare `"${REC}" != ""` guard lets anything
   non-empty through - and if the shared secret were ever stale, `authorizedParams()` answers
   `403 "invalid secret"`, so *every* call would have called `MixMonitor(invalid secret,b)`. The
   guard now requires the value to start with `/`, which rejects an empty body, an error string
   and an HTML error page alike.
2. **The path is interpolated into a dialplan argument.** A comma would truncate the MixMonitor
   argument and a paren would end the application call. The generated filename is hex-only, but
   `CALL_RECORDING_DIR` is not, so `isDialplanSafe()` refuses to record rather than emit a path
   that cannot be safely quoted. Seven tests cover it.

Also fixed: an ordering assertion that was **vacuous**. It used `indexOf("MixMonitor")`, which
matched the word inside the explanatory comment above the line, so it passed even with MixMonitor
moved after `Dial`. It now matches the verb `?MixMonitor(`, and was re-verified by deliberately
swapping the order - two tests go red.

### Known behaviour, not a bug

With option `b`, an unanswered call produces **no audio** (nothing was ever bridged) while the
`call_recording` row still exists. Playback should show "no audio - call not answered" rather than
erroring, and the stage 5 retention sweep should reconcile rows with no file.

### Review findings (two parallel reviews, 2026-10-06) — fixed

**F1 (critical, was my bug): the directory was unreachable by Asterisk.** The default was under
`/home/phone`, which is `drwxr-x--- phone phone`; `asterisk` is not in group `phone`, so it could
not traverse it. Confirmed on the box with `sudo -u asterisk test -x /home/phone` -> DENIED.
MixMonitor would have failed EACCES on **every** call while the backend logged "RECORDING" and
wrote a chain-of-custody entry — a false evidence trail, the worst failure for this feature.
I had verified group membership but not the parent directories. Fixed: the default is now
`/var/spool/asterisk/monitor`, which Asterisk owns and writes, the backend reads through group
`asterisk`, and mode 0750 keeps out of world-readable. No permission changes needed.

**F1b: the guard tested the wrong principal.** `dir.canWrite()` asks whether *the backend* can
write — true for a backend-owned directory Asterisk cannot even enter. Now checks readability
(what we actually need, for playback) and leaves write failures to MixMonitor, which reports its
own.

**The audit trail claimed recordings that might not exist.** The access entry was written at
decision time as `"record"`, before MixMonitor had opened anything — and with option `b` an
unanswered call produces no audio at all. Now `"record-requested"`, with confirmation left to a
separate file-backed event. A chain of custody that overstates is worse than none.

**The decision is now idempotent per call.** Each hit previously inserted a `call_recording` row
*and* an access row, with no dedupe and no rate limit — so anyone holding the shared secret could
flood the very log this feature exists to produce. It now returns the path already issued for that
call.

**Secret prefixes are no longer logged.** `printEnvDiagnostics` wrote the first 4 characters and
the exact length of `ADMIN_PASSWORD`, the AMI/ARI secrets, the internal secret and the CallApp key
into the journal on every restart. Now just `set` / `missing`.

Also corrected: the endpoint's own `GUARD:` comment claimed "returns empty on ANY failure", which
was untrue for the 403 path. The comment now names that exception and why the `/`-prefix dialplan
guard is what closes it.

### Known and accepted, with reasons

- **F2 — the priority shift.** Adding two lines moves `Dial` from priority 11 to 13. A channel
  parked inside `Dial` when `dialplan reload` lands can resume at the *new* numbering and re-dial
  the manager after the conversation ended (a ghost re-ring, not a drop). No code fix; this is
  exactly what Rule 2's "reload at idle" exists for. **Do the first reload with
  `core show channels` at 0.**
- **F4 — `ExecIf` propagates its inner application's return value**, and MixMonitor returns -1 on
  an empty filename, which would hang the caller up *before* `Dial`. The `${REC:0:1}" = "/"` guard
  makes an empty filename unreachable, so the trigger is closed. The reviewed alternative — nesting
  `TryExec(MixMonitor(${REC}\,b))` — needs comma escaping inside `ExecIf` and is itself a syntax
  risk on a live dialplan, so the guard is the lower-risk choice.
- **F5 — a third blocking CURL before `Dial`, and no curl timeout anywhere** (`func_curl`'s default
  is ~180 s). Confirmed: no `CURLOPT` or `curltimeout` in the tree or in `/etc/asterisk`. I have
  **not** set one, because recovery-code-rules section 6 says to calibrate against measurement, not
  intuition, and the right value depends on how long `/call/inbound` actually takes. Worth doing as
  its own change, after measuring. Noted that this adds a third instance of a pre-existing exposure.
- **F6 — no retention or free-space guard for recordings.** Stage 5. The agent is right that it
  should exist *before* the first long-lived rule, given the 2026-09-07 SQLITE_FULL outage on this
  same filesystem.

### Still open, pre-existing, and worth fixing separately

**The internal secret is world-readable on the edge.** `makeWorldReadable` forces every generated
config to `rw-r--r--`, and `/etc/asterisk/extensions_shops.conf` embeds the secret 32 times —
verified: `-rw-r--r-- phone phone`. Any local account can read it and then reach every
`/api/internal/*` endpoint, including `/provision`, which can reload chan_quectel and drop calls
fleet-wide. `0640` + group `asterisk` satisfies the original reason for 0644 (a failed `#include`
segfaults chan_quectel), but it must be done so that a failed `chgrp` leaves the file readable
rather than breaking Asterisk — its own change, not a footnote to this one.

**No rate limit or lockout on `/login`.** Unlimited attempts, no delay, no counter. This outranks
the constant-time comparison by a wide margin: an attacker brute-forces, they do not time.

**The admin UI binds `0.0.0.0`,** not just the tailnet, so it is reachable on the shop LAN at
`192.168.0.192:8080` over plain HTTP. Cookie signing stops forgery, not capture and replay; and
`AdminSession` has no issuedAt, no nonce and no server-side store, so a captured cookie is valid
until the signing key is rotated.

### Deploying it

Writing the dialplan and reloading touches `/etc/asterisk`, so **Rule 2 applies**. Note that
`dialplan reload` is not `module reload chan_quectel.so`: it re-reads extensions only and does not
re-init trunks, so it does not drop calls. Still to be done at an idle moment, and verified on
Testshop 1 first - it has a generated dialplan and no real traffic.

## 11. Stage 4 as built — admin-only playback (2026-10-06)

Scope set by Kresten: he is the only person with shell on the edge box, so the LAN-exposure and
shared-secret items in section 0.3 and the review follow-ups are **accepted risk, not being
fixed**. The guarantee that matters is narrower and now enforced in code:

> recordings must not be available for playback to shop managers or owners through the web backend.

Three independent barriers, because relying on any one would be thin:

1. **The global gate.** `WebAdmin`'s `intercept` already requires an `AdminSession` for every path
   not in `isPublic` and not starting with `/owner`. All routes live under `/recordings`.
2. **Each handler re-checks.** Every route calls `requireAdminForRecordings()`, which demands an
   `AdminSession` and explicitly notices and logs an `OwnerSession` attempt. A later edit to the
   allowlist, or a new owner prefix, cannot silently open these up.
3. **The app has no surface at all.** Nothing in `MobileApi.kt` references any recording table or
   accessor, so the JWT-authenticated manager app has no path to them.

Refusals answer **404, not 403**: whether a particular number is under covert recording is itself
the sensitive fact, and a 403 would confirm the page exists.

### The audio stream

`/recordings/audio?id=N` looks the id up in the DB, canonicalises the resulting path, and refuses
unless it sits inside the configured recordings directory — so a tampered `path` column cannot be
turned into an arbitrary file read. Never a static route, never a guessable URL (the filename is
opaque hex). Every playback writes a `play` entry to the chain of custody with the admin's
username.

### Pinned by tests, not by good intentions

`RecordingAccessIsolationTest` (10 tests) asserts the structure rather than the behaviour of one
running server, because the risk is a future edit rather than today's handlers:

- no `/recordings` entry in the public allowlist
- no recording route under the owner-gated `/owner` prefix
- every recording route sits under `/recordings` and calls the guard exactly once
- the guard requires `AdminSession`, considers `OwnerSession`, and answers 404 not 403
- the audio route canonicalises, confines to the recordings directory, and audits
- no static mapping over the recordings path
- the shared-secret dialplan endpoint answers text, never a file, and cannot enumerate recordings
- `MobileApi.kt` contains none of the recording tables or accessors

Verified to have teeth: adding `/recordings` to the allowlist, and removing the guard from the
audio handler, each turn tests red. The guard-count assertion was itself fixed after it failed to
catch the missing guard — it had been counting the helper's own declaration alongside its call
sites, so one unguarded handler still satisfied it.

### Deliberately not built

- **No second password prompt** before the recordings page. He is the only admin; the friction
  would buy little.
- **No `preserved` / export bundle / retention sweep yet** — stage 5. Note again that the reaper
  should exist before a rule has been running for months, given the 2026-09-07 SQLITE_FULL outage
  on this same filesystem.

## 12. Stage 5 as built — the reaper (2026-10-06)

### The decision that shaped it

The obvious design is "when the disk gets low, delete the oldest recordings". **This does not do
that, on purpose.** These recordings are evidence being gathered for a handover to police or
social services. Deleting them to reclaim space would destroy the thing the feature exists to
produce, in order to protect a disk with 174 GB free — recovery code destroying the working state
it was meant to protect, which is the failure this codebase keeps relearning.

So the two concerns are strictly separate:

| Concern | Response |
|---|---|
| Disk running low | **Stop starting new recordings.** Never delete. Reverses by itself once space is free. |
| A recording getting old | Delete it, **age-based only**, after 365 days, **unless marked as evidence**. |

Thresholds: 365-day retention (deliberately far past the 90-day rule window and any handover), and
a 5 GB free-space floor — far above anything SQLite needs, because the point is protecting the
database and the phone system, not using the last byte for audio. At ~1 MB/min, 5 GB is still
~80 hours of conversation in hand.

### What the sweep does hourly

1. **Finalise.** Once a file has settled (5 min), record its size and **SHA-256**, and log
   `record-confirmed`. The hash is what makes a handed-over recording hard to dispute, and it has
   to be taken before anything else touches the file. A file still being written is skipped —
   hashing a partial file would store a wrong hash.
2. **No-audio.** A row whose file never appeared is recorded as size 0 and logged `no-audio`, so
   it stops being re-examined every hour. This is the normal outcome of an unanswered call under
   MixMonitor's `b`.
3. **Delete aged.** `preserved = 0 AND started_at < cutoff`, excluded **in SQL** rather than in
   Kotlin so no code path can forget it. Each deletion prints the evidence it acted on. The row is
   kept with `deleted_at` set, so the record that a recording existed — and who accessed it —
   outlives the audio.

An undeletable file leaves its row alone so the next sweep retries; losing track of a file is
worse than trying again in an hour.

One deliberate fail-**open**: if free space cannot be determined (`usableSpace` answers 0 for an
unqueryable path), recording is allowed, because treating "could not tell" as "stop" would
silently disable the feature on an odd filesystem. MixMonitor's own write failure is the backstop,
and it says so in the log. This is the one place where failing open is the lesser evil.

### Tests

`CallRecordingReaperTest`, 14 tests. Verified to have teeth: replacing the age-and-preserved
query with "all recordings" turns **six** red, including `a preserved recording is never deleted,
at any age` and `the reaper never deletes anything because of disk pressure`.

Also covers: in-progress files not hashed, no-audio recorded once rather than hourly, the row
surviving deletion, preserve/unpreserve both audited, and the retention default being long enough
to outlast an evidence-gathering period.

### UI

The recordings list gains an Evidence column with **Keep as evidence** / **Release**, and states
the retention rule plainly, including that low disk stops new recordings rather than deleting
existing ones.

## 7. What I need from you

1. **Do the threats happen on the phone at all?** If they are purely in person, stages 1–5 are the
   wrong tool and I would rather tell you that than build them.
2. **Announce the recording, or not?** My recommendation is yes. It is your decision — ideally with
   a lawyer, since it is the difference between evidence and a GDPR problem.
3. **Inbound only, or outbound too?**
4. **May I verify finding 0.3 #1** (the forgeable session cookie)? It is a one-request test against
   your own backend and it was blocked as credential exploration, correctly. I will not attempt it
   without you saying so.
5. **Who else knows the admin password?** If any manager does, "only the admin can listen" is
   already untrue and no amount of code fixes it.
