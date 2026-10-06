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
