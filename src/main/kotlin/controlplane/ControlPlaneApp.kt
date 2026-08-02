package controlplane

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Standalone platform control-plane service.
 *
 * Runs on the Headscale box (co-located), holds the Headscale admin API key, and exposes the
 * tenant/device onboarding endpoints + admin UI ([ControlPlaneRoutes]) on a localhost admin
 * listener, plus a PUBLIC download listener ([DownloadRoutes]) that serves only one-time APK links.
 * Deliberately separate from the per-tenant edge backends (which must never hold the admin key).
 *
 * Env:
 *   HEADSCALE_API_KEY       (required — feature off / exits if unset)
 *   HEADSCALE_API_URL       Headscale REST base (default https://headscale.warpfactor.dk)
 *   HEADSCALE_CONTROL_URL   control-server URL clients embed (default = API url)
 *   HEADSCALE_MAGICDNS_BASE MagicDNS base (default ts.warpfactor.dk)
 *   CONTROL_PLANE_TOKEN     admin shared secret (full access)
 *   CONTROL_PLANE_PORT/HOST  admin listener (default 127.0.0.1:8090)
 *   EDGE_API_TEMPLATE       per-tenant edge backend URL template, {ownerId} substituted
 *   EDGE_TOKENS             per-tenant edge tokens: "1=<tokenA>,2=<tokenB>" (token scoped to that ownerId)
 *   DOWNLOAD_PORT/HOST      public download listener (default 0.0.0.0:8091)
 *   DOWNLOAD_PUBLIC_BASE    public base URL of the download listener, e.g. http://headscale.warpfactor.dk:8091
 *   APK_PATH / APK_FILENAME the APK to serve (default /opt/control-plane/apk/shopmanager.apk, ShopManager.apk)
 */
fun main() {
    val cfg = TailnetConfig.fromEnv() ?: run {
        System.err.println("HEADSCALE_API_KEY is not set — control plane cannot start.")
        return
    }
    val token = System.getenv("CONTROL_PLANE_TOKEN")?.takeIf { it.isNotBlank() }
    val port = System.getenv("CONTROL_PLANE_PORT")?.toIntOrNull() ?: 8090
    val host = System.getenv("CONTROL_PLANE_HOST")?.takeIf { it.isNotBlank() } ?: "127.0.0.1"
    val edgeTemplate = System.getenv("EDGE_API_TEMPLATE")?.takeIf { it.isNotBlank() }
        ?: "https://edge-{ownerId}.${cfg.magicDnsBase}"
    val edgeTokens = parseEdgeTokens(System.getenv("EDGE_TOKENS"))

    val downloadPort = System.getenv("DOWNLOAD_PORT")?.toIntOrNull() ?: 8091
    val downloadHost = System.getenv("DOWNLOAD_HOST")?.takeIf { it.isNotBlank() } ?: "0.0.0.0"
    val downloadPublicBase = System.getenv("DOWNLOAD_PUBLIC_BASE")?.takeIf { it.isNotBlank() } ?: ""
    val apkFile = File(System.getenv("APK_PATH")?.takeIf { it.isNotBlank() } ?: "/opt/control-plane/apk/shopmanager.apk")
    val apkFilename = System.getenv("APK_FILENAME")?.takeIf { it.isNotBlank() } ?: "ShopManager.apk"

    val tailnet = TailnetService(cfg)
    println("[control-plane] Headscale API=${cfg.apiUrl} magicdns=${cfg.magicDnsBase} " +
            "adminAuth=${if (token != null) "token" else "OPEN"} edgeTokens=${edgeTokens.size} " +
            "admin=http://$host:$port")

    // Public download listener — serves ONLY the one-time /dl/{token} (no admin API).
    if (downloadPublicBase.isNotBlank()) {
        embeddedServer(Netty, port = downloadPort, host = downloadHost) {
            routing { DownloadRoutes(apkFile, apkFilename).install(this) }
        }.start(wait = false)
        println("[control-plane] download listener http://$downloadHost:$downloadPort " +
                "(public=$downloadPublicBase apk=${apkFile.path} exists=${apkFile.exists()})")
    }

    embeddedServer(Netty, port = port, host = host) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        routing { ControlPlaneRoutes(tailnet, token, edgeTemplate, edgeTokens, downloadPublicBase).install(this) }
    }.start(wait = true)
}

/** Parses `EDGE_TOKENS` ("1=tokenA,2=tokenB") into token -> ownerId. */
private fun parseEdgeTokens(raw: String?): Map<String, Int> =
    raw?.split(",")?.mapNotNull { pair ->
        val p = pair.split("=", limit = 2)
        val owner = p.getOrNull(0)?.trim()?.toIntOrNull()
        val tok = p.getOrNull(1)?.trim()
        if (owner != null && !tok.isNullOrBlank()) tok to owner else null
    }?.toMap() ?: emptyMap()
