package controlplane

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

/**
 * Standalone platform control-plane service.
 *
 * Runs on the Headscale box (co-located), holds the Headscale admin API key, and exposes the
 * tenant/device onboarding endpoints + admin UI ([ControlPlaneRoutes]). It is deliberately separate
 * from the per-tenant edge backends (which must never hold the admin key).
 *
 * Env:
 *   HEADSCALE_API_KEY      (required — feature off / exits if unset)
 *   HEADSCALE_API_URL      Headscale REST base (default https://headscale.warpfactor.dk)
 *   HEADSCALE_CONTROL_URL  control-server URL clients embed (default = API url)
 *   HEADSCALE_MAGICDNS_BASE MagicDNS base (default ts.warpfactor.dk)
 *   CONTROL_PLANE_TOKEN    shared secret; when set, required on every request
 *   CONTROL_PLANE_PORT     listen port (default 8090)
 *   CONTROL_PLANE_HOST     bind host (default 127.0.0.1)
 *   EDGE_API_TEMPLATE      per-tenant edge backend URL template, {ownerId} substituted
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

    val tailnet = TailnetService(cfg)
    println("[control-plane] Headscale API=${cfg.apiUrl} control=${cfg.controlUrl} magicdns=${cfg.magicDnsBase} " +
            "auth=${if (token != null) "token" else "OPEN"} listening http://$host:$port")

    embeddedServer(Netty, port = port, host = host) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        routing { ControlPlaneRoutes(tailnet, token, edgeTemplate).install(this) }
    }.start(wait = true)
}
