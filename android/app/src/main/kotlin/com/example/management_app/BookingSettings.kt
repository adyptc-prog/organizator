package com.example.management_app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class BoardInfo(val id: String, val name: String)

data class BoardBookingSettings(
    val enabled: Boolean,
    val durationMin: Int,
    val workStartMin: Int,
    val workEndMin: Int,
    val closedDays: Set<Int>, // 1=luni .. 7=duminică, la fel ca DateTime.weekday din Dart
)

data class BusyInterval(val startMin: LocalDateTime, val endMin: LocalDateTime)

/**
 * Citește tabelele, setările de rezervare și programările existente direct din
 * SharedPreferences-ul Flutter — folosit atât de MainActivity (afișarea „Spatiere”
 * pe Android) cât și de ClientBookingReceiver (botul SMS), ca să existe o singură
 * sursă de adevăr pentru calculul locurilor libere pe această platformă.
 */
object BookingSettings {
    private const val PREFS_NAME = "FlutterSharedPreferences"

    private val ISO_SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadBoards(context: Context): List<BoardInfo> {
        val json = prefs(context).getString("flutter.management_boards", null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val id = o.optString("id", "")
                if (id.isEmpty()) null else BoardInfo(id, o.optString("name", ""))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun loadSettings(context: Context, boardId: String): BoardBookingSettings {
        val p = prefs(context)
        val closedStr = p.getString("flutter.closed_days_$boardId", "") ?: ""
        val closed = closedStr.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
        return BoardBookingSettings(
            enabled = p.getBoolean("flutter.booking_enabled_$boardId", false),
            durationMin = p.getInt("flutter.appointment_duration_$boardId", 30),
            workStartMin = p.getInt("flutter.work_start_$boardId", 9 * 60),
            workEndMin = p.getInt("flutter.work_end_$boardId", 18 * 60),
            closedDays = closed,
        )
    }

    private fun parseFlexibleIso(raw: String): LocalDateTime? = try {
        LocalDateTime.parse(raw)
    } catch (_: Exception) {
        try {
            LocalDateTime.parse(raw, ISO_SHORT)
        } catch (_: Exception) {
            null
        }
    }

    // Intervalul ocupat al unei programări = [expiresAt - durată, expiresAt) —
    // aceeași convenție ca funcția „Spatiere” din Dart (durata e o setare unică
    // per tabel, nu per înregistrare).
    fun loadBusyIntervals(context: Context, boardId: String, durationMin: Int): List<BusyInterval> {
        val p = prefs(context)
        val result = mutableListOf<BusyInterval>()

        val itemsJson = p.getString("flutter.management_items_$boardId", null)
        if (itemsJson != null) {
            try {
                val arr = JSONArray(itemsJson)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.isNull("expiresAt")) continue
                    val expiresStr = o.optString("expiresAt", "")
                    if (expiresStr.isEmpty()) continue
                    val end = parseFlexibleIso(expiresStr) ?: continue
                    result.add(BusyInterval(end.minusMinutes(durationMin.toLong()), end))
                }
            } catch (_: Exception) {
            }
        }

        // Rezervări deja confirmate de clienți, dar încă neprocesate de aplicație
        // (stau în coada de sincronizare) — trebuie tratate ca ocupate, altfel doi
        // clienți ar putea primi aceeași oră liberă înainte ca aplicația să fie
        // deschisă și coada procesată.
        val queueJson = context
            .getSharedPreferences(SmsSyncReceiver.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(SmsSyncReceiver.QUEUE_KEY, "[]") ?: "[]"
        try {
            val arr = JSONArray(queueJson)
            for (i in 0 until arr.length()) {
                val entry = arr.getJSONObject(i)
                if (entry.optString("board", "") != boardId) continue
                val msg = entry.optString("msg", "")
                if (!msg.startsWith("ORG:A:") && !msg.startsWith("ORG:I:") && !msg.startsWith("ORG:U:")) continue
                val j = JSONObject(msg.substring(6))
                val expiresStr = j.optString("e", "")
                if (expiresStr.isEmpty()) continue
                val end = parseFlexibleIso(expiresStr) ?: continue
                result.add(BusyInterval(end.minusMinutes(durationMin.toLong()), end))
            }
        } catch (_: Exception) {
        }

        return result
    }
}
