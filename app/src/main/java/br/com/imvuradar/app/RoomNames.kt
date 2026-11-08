package br.com.imvuradar.app

import android.content.Context

/** Apelidos de sala por ID (a API nao expoe nome de sala privada de terceiro). */
object RoomNames {
    private const val PREFS = "imvu_radar_room_names"

    fun get(ctx: Context, rid: String): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString(rid, "") ?: ""
    }

    fun set(ctx: Context, rid: String, name: String) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putString(rid, name.trim()).apply()
    }
}
