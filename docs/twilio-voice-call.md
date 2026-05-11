# Twilio Voice — Inbound call flow + call log + blacklist

## Overview

All inbound calls to a shop's Twilio number are routed according to the **Phone flow spec**
(`Phone flow.txt`).  Every call is **immediately persisted** to the database on first webhook
hit, so the ShopManager Android app can see live calls in real time and operators can create
bookings linked to an ongoing call.

---

## Inbound call state machine

```
IncomingCall
  → (blacklisted)            RejectedBlacklisted → Terminated (silent <Reject/>)
  → (unknown customer)
      outside hours          ClosedMessage → Terminated (silent <Reject/>, no audio)
      inside hours + temp-closed   TemporaryClosedMessage → Terminated (silent <Reject/>, no audio)
      inside hours + no operator   Terminated (silent <Hangup/>, no audio)
      inside hours + operator busy Terminated (silent <Hangup/>, no audio)
      inside hours + open    OperatorWhisper → BridgedToOperator / Terminated (silent <Hangup/>)
  → (known customer)         KnownCustomerMenu (up to 3 DTMF attempts)
      digit 1                KnownCustomerSmsBooking → Terminated
      digit 2 + outside hours     ClosedMessage → Terminated (plays closedWelcomeMessage)
      digit 2 + temp-closed  TemporaryClosedMessage → Terminated (plays temporaryOperatorClosedMessage)
      digit 2 + open         OperatorWhisper → BridgedToOperator / Terminated (plays spoken feedback)
      no input × 3           Terminated
```

### Unknown caller — no audio policy

Unknown/new callers hear **no automated messages** at any stage:
- Shop closed or unavailable → silent `<Reject/>` (call is declined before being answered).
- Shop open but operator busy / misconfigured → silent `<Hangup/>`.
- Shop open and operator available → caller hears **only ringing** until the operator picks up.
  There is no hold message, no "please wait" announcement.
- Operator does not answer / declines → silent `<Hangup/>`.

### Operator whisper

Before a call is bridged to the operator:
- Customer hears **ringing only** (`answerOnBridge="true"`, no `<Say>` before `<Dial>`).
- Operator hears a private whisper (customer cannot hear this):
  - *New customer*: "Incoming call for {businessName}. New customer. Intake required."
  - *Existing customer*: "Incoming call for {businessName}. Existing customer."
- The call is bridged automatically as soon as the whisper TwiML ends.
- If the operator wants to decline, they hang up their phone before the whisper finishes.
  This triggers `dial-status` with `no-answer`/`canceled` → silent hangup for unknown callers.

---

## Backend webhooks (Twilio Console → Phone Number → Voice)

| Purpose | Method | URL |
|---------|--------|-----|
| Inbound call entry | POST | `https://{PUBLIC_BASE_URL}/api/twilio/voice/welcome` |
| Status callback (optional) | POST | `https://{PUBLIC_BASE_URL}/api/twilio/voice/status` |

All other endpoints (`/menu`, `/operator-whisper`, `/operator-accept`, `/dial-status`) are
called internally by Twilio as part of the flow; they do not need to be manually configured.

---

## Required environment variables

```
TWILIO_ACCOUNT_SID
TWILIO_AUTH_TOKEN
TWILIO_FROM_NUMBER     # E.164, used as SMS sender fallback
PUBLIC_BASE_URL        # e.g. https://api.example.com — must be reachable by Twilio
```

---

## Shop voice config (WebAdmin → Shops → Edit)

| Field | Description |
|-------|-------------|
| Twilio number (E.164) | Routes inbound calls to the correct shop |
| Operator phone (E.164) | Target for press-2 / unknown-caller transfer |
| Business name | Spoken in operator whisper |
| Welcome message (OPEN) | Played to known customers when open |
| Welcome message (CLOSED) | Played when outside opening hours |
| Temporary operator closed | Boolean toggle |
| Temporary operator closed message | Defaults to "Our phones are temporarily unavailable. Please try again in 30 minutes." |

---

## Call log (DB: `voice_call` + `voice_call_event`)

### `voice_call` table

| Column | Notes |
|--------|-------|
| `id` | Internal PK |
| `shop_id` | |
| `twilio_call_sid` | Unique Twilio call SID |
| `from_phone` | Caller E.164 |
| `to_phone` | Shop Twilio number |
| `customer_type` | `unknown` / `known` |
| `customer_id` | nullable FK to `customers` |
| `state` | Current state machine state |
| `outcome` | Terminal outcome enum |
| `is_active` | 1 while call is in progress |
| `started_at` | Epoch ms — set on first webhook hit |
| `ended_at` | Epoch ms — set on termination |
| `linked_booking_id` | nullable FK to `appointments` |

The row is **inserted immediately** at the start of `/api/twilio/voice/welcome`, before any
routing logic runs.  This means the app can display the incoming caller in real time.

### `voice_call_event` table

Timeline of every state transition: `call_id`, `state`, `note`, `created_at`.

---

## Manager API endpoints (JWT protected)

### Active calls (live call lookup)

```
GET /api/mobile/manager/shops/{shopId}/calls/active
```
Returns all calls with `is_active = 1` for the shop.  Poll this to show live incoming calls.

### Call history

```
GET /api/mobile/manager/shops/{shopId}/calls?limit=50
```

### Single call detail + event timeline

```
GET /api/mobile/manager/calls/{callId}
```
Response:
```json
{ "call": { ... }, "events": [ { "state": "...", "note": "...", "createdAt": 0 } ] }
```

---

## Booking linked to an ongoing call

Use `POST /api/mobile/manager/booking/create-json` (JSON body) instead of the form-based
`/booking/create` endpoint.  Pass `voiceCallId` to atomically link the booking to the call.

```json
{
  "shopId": 1,
  "employeeId": 3,
  "customerId": 42,
  "serviceIds": [1, 2],
  "appointmentTime": "2026-04-10 14:00",
  "voiceCallId": 17
}
```

Response:
```json
{ "appointmentId": 99, "voiceCallId": 17 }
```

The `voice_call.linked_booking_id` field is updated atomically; an event
`BOOKING_LINKED booking_id=99` is appended to `voice_call_event`.

---

## Phone blacklist (DB: `phone_blacklist`)

Blacklisted callers are **silently rejected** (`<Reject/>`) at webhook entry.

### API (JWT protected)

```
GET    /api/mobile/manager/shops/{shopId}/blacklist
POST   /api/mobile/manager/shops/{shopId}/blacklist       { "phone": "+4512345678", "reason": "..." }
DELETE /api/mobile/manager/shops/{shopId}/blacklist/{id}
```

Android blacklisting UI will be wired up in **step two**.

---

## Outbound call (existing — unchanged)

`POST /api/mobile/manager/appointments/{appointmentId}/call-customer`

Triggers an outbound Twilio call to the customer tied to an appointment.
TwiML is served from `GET/POST /api/twilio/voice/ready?msg=...`.

---

## Notes

- Twilio `X-Twilio-Signature` validation is not yet implemented; consider adding for production.
- For local testing use a tunnel (ngrok, cloudflared, etc.) and set `PUBLIC_BASE_URL` accordingly.
- The SMS is sent from the shop's configured Twilio number if present; otherwise falls back to `TWILIO_FROM_NUMBER`.
