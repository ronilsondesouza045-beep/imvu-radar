package br.com.imvuradar.app.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Indice persistente: quem (cid) esta em qual sala.
 * O app le o roster das salas que acompanha e guarda o mapa.
 * Assim "onde esta" responde na hora e mostra TODAS as salas
 * de quem esta em varias ao mesmo tempo (multi-cliente no PC).
 */
class RoomIndex(ctx: Context) {

    private val prefs: SharedPreferences =
        ctx.getSharedPreferences("imvu_radar_index", Context.MODE_PRIVATE)

    /** cid -> Set de roomId */
    private val index = HashMap<String, MutableSet<String>>()

    /** roomId -> lista de cids (ultimo roster visto) */
    private val roomMembers = HashMap<String, List<String>>()

    init {
        load()
    }

    private fun load() {
        val raw = prefs.getString("map", null) ?: return
        try {
            val obj = JSONObject(raw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val cid = keys.next()
                val arr = obj.optJSONArray(cid) ?: JSONArray()
                val set = mutableSetOf<String>()
                for (i in 0 until arr.length()) set.add(arr.getString(i))
                index[cid] = set
            }
        } catch (_: Exception) {
        }
    }

    private fun save() {
        try {
            val obj = JSONObject()
            for ((cid, rooms) in index) {
                obj.put(cid, JSONArray(rooms.toList()))
            }
            prefs.edit().putString("map", obj.toString()).apply()
        } catch (_: Exception) {
        }
    }

    /** atualiza o mapa com o roster lido de uma sala */
    fun updateRoom(rid: String, members: List<String>) {
        roomMembers[rid] = members
        for (cid in index.keys.toList()) {
            if (cid !in members) index[cid]?.remove(rid)
        }
        for (cid in members) {
            index.getOrPut(cid) { mutableSetOf() }.add(rid)
        }
        save()
    }

    /** salas onde o cid esta (pelo indice) */
    fun roomsOf(cid: String): List<String> = index[cid]?.toList() ?: emptyList()

    /** todas as salas conhecidas */
    fun knownRooms(): List<String> = roomMembers.keys.toList()

    /** pessoas conhecidas (para varredura em 1 salto) */
    fun knownCids(): List<String> = index.keys.toList()

    fun memberCount(rid: String): Int = roomMembers[rid]?.size ?: 0

    fun clear() {
        index.clear(); roomMembers.clear(); save()
    }
}
