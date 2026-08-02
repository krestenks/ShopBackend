package controlplane

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Configuration for the platform control plane's Headscale integration.
 *
 * The control plane is the ONLY component that holds the Headscale admin API key (a
 * global-admin credential) — never an edge/tenant backend. It co-locates on the
 * Headscale box, so [apiUrl] normally points at the local Headscale (via a
 * `127.0.0.1 headscale.warpfactor.dk` hosts entry so the cert SNI still validates).
 *
 * Disabled (all methods throw) when [apiKey] is blank.
 */
data class TailnetConfig(
    /** Headscale REST API base, e.g. `https://headscale.warpfactor.dk` (no trailing slash, no /api). */
    val apiUrl: String,
    /** Headscale admin API key (Bearer). Create with `headscale apikeys create`. */
    val apiKey: String,
    /** Control-server URL clients embed to join, e.g. `https://headscale.warpfactor.dk`. */
    val controlUrl: String,
    /** MagicDNS base domain of the tailnet, e.g. `ts.warpfactor.dk`. */
    val magicDnsBase: String,
    /** Deep-link scheme the Android app registers, e.g. `shopmanager`. */
    val deepLinkScheme: String = "shopmanager",
    /** Default device-invite lifetime. */
    val inviteTtl: Duration = Duration.ofHours(24),
    val timeoutMs: Long = 15_000,
) {
    val enabled: Boolean get() = apiKey.isNotBlank() && apiUrl.isNotBlank()

    companion object {
        /** Builds config from env; returns null (feature off) when HEADSCALE_API_KEY is unset. */
        fun fromEnv(): TailnetConfig? {
            val key = System.getenv("HEADSCALE_API_KEY")?.takeIf { it.isNotBlank() } ?: return null
            val api = System.getenv("HEADSCALE_API_URL")?.takeIf { it.isNotBlank() }
                ?: "https://headscale.warpfactor.dk"
            return TailnetConfig(
                apiUrl = api.trimEnd('/'),
                apiKey = key,
                controlUrl = System.getenv("HEADSCALE_CONTROL_URL")?.takeIf { it.isNotBlank() } ?: api.trimEnd('/'),
                magicDnsBase = System.getenv("HEADSCALE_MAGICDNS_BASE")?.takeIf { it.isNotBlank() } ?: "ts.warpfactor.dk",
                deepLinkScheme = System.getenv("APP_DEEPLINK_SCHEME")?.takeIf { it.isNotBlank() } ?: "shopmanager",
            )
        }
    }
}

/** A Headscale user (one per tenant: name = `tenant-{ownerId}`). */
data class HsUser(val id: String, val name: String)

/** A Headscale pre-auth key. [key] is the secret a device joins with. */
data class HsPreAuthKey(
    val id: String,
    val key: String,
    val userId: String,
    val aclTags: List<String>,
    val reusable: Boolean,
    val ephemeral: Boolean,
    val expiration: String?,
)

/** A device onboarding invite: the pre-auth key plus the deep link the manager scans/taps. */
data class DeviceInvite(
    val ownerId: Int,
    val deviceLabel: String,
    /** Id of the minted pre-auth key — used to attach the friendly [deviceLabel] to the node that
     *  later joins with it (the node JSON carries `preAuthKey.id`). */
    val preAuthKeyId: String,
    val preAuthKey: String,
    val deepLink: String,
    val expiration: String?,
)

/**
 * Thin Kotlin wrapper over the Headscale REST API (`/api/v1`) for tenant + device onboarding.
 *
 * Tenant model: one Headscale user per tenant, named `tenant-{ownerId}`, and every device the
 * tenant owns is tagged `tag:tenant-{ownerId}`. The ACL (managed here in Headscale's *database*
 * policy mode) is deny-by-default: a tenant reaches only its own devices and the shared
 * `tag:llm-server`. Tags can only be assigned through pre-auth keys minted here with the admin
 * key — devices cannot self-assign.
 *
 * All shapes verified against Headscale v0.29.3.
 */
