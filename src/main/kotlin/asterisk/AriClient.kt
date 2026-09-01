package asterisk

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject

/**
 * Minimal ARI client used for dynamic PJSIP provisioning (no config file edits,
 * no reload). Uses ARI "push configuration":
 *
 *   PUT /ari/asterisk/config/dynamic/res_pjsip/{objectType}/{id}
 *
 * NOTE (server prerequisite): dynamic objects must be mapped to a writable sorcery
 * backend in /etc/asterisk/sorcery.conf, e.g.
 *   [res_pjsip]
 *   endpoint=astdb,pjsip/endpoint
 *   auth=astdb,pjsip/auth
 *   aor=astdb,pjsip/aor
 * plus res_pjsip.conf "endpoint_identifier_order" left at default. Without this,
 * the PUT returns 403/404.
 */
class AriClient(private val config: AsteriskConfig) {

    private val client = HttpClient(CIO)

    /** Creates or updates the PJSIP endpoint+auth+aor triplet for a shop's manager app. */
    suspend fun upsertEndpoint(shopId: Int, sipPassword: String) =
        upsertSipAccount(config.endpointId(shopId), sipPassword, config.outboundContext(shopId))

    /** Creates or updates the endpoint the IN-SHOP device registers as (internal-only context). */
    suspend fun upsertPhoneEndpoint(shopId: Int, sipPassword: String) =
        upsertSipAccount(config.phoneEndpointId(shopId), sipPassword, config.internalContext(shopId))

    /**
     * Creates or updates a MANAGER's single SIP identity (mgr{id}). A pool of managers
     * each register their own endpoint; the duty-aware routing (and manager-to-manager
     * intercom) forks the call to every covering manager's endpoint.
     *
     * max_contacts=1 (+ remove_existing): one manager = one active phone, so a fresh
     * REGISTER REPLACES the previous contact. Without this, a re-onboarded phone (new
     * tailnet IP) or an app restart (new ephemeral port) leaves the old contact behind;
     * those ghosts qualify as Unavailable and Dial() forks to them, timing out the call
     * on NO ANSWER even when the live contact would answer. (Asterisk 18.10 predates the
     * AOR remove_unavailable option, so capping contacts is the portable fix; genuine
     * multi-device would need push-wake, which is deferred.)
     */
    suspend fun upsertManagerEndpoint(managerId: Int, sipPassword: String) =
        upsertSipAccount(config.managerEndpointId(managerId), sipPassword, config.managerContext(managerId), maxContacts = 1)

    suspend fun deleteManagerEndpoint(managerId: Int) {
        val endpointId = config.managerEndpointId(managerId)
        deleteConfig("endpoint", endpointId)
        deleteConfig("auth", "$endpointId-auth")
        deleteConfig("aor", endpointId)
    }

    /**
     * Per-AOR qualify period, deliberately NOT the same number for every AOR.
     *
     * One phone hosts several AORs (its own mgr{id} plus a shop{n}-manager for every shop it
     * covers). With an identical frequency they were all created together and stayed in
     * lockstep, so Asterisk fired every OPTIONS for that handset at the same instant.
     *
     * That is the worst possible arrangement, because the app answers only ONE SIP message per
     * Core.iterate() tick (measured: replies to a simultaneous burst of 5 came back spaced at
     * exactly one tick). Five AORs in lockstep therefore made the last one wait five ticks.
     * Spreading the period means each OPTIONS usually arrives alone and costs a single tick.
     *
     * 27..33 keeps the mean at the previous 30 s, so total probe load is unchanged; the values
     * are derived from the AOR id so they are stable across restarts rather than random. This
     * is a mitigation for phones still running the old 500 ms pump — the real fix is the pump
     * itself (ShopManager `ITERATE_INTERVAL_IDLE_MS`, dropped 500 -> 20). See
     * `docs/derp-latency-findings.md`.
     */
    private fun qualifyFrequencyFor(endpointId: String): Int =
        27 + Math.floorMod(endpointId.hashCode(), 7)

