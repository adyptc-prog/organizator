package com.example.management_app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ServiceInfo(val name: String, val durationMin: Int)

/**
 * Serviciile unui tabel (categorie), fiecare cu durata lui — scrise de
 * ecranul „Servicii” din Flutter (services.dart, cheia „services_<tabel>”)
 * și citite de botul SMS. Aceleași limite ca în Dart.
 */
object ServicesStore {
    const val SYNC_PREFIX = "ORG:V:"
    const val MAX_SERVICES = 15
    const val MIN_DURATION = 5
    const val MAX_DURATION = 12 * 60

    private const val PREFS_NAME = "FlutterSharedPreferences"

    fun key(boardId: String) = "flutter.services_$boardId"

    private fun isValidDuration(m: Int) = m in MIN_DURATION..MAX_DURATION

    fun decode(arr: JSONArray?): List<ServiceInfo> {
        if (arr == null) return emptyList()
        val result = mutableListOf<ServiceInfo>()
        for (i in 0 until arr.length()) {
            if (result.size >= MAX_SERVICES) break
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("n", "").trim()
            val m = o.opt("m") as? Number ?: continue
            if (name.isEmpty() || m.toDouble() != m.toInt().toDouble() || !isValidDuration(m.toInt())) continue
            result.add(ServiceInfo(name, m.toInt()))
        }
        return result
    }

    fun load(context: Context, boardId: String): List<ServiceInfo> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key(boardId), null) ?: return emptyList()
        return try { decode(JSONArray(raw)) } catch (_: Exception) { emptyList() }
    }

    /**
     * Lista primită de la partener („ORG:V:{"d":30,"s":[...]}”) — scrisă
     * imediat, ca botul s-o folosească chiar dacă aplicația e închisă. Flutter
     * o aplică și el din coadă (aceleași valori). Fals dacă mesajul e invalid.
     */
    fun applySync(context: Context, boardId: String, payload: String): Boolean {
        val j = try { JSONObject(payload) } catch (_: Exception) { return false }
        val d = j.opt("d") as? Number ?: return false
        if (!isValidDuration(d.toInt())) return false
        val services = decode(j.optJSONArray("s"))
        val arr = JSONArray()
        services.forEach { arr.put(JSONObject().put("n", it.name).put("m", it.durationMin)) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(key(boardId), arr.toString())
            // Plugin-ul Flutter salvează valorile int ca Long.
            .putLong("flutter.appointment_duration_$boardId", d.toLong())
            .commit()
        return true
    }
}
