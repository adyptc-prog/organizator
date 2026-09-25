package com.example.management_app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Telephony
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ascultă SMS-urile primite și stochează în coadă pe cele cu prefix ORG:
 * (mesaje de sincronizare trimise de celălalt dispozitiv Organizator).
 * Fiecare tabel poate avea propriul partener de sincronizare, deci fiecare
 * mesaj e etichetat cu tabelul al cărui partener configurat corespunde
 * expeditorului.
 *
 * Flutter citește coada la pornire și la revenire în foreground via
 * MethodChannel "organizator/sms" → getSyncMessages / clearSyncQueue.
 */
class SmsSyncReceiver : BroadcastReceiver() {

    companion object {
        const val SYNC_PREFIX = "ORG:"
        const val PREFS_NAME  = "SyncQueue"
        const val QUEUE_KEY   = "queue"

        // Licența se împarte între cele două telefoane sincronizate: „L” poartă
        // fișierul de licență semnat, „R” e cererea unui telefon fără licență
        // (trimisă când își configurează partenerul după ce celălalt a făcut-o
        // deja — altfel licența trimisă atunci ar fi fost ignorată).
        const val LICENSE_PREFIX         = "ORG:L:"
        const val LICENSE_REQUEST_PREFIX = "ORG:R:"

        // Protejează scrierile concurente în coadă — atât acest receiver, cât și
        // ClientBookingReceiver (rezervări de la clienți) pot scrie simultan.
        val QUEUE_LOCK = Any()

        // Scrie un mesaj în coada de sincronizare — folosit de orice cod nativ
        // care trebuie să adauge/actualizeze/șteargă o înregistrare (bot de
        // rezervări, expirare automată de validare etc.), fără să dubleze
        // logica de acces la SharedPreferences în fiecare loc.
        fun enqueue(context: Context, boardId: String, msg: String) {
            synchronized(QUEUE_LOCK) {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val existing = prefs.getString(QUEUE_KEY, "[]") ?: "[]"
                val arr = JSONArray(existing)
                val entry = JSONObject()
                entry.put("board", boardId)
                entry.put("msg", msg)
                arr.put(entry)
                prefs.edit().putString(QUEUE_KEY, arr.toString()).apply()
            }
        }
    }

    // Păstrăm doar cifrele, ca să comparăm numere indiferent de format
    // (+40712345678, 0712345678, cu spații etc.)
    private fun digitsOnly(raw: String?): String = raw.orEmpty().filter { it.isDigit() }

    private fun matches(sender: String, partnerDigits: String): Boolean {
        if (partnerDigits.isEmpty()) return false
        val senderDigits = digitsOnly(sender)
        val minLen = minOf(senderDigits.length, partnerDigits.length)
        if (minLen < 7) return false
        return senderDigits.takeLast(minLen) == partnerDigits.takeLast(minLen)
    }

    // Găsește tabelul al cărui partener configurat corespunde expeditorului.
    // Întoarce null dacă niciun tabel nu așteaptă mesaje de la acest număr —
    // altfel oricine ne știe numărul ar putea injecta/modifica/șterge
    // înregistrări trimițând un SMS "ORG:...".
    private fun matchingBoardId(flutterPrefs: SharedPreferences, sender: String): String? {
        val boardsJson = flutterPrefs.getString("flutter.management_boards", null)
        if (boardsJson == null) {
            // Migrarea Dart nu a rulat încă — un singur tabel implicit.
            val partnerDigits = digitsOnly(flutterPrefs.getString("flutter.sync_partner_phone", null))
            return if (matches(sender, partnerDigits)) "" else null
        }
        val boards = JSONArray(boardsJson)
        for (i in 0 until boards.length()) {
            val id = boards.getJSONObject(i).optString("id", "")
            if (id.isEmpty()) continue
            val partnerDigits = digitsOnly(flutterPrefs.getString("flutter.sync_partner_phone_$id", null))
            if (matches(sender, partnerDigits)) return id
        }
        return null
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val pdus = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            ?: return

        val flutterPrefs = context.getSharedPreferences(
            "FlutterSharedPreferences", Context.MODE_PRIVATE
        )

        // Grupăm PDU-urile pe expeditor și concatenăm corpul
        // (SMS multipart: toate segmentele sosesc în același broadcast)
        val bySender = mutableMapOf<String, StringBuilder>()
        for (sms in pdus) {
            val sender = sms.originatingAddress ?: continue
            val body   = sms.messageBody        ?: continue
            bySender.getOrPut(sender) { StringBuilder() }.append(body)
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        for ((sender, sb) in bySender) {
            val body = sb.toString()
            if (!body.startsWith(SYNC_PREFIX)) continue

            val boardId = matchingBoardId(flutterPrefs, sender) ?: continue

            if (body.startsWith(LICENSE_PREFIX)) {
                try {
                    val decision = LicenseStore.adoptFromPartner(
                        context, body.removePrefix(LICENSE_PREFIX)
                    )
                    Log.i("OrgDiag", "license from partner: $decision")
                } catch (e: Exception) {
                    Log.e("OrgDiag", "license from partner FAILED", e)
                }
                continue
            }
            if (body.startsWith(LICENSE_REQUEST_PREFIX)) {
                LicenseStore.shareableLicense(context)?.let {
                    sendSms(context, sender, LICENSE_PREFIX + it)
                }
                continue
            }

            try {
                synchronized(QUEUE_LOCK) {
                    val existing = prefs.getString(QUEUE_KEY, "[]") ?: "[]"
                    val arr = JSONArray(existing)
                    val entry = JSONObject()
                    entry.put("board", boardId)
                    entry.put("msg", body)
                    arr.put(entry)
                    prefs.edit().putString(QUEUE_KEY, arr.toString()).apply()
                }
            } catch (_: Exception) {
                // JSON corupt — ignorat
            }
        }
    }

    private fun sendSms(context: Context, phone: String, message: String) {
        SmsSender.send(context, phone, message)
    }
}
