package controlplane

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent friendly-name store: maps a device's pre-auth key id → the label the admin typed on
 * the Add-phone page. A node's JSON carries `preAuthKey.id`, so we can attach the label to whatever
 * node later joins with that key — without touching the Headscale hostname (which must stay
 * DNS-safe for MagicDNS). Backed by a small JSON file so it survives control-plane restarts.
 */
class DeviceLabelStore(private val file: File) {
    private val map = ConcurrentHashMap<String, String>()
    private val json = Json { prettyPrint = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    init {
        runCatching {
            if (file.exists()) json.decodeFromString(serializer, file.readText(Charsets.UTF_8))
                .forEach { (k, v) -> map[k] = v }
        }.onFailure { System.err.println("[control-plane] device-labels load failed: ${it.message}") }
    }

    fun get(preAuthKeyId: String?): String? = preAuthKeyId?.let { map[it] }

    fun put(preAuthKeyId: String, label: String) {
        if (label.isBlank()) return
        map[preAuthKeyId] = label
        persist()
    }

    /** Clears a name (reverts the phone to showing its tailnet hostname). */
    fun remove(preAuthKeyId: String) {
        if (map.remove(preAuthKeyId) != null) persist()
    }

    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(serializer, map.toSortedMap()), Charsets.UTF_8)
        }.onFailure { System.err.println("[control-plane] device-labels save failed: ${it.message}") }
    }
}
