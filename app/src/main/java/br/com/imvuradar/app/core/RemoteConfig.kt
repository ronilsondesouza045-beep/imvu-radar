package br.com.imvuradar.app.core

import org.json.JSONObject

/** Config remota do site: nomes de sala por ID (a API nao expoe sala privada de terceiro). */
object RemoteConfig {
    @Volatile private var rooms: Map<String, String> = emptyMap()

    fun update(json: JSONObject): Boolean {
        val r = json.optJSONObject("rooms") ?: return false
        val m = HashMap<String, String>()
        val keys = r.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = r.optString(k, "")
            if (v.isNotBlank()) m[k] = v
        }
        val changed = m != rooms
        rooms = m
        return changed
    }

    fun nameFor(rid: String): String = rooms[rid] ?: ""
}
