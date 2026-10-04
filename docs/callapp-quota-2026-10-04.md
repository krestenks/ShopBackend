# CallApp quota burn — 2026-10-04

**A quota rejection was being cached as a legitimate "not found". That one mis-classification
erased 397 cached names, disabled both safety nets built for exactly this situation, and turned a
quota error into a self-amplifying request storm of ~130 wasted calls/day.**

Investigation was read-only (a snapshot copy of `shop.db`, since removed). Claims are CONFIRMED
(I read the log line / row) unless marked otherwise. Fix committed the same day; see the end for
what still needs a decision.

---

## The evidence

Every "not found" in the cache was actually a quota rejection. CONFIRMED:

```
api_message = HTTP 429: Too Many Requests
raw_json    = {"message":"You have exceeded the MONTHLY quota for Requests on your
               current plan, PRO. Upgrade your plan at .../DataCrawler/api/callapp"}
```

Grouping the whole table by `api_message` — three distinct values, ever:

| `api_message` | rows | first | last |
|---|---|---|---|
| `Success` | 994 | 2026-07-15 | **2026-09-26 13:06** |
| `HTTP 429: Too Many Requests` | **397** | 2026-10-01 09:56 | 2026-10-04 09:56 |
| `Invalid phone number or data not found.` | **1** | 2026-07-28 | 2026-07-28 |

So there is exactly **one** genuine not-found in the system's entire history. The other 397 were
rejections filed as answers.

Daily writes show the step change when the quota ran out:

| Period | Writes/day | Outcome |
|---|---|---|
| 13–26 Sept | 13–75 (avg ~40) | all `found=1`, healthy |
| 27–30 Sept | 0 | nothing due |
| 1–4 Oct | **111 / 113 / 134 / 41** | **100% HTTP 429** |

## Mechanism

`CallAppRapidApiClient.lookup()` returned a *result* for any non-2xx status instead of throwing:

```kotlin
if (!response.status.isSuccess()) {
    return CallAppLookupResult(found = false, ..., message = "HTTP $httpStatus: ...")
}
```

The caller could not tell that apart from a real answer, so it took the **success** path —
`upsertCustomerCallAppScreening`, which hard-codes `error = NULL, failure_count = 0,
next_retry_at = NULL` and sets `name = excluded.name` (NULL for a rejection).

Four consequences, all compounding:

1. **Exponential backoff bypassed.** `getCustomersNeedingCallAppScreening` routes rows by
   `error IS NULL`; with the error field cleared, a rejection took the short *not-found* TTL
   branch rather than `next_retry_at`.
2. **Circuit breaker unreachable.** The loop ran `consecutiveFailures.set(0)` on every 429,
   because it looked like a success. The breaker built for precisely this case could never fire.
3. **The cache actively destroyed.** A 429 overwrote a good `found=1` row (30-day TTL) with
   `found=0` (3-day TTL). Each overwrite multiplied that customer's request rate by 10 — which is
   why 1 Oct jumped from ~40/day to ~130/day in a single step. Self-amplifying.
4. **Silent data loss.** The error path deliberately preserves the last good name; the success
   path does not. **397 customers had their CallApp name overwritten with NULL**, and `raw_json`
   was overwritten too, so the names are unrecoverable from that table — they must be re-fetched.

And it left **no trace in the journal**: four days of storm, zero `[CallApp]` lines. Silence is how
it stayed invisible.

## The separate, chronic problem

Even with the bug fixed, the old TTLs did not fit the plan:

- 1370 customers ÷ 30-day refresh = **~46/day ≈ 1370 requests/month**
- Plan: **1500/month** → 91% of quota spent refreshing data we already had, leaving ~130/month for
  the ~18 new customers/day (≈550/month).

September's exhaustion was not bad luck; steady-state demand was roughly 2× the plan. A CallApp
name for a given number essentially never changes, so re-screening hits was close to pure waste.

## What was changed

| # | Change | Effect |
|---|---|---|
| 1 | `lookup()` **throws** on non-2xx — `CallAppQuotaExceededException` for 429, `CallAppHttpException` otherwise | A returned result now always means the API answered, so it is always safe to cache |
| 2 | **Quota stop**: a 429 writes *nothing* and suppresses all lookups for 6h (one scheduler cycle), honoured by the batch *and* the on-contact path | ~130 wasted requests/day → ~4/day, cache left intact |
| 3 | Hit TTL 30 days → **365 days** | Steady state ~1370/month → ~550/month |
| 4 | Not-found TTL 3 days → **30 days** | Each not-found: 10 requests/month → 1 |
| 5 | Batch returns a `BatchSummary` and **logs every run**; `requests` now counts only calls that reached the API | The next incident is visible in the journal |
| 6 | Startup repair deletes the 397 mis-filed rejection rows | Destroyed names get re-fetched instead of sitting wrong behind a 30-day TTL |
| 7 | Hourly cleanup prunes orphan screening rows (34 found) | Cache row count matches the customer count |

The quota stop is deliberately a *different mechanism* from the circuit breaker, because the two
know different things: the breaker is a guess that the API is unhealthy and worth probing past
(so `screenCustomerNow` still bypasses it), whereas a 429 is certainty that the request cannot be
served, so a best-effort attempt is waste with no upside. It is in-memory, so a backend restart
costs one extra probe request — accepted rather than adding persistence.

### Tests

`CallAppRapidApiClientTest` runs the real CIO client against a throwaway loopback HTTP server:
429 → quota exception, other non-2xx → HTTP exception, 401 → HTTP exception (a bad key must not be
mistaken for exhausted quota), and the hit/miss parsing still works including UTF-8 names.
Verified to have teeth: reverting the one-line fix turns 3 of them red.

`CallAppQuotaHandlingTest` covers the service layer: a 429 writes nothing through *either* upsert,
stops the batch after one request, suppresses later batches and on-contact lookups, expires so a
restored quota is picked up, and still routes a 503 to the error path and a genuine not-found to
the cache. 82 tests in the suite, all passing.

## Expected numbers after the fix

- While the quota is exhausted: **~4 probe requests/day** (one per scheduler run), no DB writes.
- Once the quota resets: a one-off **397 requests** to restore the destroyed names (≈2 days at the
  50-per-batch cap), then steady state of roughly **550–600/month** against the 1500/month plan.

## Open / needs a decision

- **Deploying this needs a backend restart** (Rule 2) — the startup re-provision reloads
  chan_quectel, so it waits for an idle moment.
- The 429s themselves probably do **not** consume quota (RapidAPI normally rejects over-quota
  requests at the gateway without counting them), so the current storm likely is not eating the
  next cycle's allowance. SUSPECTED — not verified against RapidAPI's dashboard.
- Unknown when the billing cycle actually resets; the 1 Oct requests were still rejected, so it is
  not the 1st of the month. Worth reading off the RapidAPI dashboard.
- The 2 `InvalidPhone` rows have `failure_count = 10` and retry weekly forever without calling the
  API. Harmless, but they occupy batch slots.
