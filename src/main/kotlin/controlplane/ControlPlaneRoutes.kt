package controlplane

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.respondHtml
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.html.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Platform control-plane routes: onboard tenants and mint device invites against Headscale.
 *
 * This is platform-admin territory (it wields the Headscale admin key via [TailnetService]) and is
 * intended to run as its own small service on the Headscale box — NOT bundled into a per-tenant edge
 * backend. Guarded by an optional shared [token]: when set, every request must carry it as
 * `Authorization: Bearer <token>` or `?token=<token>`; when null the service is open (bind localhost).
 *
 * @param edgeApiTemplate template for a tenant's edge backend URL, `{ownerId}` substituted, used as the
 *        invite's `api=` when the caller doesn't supply one (e.g. `https://edge-{ownerId}.ts.warpfactor.dk`).
 */
class ControlPlaneRoutes(
    private val tailnet: TailnetService,
    private val token: String?,
    private val edgeApiTemplate: String = "https://edge-{ownerId}.ts.warpfactor.dk",
) {
    @Serializable data class CreateTenantReq(val ownerId: Int)
    @Serializable data class CreateInviteReq(val ownerId: Int, val deviceLabel: String, val edgeApiUrl: String? = null)
    @Serializable data class TenantDto(val ownerId: Int, val userId: String, val userName: String)
    @Serializable data class InviteDto(val ownerId: Int, val deviceLabel: String, val deepLink: String, val expiration: String?, val preAuthKey: String)
    @Serializable data class NodeDto(val id: String, val name: String, val user: String, val tags: List<String>, val addresses: List<String>)
    @Serializable data class Tenants(val tenants: List<TenantDto>)
    @Serializable data class Nodes(val nodes: List<NodeDto>)
    @Serializable data class ErrorDto(val error: String)

    private fun edgeApiFor(ownerId: Int) = edgeApiTemplate.replace("{ownerId}", ownerId.toString())

    private suspend fun listTenants(): List<TenantDto> =
        tailnet.listUsers().mapNotNull { u ->
            u.name.removePrefix("tenant-").toIntOrNull()?.let { TenantDto(it, u.id, u.name) }
        }.sortedBy { it.ownerId }

    private suspend fun listNodeDtos(): List<NodeDto> = tailnet.listNodes().map { n ->
        NodeDto(
            id = n["id"]?.jsonPrimitive?.content ?: "?",
            name = (n["givenName"] ?: n["name"])?.jsonPrimitive?.content ?: "?",
            user = n["user"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "?",
            tags = (n["forcedTags"] ?: n["validTags"])?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            addresses = n["ipAddresses"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
        )
    }

    private fun generateQrPng(content: String, size: Int = 300): ByteArray {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until size) for (y in 0 until size) img.setRGB(x, y, if (matrix[x, y]) 0x000000 else 0xFFFFFF)
        val baos = ByteArrayOutputStream(); ImageIO.write(img, "png", baos); return baos.toByteArray()
    }

    fun install(r: Route) = r.route("") {
        // ── auth guard ──
        intercept(ApplicationCallPipeline.Plugins) {
            val t = token ?: return@intercept
            val bearer = call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
            val q = call.request.queryParameters["token"]
            if (bearer != t && q != t) {
                call.respond(HttpStatusCode.Unauthorized, "control plane: missing/invalid token")
                finish()
            }
        }

        get("/") { call.respondPage() }

        get("/qr.png") {
            val data = call.request.queryParameters["data"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "missing data")
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respondBytes(generateQrPng(data), ContentType.Image.PNG)
        }

        get("/api/tenants") { call.respond(Tenants(listTenants())) }
        get("/api/nodes") { call.respond(Nodes(listNodeDtos())) }

        post("/api/tenants") {
            val req = call.receive<CreateTenantReq>()
            val u = tailnet.ensureTenant(req.ownerId)
            call.respond(TenantDto(req.ownerId, u.id, u.name))
        }

        post("/api/invites") {
            val req = call.receive<CreateInviteReq>()
            val inv = tailnet.createDeviceInvite(
                ownerId = req.ownerId,
                deviceLabel = req.deviceLabel,
                edgeApiUrl = req.edgeApiUrl?.takeIf { it.isNotBlank() } ?: edgeApiFor(req.ownerId),
            )
            call.respond(InviteDto(inv.ownerId, inv.deviceLabel, inv.deepLink, inv.expiration, inv.preAuthKey))
        }
    }

    // ── Admin page (thin client over the JSON API) ─────────────────────────────
    private suspend fun ApplicationCall.respondPage() {
        val tok = request.queryParameters["token"] ?: token ?: ""
        respondHtml {
            head {
                meta { charset = "utf-8" }
                meta { name = "viewport"; content = "width=device-width, initial-scale=1" }
                title { +"Headscale Control Plane" }
                style { unsafe { raw(CSS) } }
            }
            body {
                h1 { +"🕸 Headscale Control Plane" }
                p("sub") { +"Onboard tenants and mint device invites for the self-hosted tailnet." }

                div("grid") {
                    div("card") {
                        h2 { +"➕ Onboard tenant" }
                        p("hint") { +"Creates the Headscale user tenant-{ownerId} and updates the deny-by-default ACL." }
                        input { id = "t_owner"; type = InputType.number; placeholder = "owner id (e.g. 1)" }
                        button { attributes["onclick"] = "createTenant()"; +"Create / ensure tenant" }
                        div("out") { id = "t_out" }
                    }
                    div("card") {
                        h2 { +"📲 Device invite" }
                        input { id = "i_owner"; type = InputType.number; placeholder = "owner id" }
                        input { id = "i_label"; type = InputType.text; placeholder = "device label (e.g. galaxy-a70)" }
                        input { id = "i_api"; type = InputType.text; placeholder = "edge api url (blank = default)" }
                        button { attributes["onclick"] = "createInvite()"; +"Generate invite" }
                        div("out") { id = "i_out" }
                    }
                }

                h2 { +"Tenants" }; div { id = "tenants" }
                h2 { +"Nodes" }; div { id = "nodes" }

                script { unsafe { raw(js(tok)) } }
            }
        }
    }

    companion object {
        private val CSS = """
            body{font-family:system-ui,Segoe UI,Roboto,sans-serif;max-width:960px;margin:24px auto;padding:0 16px;color:#1a2330;background:#f5f7fa}
            h1{margin:0 0 4px} .sub{color:#667;margin:0 0 20px}
            .grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}
            .card{background:#fff;border:1px solid #e2e8f0;border-radius:10px;padding:16px}
            .card h2{margin:0 0 8px;font-size:1.05rem} .hint{color:#788;font-size:.85rem;margin:.2rem 0 .6rem}
            input{width:100%;box-sizing:border-box;padding:8px;margin:4px 0;border:1px solid #cbd5e1;border-radius:6px}
            button{margin-top:8px;padding:8px 14px;background:#2563eb;color:#fff;border:0;border-radius:6px;cursor:pointer}
            .out{margin-top:10px;font-size:.9rem;word-break:break-all}
            .out code{background:#0f172a;color:#e2e8f0;padding:2px 6px;border-radius:4px;display:inline-block}
            .out img{margin-top:8px;border:1px solid #ddd;border-radius:8px;background:#fff}
            table{border-collapse:collapse;width:100%;background:#fff;border-radius:8px;overflow:hidden}
            th,td{border:1px solid #e2e8f0;padding:6px 10px;text-align:left;font-size:.88rem}
            th{background:#f1f5f9} .muted{color:#99a}
            @media(max-width:640px){.grid{grid-template-columns:1fr}}
        """.trimIndent()

        private fun js(token: String) = """
            const TOK = ${escapeJs(token)};
            const H = TOK ? {'Authorization':'Bearer '+TOK,'Content-Type':'application/json'} : {'Content-Type':'application/json'};
            async function jget(p){const r=await fetch(p,{headers:H});return r.json()}
            async function jpost(p,b){const r=await fetch(p,{method:'POST',headers:H,body:JSON.stringify(b)});return {ok:r.ok,body:await r.json()}}
            function q(s){return encodeURIComponent(s)}
            async function createTenant(){
              const id=parseInt(document.getElementById('t_owner').value);
              const o=document.getElementById('t_out');
              if(!id){o.textContent='enter an owner id';return}
              const r=await jpost('/api/tenants',{ownerId:id});
              o.innerHTML = r.ok ? ('✅ user <code>'+r.body.userName+'</code> (id '+r.body.userId+')') : ('❌ '+JSON.stringify(r.body));
              load();
            }
            async function createInvite(){
              const id=parseInt(document.getElementById('i_owner').value);
              const label=document.getElementById('i_label').value||'device';
              const api=document.getElementById('i_api').value;
              const o=document.getElementById('i_out');
              if(!id){o.textContent='enter an owner id';return}
              const r=await jpost('/api/invites',{ownerId:id,deviceLabel:label,edgeApiUrl:api});
              if(!r.ok){o.innerHTML='❌ '+JSON.stringify(r.body);return}
              const link=r.body.deepLink;
              o.innerHTML='<div>Deep link (expires '+(r.body.expiration||'?')+'):</div><code>'+link+'</code>'+
                '<div><img width=220 src="/qr.png?data='+q(link)+(TOK?('&token='+q(TOK)):'')+'"></div>';
              load();
            }
            async function load(){
              const t=await jget('/api/tenants');
              document.getElementById('tenants').innerHTML = t.tenants.length ?
                '<table><tr><th>owner</th><th>headscale user</th><th>id</th></tr>'+
                t.tenants.map(x=>'<tr><td>'+x.ownerId+'</td><td>'+x.userName+'</td><td>'+x.userId+'</td></tr>').join('')+'</table>'
                : '<p class=muted>no tenants yet</p>';
              const n=await jget('/api/nodes');
              document.getElementById('nodes').innerHTML = n.nodes.length ?
                '<table><tr><th>name</th><th>user</th><th>tags</th><th>addresses</th></tr>'+
                n.nodes.map(x=>'<tr><td>'+x.name+'</td><td>'+x.user+'</td><td>'+x.tags.join(', ')+'</td><td>'+x.addresses.join(', ')+'</td></tr>').join('')+'</table>'
                : '<p class=muted>no nodes joined</p>';
            }
            load();
        """.trimIndent()

        private fun escapeJs(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
