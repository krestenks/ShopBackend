# Manager pool per tenant — plan

Goal: let a **pool of managers** handle a tenant's (owner's) shops. Several managers can
be logged in at once; when a call comes in we ring **all on-duty** managers who cover that
shop (SIP forking; first to answer wins). A manager can go **off duty** to mute call and
notification traffic. Duty is **tenant-wide** (a manager toggles on/off once and covers all
shops they're assigned to).

## Where we're starting from (as of 2026-07-28)

- **Tenant = `owner`.** `owner_id` is on `managers`, `shops`, `customers`, etc. JWT carries
  `userId`, `role` (`manager`|`shop`), `ownerId`.
- **Shop→manager was strictly 1:1** via `shops.manager_id`. A manager can own many shops; a
  shop had exactly one manager. No pool table existed. (`manager_shop` referenced by the dead
  `assignManagerToShop()` was never created — ignore it.)
- **One SIP endpoint per shop:** `shop{id}-manager` (AOR `max_contacts=3`). Inbound GSM calls
  hit `POST /api/internal/telephony/call/inbound`, which returns a verdict string; the
  generated dialplan then `Dial(PJSIP/shop{id}-manager,30)`
  (`asterisk/AsteriskConfigWriters.kt`).
- **No presence / duty / push.** Ringing works only because the app keeps one SIP
  registration alive per shop via a foreground service. `TODO(FCM)` marks the push hook at
  `asterisk/InternalTelephonyRoutes.kt`.

## Core design

Three new concepts, then routing becomes duty-aware.

1. **Pool membership** — new M:N table `shop_manager(shop_id, manager_id, owner_id, created_at)`.
   `shops.manager_id` stays as the shop's *primary* manager (intercom grouping, group-chat
   room, fallback). The covering set for a shop = its primary manager ∪ its `shop_manager` rows.
   Backfilled from `shops.manager_id` so nothing changes until owners populate pools.

2. **Per-manager SIP identity** (Phase 2) — provision one PJSIP endpoint per manager,
   `mgr{managerId}`, instead of one per shop. The app registers a single identity regardless
   of how many shops it covers.

3. **Duty state** (server-authoritative) — new table
   `manager_duty(manager_id PK, owner_id, on_duty, updated_at)`, tenant-wide boolean. Off-duty
   managers are excluded from ring targets (and from future push) by the backend, independent
   of whether their app is still registered.

**Routing** (Phase 3): `call/inbound` computes the on-duty covering managers for the shop and
returns their endpoints as a forked dial string (`PJSIP/mgr7&PJSIP/mgr12`). Asterisk rings all;
first answer wins. Nobody on duty → fallback.

## Phases

### Phase 1 — Data model & duty API (this change)
- Migrations: `shop_manager`, `manager_duty`; backfill `shop_manager` from `shops.manager_id`.
- DB helpers: pool-aware `getShopsForManager` / `isManagerOfShop` (union primary + pool),
  `getManagerIdsForShop`, `setShopPool`, and duty (`isManagerOnDuty`, `setManagerDuty`,
  `getOnDutyManagerIdsForShop`).
- Mobile API: `GET`/`POST /api/mobile/duty`.
- Admin UI: a "Call pool" multi-select on both shop-edit pages (super-admin + owner).

### Phase 2 — Per-manager SIP endpoints
- `AsteriskConfig.managerEndpointId(id) = "mgr$id"`; `AriClient.upsertManagerEndpoint`;
  provision per manager in `AsteriskProvisioner`; store `mgr_sip_password`.
- `GET /api/mobile/telephony/sip-credentials` (no shopId) → the manager's own creds.

### Phase 3 — Duty-aware routing (the heart)
- `call/inbound` (and the menu "operator" path) compute the on-duty covering managers and
  return the forked dial target.
- `AsteriskConfigWriters`: `Dial(${TARGETS},30)` in the `ring` and `operator` paths.
- No-one-on-duty fallback: treat like temporary-operator-closed (SMS booking / "unavailable"),
  with an optional per-tenant switch to ring the primary manager anyway.

### Phase 4 — Notification muting (app repo `AndroidStudioProjects\ShopManager`)
- Duty toggle UI → `POST /api/mobile/duty`. Register the single `mgr{id}` identity.
- Off-duty: suppress `IncomingCallWatcherService` notifications, stop the ringing foreground
  service / SIP-unregister. On-duty: re-register.
- SMS threads scoped to `shop_manager` coverage; notifications gated by duty.

### Phase 5 — (Deferred) FCM push-wake
- Push-wake only on-duty covering managers' device tokens before Asterisk dials.

## Open decisions
- **Coverage granularity:** explicit `shop_manager` assignment (chosen) vs. auto-link every
  manager to every shop in the tenant.
- **No-one-on-duty fallback:** SMS-booking/"unavailable" (default) vs. ring primary anyway.
- **Busy handling:** include everyone in the fork (start here) vs. exclude via `isOperatorBusy`.
- **Default duty after deploy:** absent row = off. Revisit when Phase 3 wires routing so the
  current single-manager shops don't go silent (likely default backfilled managers to on).