class TailnetService(private val config: TailnetConfig) {

    private val base = "${config.apiUrl}/api/v1"

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = config.timeoutMs
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = config.timeoutMs
        }
        expectSuccess = false
    }

    // ── tenant naming ────────────────────────────────────────────────────────
    fun tenantUserName(ownerId: Int) = "tenant-$ownerId"
    fun tenantTag(ownerId: Int) = "tag:tenant-$ownerId"
    private val llmTag = "tag:llm-server"

    // ── HTTP helpers ─────────────────────────────────────────────────────────
    private suspend fun req(
        method: HttpMethod,
        path: String,
        body: JsonElement? = null,
    ): JsonObject {
        val resp: HttpResponse = client.request("$base$path") {
            this.method = method
            headers { append(HttpHeaders.Authorization, "Bearer ${config.apiKey}") }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }
        val text = resp.bodyAsText()
        if (!resp.status.isSuccess()) {
            error("Headscale API ${method.value} $path -> ${resp.status}: ${text.take(300)}")
        }
        return if (text.isBlank()) JsonObject(emptyMap())
        else Json.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.userOf(field: String = "user"): HsUser {
        val u = this[field]!!.jsonObject
        return HsUser(u["id"]!!.jsonPrimitive.content, u["name"]!!.jsonPrimitive.content)
    }

    // ── Users ────────────────────────────────────────────────────────────────

    suspend fun listUsers(): List<HsUser> =
        req(HttpMethod.Get, "/user")["users"]?.jsonArray.orEmpty().map {
            val o = it.jsonObject
            HsUser(o["id"]!!.jsonPrimitive.content, o["name"]!!.jsonPrimitive.content)
        }

    suspend fun findUser(name: String): HsUser? = listUsers().firstOrNull { it.name == name }

    suspend fun createUser(name: String): HsUser =
        req(HttpMethod.Post, "/user", buildJsonObject { put("name", name) }).userOf()

    suspend fun deleteUser(id: String) { req(HttpMethod.Delete, "/user/$id") }

    /** Idempotently ensures the tenant's Headscale user exists. */
    suspend fun ensureTenantUser(ownerId: Int): HsUser =
        findUser(tenantUserName(ownerId)) ?: createUser(tenantUserName(ownerId))

    // ── Pre-auth keys ─────────────────────────────────────────────────────────

    suspend fun createPreAuthKey(
        userId: String,
        tags: List<String>,
        reusable: Boolean = false,
        ephemeral: Boolean = false,
        ttl: Duration = config.inviteTtl,
    ): HsPreAuthKey {
        val expiry = DateTimeFormatter.ISO_INSTANT.format(Instant.now().plus(ttl))
        val body = buildJsonObject {
            put("user", userId)
            put("reusable", reusable)
            put("ephemeral", ephemeral)
            put("expiration", expiry)
            putJsonArray("aclTags") { tags.forEach { add(it) } }
        }
        val k = req(HttpMethod.Post, "/preauthkey", body)["preAuthKey"]!!.jsonObject
        return HsPreAuthKey(
            id = k["id"]!!.jsonPrimitive.content,
            key = k["key"]!!.jsonPrimitive.content,
            userId = k["user"]!!.jsonObject["id"]!!.jsonPrimitive.content,
            aclTags = k["aclTags"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            reusable = k["reusable"]?.jsonPrimitive?.booleanOrNull ?: false,
            ephemeral = k["ephemeral"]?.jsonPrimitive?.booleanOrNull ?: false,
            expiration = k["expiration"]?.jsonPrimitive?.contentOrNull,
        )
    }

    suspend fun expirePreAuthKey(userId: String, key: String) {
        req(HttpMethod.Post, "/preauthkey/expire", buildJsonObject {
            put("user", userId); put("key", key)
        })
    }

    // ── Nodes ────────────────────────────────────────────────────────────────

    /** Returns (id, name, user, tags) for every registered node. */
    suspend fun listNodes(): List<JsonObject> =
        req(HttpMethod.Get, "/node")["nodes"]?.jsonArray.orEmpty().map { it.jsonObject }

    suspend fun deleteNode(id: String) { req(HttpMethod.Delete, "/node/$id") }

    /** Expires (logs out) a node without deleting it — it must re-authenticate to return. */
    suspend fun expireNode(id: String) { req(HttpMethod.Post, "/node/$id/expire") }

    /** The ACL tags of a single node (e.g. `["tag:tenant-1"]`); empty if the node is unknown.
     *  Read from the node's `tags` field — NOT `forcedTags`/`validTags`, which the REST API leaves null. */
    suspend fun nodeTags(id: String): List<String> =
        listNodes().firstOrNull { it["id"]?.jsonPrimitive?.content == id }
            ?.get("tags")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    // ── Policy (ACL) ─────────────────────────────────────────────────────────

    suspend fun getPolicy(): String =
        req(HttpMethod.Get, "/policy")["policy"]?.jsonPrimitive?.content.orEmpty()

    suspend fun setPolicy(hujson: String) {
        req(HttpMethod.Put, "/policy", buildJsonObject { put("policy", hujson) })
    }

    /**
     * Renders the full deny-by-default ACL for the given tenants. Each tenant may reach its own
     * devices and the shared LLM box; the LLM box never initiates; cross-tenant is implicitly denied.
     */
    fun renderPolicy(ownerIds: List<Int>): String {
        val policy = buildJsonObject {
            putJsonObject("tagOwners") {
                ownerIds.forEach { put("tag:tenant-$it", buildJsonArray { add("${tenantUserName(it)}@") }) }
                put(llmTag, buildJsonArray { add("llm@") })
            }
            putJsonArray("acls") {
                ownerIds.forEach { id ->
                    add(aclRule("tag:tenant-$id", "tag:tenant-$id"))   // intra-tenant
                    add(aclRule("tag:tenant-$id", llmTag))             // tenant -> LLM
                }
            }
        }
        return policy.toString()
    }

    private fun aclRule(src: String, dst: String) = buildJsonObject {
        put("action", "accept")
        putJsonArray("src") { add(src) }
        putJsonArray("dst") { add("$dst:*") }
    }

    // ── High-level onboarding ─────────────────────────────────────────────────

    /**
     * Ensures the tenant's user exists AND that the live ACL includes its tag, by rendering the
     * policy across all current tenant users + this one. Call before minting the tenant's first invite.
     */
    suspend fun ensureTenant(ownerId: Int): HsUser {
        val user = ensureTenantUser(ownerId)
        val ownerIds = (listUsers().mapNotNull { it.name.removePrefix("tenant-").toIntOrNull() } + ownerId)
            .distinct().sorted()
        setPolicy(renderPolicy(ownerIds))
        return user
    }

    /**
     * Mints a device-onboarding invite for a tenant: a short-lived pre-auth key tagged to the
     * tenant, plus the deep link the manager scans/taps to join silently.
     *
     * @param edgeApiUrl the tenant's edge-box backend base URL the app should talk to after joining.
     */
    suspend fun createDeviceInvite(
        ownerId: Int,
        deviceLabel: String,
        edgeApiUrl: String,
        ttl: Duration = config.inviteTtl,
    ): DeviceInvite {
        val user = ensureTenantUser(ownerId)
        val pak = createPreAuthKey(user.id, listOf(tenantTag(ownerId)), reusable = false, ttl = ttl)
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val link = buildString {
            append(config.deepLinkScheme); append("://join")
            append("?server=").append(enc(config.controlUrl))
            append("&key=").append(enc(pak.key))
            append("&api=").append(enc(edgeApiUrl))
        }
        return DeviceInvite(ownerId, deviceLabel, pak.id, pak.key, link, pak.expiration)
    }
}
