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
     * Generic endpoint+auth+aor triplet. The AOR id MUST equal the registering SIP
     * username (= endpointId), or PJSIP's AOR lookup on REGISTER fails with 404.
     */
    private suspend fun upsertSipAccount(endpointId: String, sipPassword: String, context: String, maxContacts: Int = 3) {
        putConfig("aor", endpointId, mapOf(
            "max_contacts" to maxContacts.toString(),
            "qualify_frequency" to "30",
            // Manager phones (esp. Samsung) intermittently stall answering OPTIONS for ~1s+
            // even on a healthy tailnet, so the default 3s timeout flaps them to Unavailable
            // and inbound calls hit "line busy". 8s tolerates those app-side stalls.
            "qualify_timeout" to "8",
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
