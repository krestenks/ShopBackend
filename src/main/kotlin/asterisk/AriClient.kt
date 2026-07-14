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
    suspend fun upsertEndpoint(shopId: Int, sipPassword: String) {
        val endpointId = config.endpointId(shopId)

        putConfig("aor", "$endpointId-aor", mapOf(
            "max_contacts" to "3",
            "qualify_frequency" to "30",
            "remove_existing" to "yes",
        ))
        putConfig("auth", "$endpointId-auth", mapOf(
            "auth_type" to "userpass",
            "username" to endpointId,
            "password" to sipPassword,
        ))
        putConfig("endpoint", endpointId, mapOf(
            "context" to config.outboundContext(shopId),
            "allow" to "!all,ulaw,alaw,g722",
            "direct_media" to "no",
            "rtp_symmetric" to "yes",
            "force_rport" to "yes",
            "rewrite_contact" to "yes",
            "auth" to "$endpointId-auth",
            "aors" to "$endpointId-aor",
        ))
    }

    suspend fun deleteEndpoint(shopId: Int) {
        val endpointId = config.endpointId(shopId)
        deleteConfig("endpoint", endpointId)
        deleteConfig("auth", "$endpointId-auth")
        deleteConfig("aor", "$endpointId-aor")
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
