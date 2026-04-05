# Twilio Voice Call ("Ready for you")

This backend supports triggering an outbound Twilio voice call to a customer for an appointment. The call plays a Text-to-Speech (TTS) message telling the customer they can come to the door.

## Overview

1. **Shop app** calls the backend endpoint:
   `POST /api/mobile/manager/appointments/{appointmentId}/call-customer`
2. Backend starts a Twilio outbound call via Twilio REST API (`Calls.json`).
3. Twilio requests TwiML from:
   `GET/POST /api/twilio/voice/ready?msg=...`
4. Backend responds with TwiML containing `<Say>`.

## Required environment variables

Backend (`ShopBackend`):

- `TWILIO_ACCOUNT_SID`
- `TWILIO_AUTH_TOKEN`
- `TWILIO_FROM_NUMBER` (E.164 format, e.g. `+4512345678`)
- `PUBLIC_BASE_URL` (publicly reachable base URL of this backend, e.g. `https://api.example.com`)

Notes:

- Twilio must be able to reach `PUBLIC_BASE_URL/api/twilio/voice/ready`.
- For local testing you typically need a tunnel (ngrok, cloudflared, etc.).

## Backend endpoints

### 1) Trigger a call (JWT protected)

`POST /api/mobile/manager/appointments/{appointmentId}/call-customer`

Headers:

- `Authorization: Bearer <jwt>`

Body (JSON, optional):

```json
{ "message": "Hello. We are ready for you now. Please come to the door." }
```

Response (JSON):

```json
{ "success": true, "status": 201, "twilio": "{...Twilio JSON...}" }
```

### 2) TwiML (public)

`GET /api/twilio/voice/ready?msg=...`

Returns:

```xml
<Response>
  <Say voice="alice">Hello ...</Say>
</Response>
```

## Incoming call: press 1 to receive booking link by SMS

This backend also supports an IVR flow on incoming calls to the shop’s Twilio number:

1. Caller rings the shop’s Twilio number.
2. Twilio hits our webhook `/api/twilio/voice/welcome`.
3. Backend returns TwiML with:
   - `<Say>` welcome message (open/closed)
   - `<Gather>` menu:
     - Press 1: receive a booking link by SMS
     - Press 2: forward to operator (only when open)
4. If caller presses 1 or 2, Twilio posts to `/api/twilio/voice/menu`.
5. If caller pressed 1: backend sends an SMS with booking link.
6. If caller pressed 2 (and shop is open): backend responds with `<Dial>` to the shop’s operator phone.

### Required setup in WebAdmin

For each shop, go to **Shops → Edit** and set:

- **Shop Twilio number (E.164)** (used to route incoming calls to the correct shop)
- **Operator phone (E.164)** (used for press-2 call forwarding)
- **Welcome message (OPEN)**
- **Welcome message (CLOSED)**
- **Opening hours** (used to decide open vs closed)

### Twilio Console configuration

In Twilio Console → Phone Numbers → (your number) → **Voice & Fax**:

- **A call comes in**: Webhook
  - Method: **POST**
  - URL: `https://<PUBLIC_BASE_URL>/api/twilio/voice/welcome`

### Notes

- The SMS is sent from the shop’s configured Twilio number if present; otherwise it falls back to env `TWILIO_FROM_NUMBER`.
- The caller’s phone number is taken from Twilio’s `From` parameter.

## Android app (ShopManager)

The ShopManager Android app adds a **Call customer** button on each appointment card. Tapping it opens a dialog where the staff can edit the TTS message and then trigger the backend call.

Files changed:

- `app/src/main/java/com/example/shopmanager/ApiService.kt`
- `app/src/main/java/com/example/shopmanager/AppointmentAdapter.kt`
- `app/src/main/java/com/example/shopmanager/ShopScheduleActivity.kt`
- `app/src/main/res/layout/appointment_item.xml`
