# Twilio setup (SMS + Voice)

This project uses Twilio for:

1. **SMS webhooks** (existing chatbot endpoint)
2. **Outbound voice calls with TTS** ("ready for you" feature)

This document describes everything you need to configure Twilio and the backend.

---

## 1) What you need from Twilio

### A) Twilio Account SID + Auth Token

In the Twilio Console:

- **Account SID** (looks like `ACxxxxxxxx...`)
- **Auth Token**

These are used by the backend to authenticate to Twilio REST API.

### B) A Twilio phone number

You need a Twilio number that supports:

- SMS (for chatbot, optional)
- Voice calls (for "ready for you" calls)

Buy/configure it in:

**Twilio Console → Phone Numbers → Manage → Active Numbers**

Make sure the number is in **E.164 format**, e.g. `+4512345678`.

---

## 2) Backend environment variables

Set the following environment variables for the `ShopBackend` process:

| Variable | Required | Example | Used for |
|---|---:|---|---|
| `TWILIO_ACCOUNT_SID` | Yes | `AC...` | REST API auth |
| `TWILIO_AUTH_TOKEN` | Yes | `xxxxxxxx` | REST API auth |
| `TWILIO_FROM_NUMBER` | Yes | `+4512345678` | Caller ID for outbound voice calls |
| `PUBLIC_BASE_URL` | Yes (voice) | `https://api.example.com` | Twilio must fetch TwiML from this URL |
| `PUBLIC_BOOKING_URL` | No (legacy) | `https://api.example.com` | Public base URL for booking links (if PUBLIC_BASE_URL not set) |
| `PORT` | No | `8080` | Server port |

Notes:

- `PUBLIC_BASE_URL` **must be publicly reachable by Twilio**. If you run locally, use a tunnel like **ngrok** or **cloudflared**.
- The backend will call Twilio's REST API and tells Twilio to fetch TwiML from:
  `PUBLIC_BASE_URL/api/twilio/voice/ready`

- Booking links are generated using:
  - `PUBLIC_BASE_URL` (preferred), otherwise
  - `PUBLIC_BOOKING_URL` (legacy name)

---

## 3) Twilio configuration (Console)

### A) Voice: outbound calls + TwiML

Nothing has to be configured in the Twilio Console for the TwiML endpoint specifically.

The backend creates calls using Twilio REST API (`Calls.json`) and provides the TwiML URL for each call.

The TwiML URL the backend uses:

```
GET/POST {PUBLIC_BASE_URL}/api/twilio/voice/ready?msg=...&appointment_id=...
```

That endpoint returns TwiML:

```xml
<Response>
  <Say voice="alice">Hello. We are ready for you now...</Say>
</Response>
```

### B) SMS: inbound webhook (chatbot)

If you want Twilio SMS chatbot to work:

In Twilio Console for your phone number:

**Active Numbers → (your number) → Messaging**

Set **A message comes in** to:

```
POST {PUBLIC_BASE_URL}/api/twilio/webhook
```

---

## 4) App/Backend API endpoints

### Trigger a “ready for you” call (JWT protected)

Endpoint:

```
POST /api/mobile/manager/appointments/{appointmentId}/call-customer
```

Headers:

```
Authorization: Bearer <jwt>
```

Body (JSON, optional):

```json
{ "message": "Hello. We are ready for you now. Please come to the door." }
```

Response:

```json
{ "success": true, "status": 201, "twilio": "{...twilio-json...}" }
```

### TwiML endpoint (public)

```
GET /api/twilio/voice/ready?msg=Hello
```

---

## 5) Local development notes

Because Twilio must reach your server over the public internet, local testing requires a tunnel.

Example using ngrok:

1. Start backend on port 8080
2. Start tunnel:
   ```
   ngrok http 8080
   ```
3. Set:
   - `PUBLIC_BASE_URL=https://<your-ngrok-subdomain>.ngrok-free.app`
4. Trigger a call from the Android app.

---

## 6) Troubleshooting

### Common errors

- **401 from Twilio**: wrong `TWILIO_ACCOUNT_SID` or `TWILIO_AUTH_TOKEN`
- **21212 / Twilio can't reach URL**: `PUBLIC_BASE_URL` not reachable (need tunnel / DNS / https)
- **From number not allowed**: `TWILIO_FROM_NUMBER` not a Twilio number on your account or not voice-enabled

### Logs

Backend prints a line like:

```
[TwilioVoice] Calling customer +45... for appointment 123 (shop 1)
```

And returns Twilio JSON body in the API response for debugging.