    /**
     * Generic endpoint+auth+aor triplet. The AOR id MUST equal the registering SIP
     * username (= endpointId), or PJSIP's AOR lookup on REGISTER fails with 404.
     */
    private suspend fun upsertSipAccount(endpointId: String, sipPassword: String, context: String, maxContacts: Int = 3) {
        putConfig("aor", endpointId, mapOf(
            "max_contacts" to maxContacts.toString(),
            "qualify_frequency" to qualifyFrequencyFor(endpointId).toString(),
            // Manager phones (esp. Samsung) intermittently stall answering OPTIONS for ~1s+
            // even on a healthy tailnet, so the default 3s timeout flaps them to Unavailable
            // and inbound calls hit "line busy".
            //
            // Raised 8 -> 15 on 2026-08-30. Measured true WIRE RTT (tcpdump, OPTIONS correlated to
            // its response by Call-ID) over 64 samples: median 287ms, p90 455ms, max 951ms — the
            // phone always answers inside ~1s. But Asterisk's OWN reported figure ran higher over
            // the same window (median 310ms, max 1451ms) and has reported 3864/6574/7549ms
            // elsewhere. Since qualify_timeout is evaluated against that reported number, Asterisk
            // can mark a phone Unavailable that in fact answered promptly — and the backend then
            // sends recovery commands to a healthy handset, which is pure churn.
            //
            // 15s keeps a 15x margin over the measured worst-case wire RTT while tolerating the
            // reported inflation. Deliberately a mitigation, not a fix: the inflation's cause is
            // still unexplained (Asterisk CPU was measured at ~0.9% of a core, so NOT load).
            // Cost of a longer timeout is slower detection of a genuinely dead phone; at
            // qualify_frequency=30 that is one extra probe cycle, which the SipMonitor already
            // tolerates.
            "qualify_timeout" to "15",
            "remove_existing" to "yes",
        ))
        putConfig("auth", "$endpointId-auth", mapOf(
            "auth_type" to "userpass",
            "username" to endpointId,
            "password" to sipPassword,
        ))
        putConfig("endpoint", endpointId, mapOf(
            "context" to context,
            "allow" to "!all,ulaw,alaw,g722",
            "direct_media" to "no",
            "rtp_symmetric" to "yes",
            "force_rport" to "yes",
            "rewrite_contact" to "yes",
            "auth" to "$endpointId-auth",
            "aors" to endpointId,
        ))
    }

    suspend fun deleteEndpoint(shopId: Int) {
        for (endpointId in listOf(config.endpointId(shopId), config.phoneEndpointId(shopId))) {
            deleteConfig("endpoint", endpointId)
            deleteConfig("auth", "$endpointId-auth")
            deleteConfig("aor", endpointId)
        }
        deleteConfig("aor", "${config.endpointId(shopId)}-aor")   // pre-fix AOR name, if present
    }

    private suspend fun putConfig(objectType: String, id: String, fields: Map<String, String>) {
        val payload = buildJsonObject {
            putJsonArray("fields") {
                fields.forEach { (attr, value) ->
                    addJsonObject {
                        put("attribute", attr)
                        put("value", value)
                    }
                }
            }
        }
        val resp = client.put("${config.ariBaseUrl}/ari/asterisk/config/dynamic/res_pjsip/$objectType/$id") {
            basicAuth(config.ariUsername, config.ariPassword)
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        if (resp.status.value !in 200..299) {
            throw AriException("PUT $objectType/$id failed: ${resp.status} ${resp.bodyAsText().take(300)}")
        }
    }

    private suspend fun deleteConfig(objectType: String, id: String) {
        val resp = client.delete("${config.ariBaseUrl}/ari/asterisk/config/dynamic/res_pjsip/$objectType/$id") {
            basicAuth(config.ariUsername, config.ariPassword)
        }
        // 404 = already gone — fine for deprovisioning.
        if (resp.status.value !in 200..299 && resp.status != HttpStatusCode.NotFound) {
            throw AriException("DELETE $objectType/$id failed: ${resp.status} ${resp.bodyAsText().take(300)}")
        }
    }
}

class AriException(message: String) : RuntimeException(message)
