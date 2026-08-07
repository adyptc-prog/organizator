package com.example.management_app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Bot de rezervări prin SMS pentru clienți.
 *
 *   „liber” / „liber <tabel>”  → răspunde cu până la 6 ore libere, numerotate
 *   „next”                     → următoarea pagină de ore libere
 *   un număr (ex. „2”)         → confirmă rezervarea opțiunii respective
 *
 * Rulează independent de Flutter (ca SmsAlarmReceiver) — citește direct din
 * SharedPreferences prin BookingSettings/FreeSlotCalculator, ca să răspundă
 * instant chiar dacă aplicația e complet închisă. Rezervarea confirmată e
 * scrisă în coada de sincronizare existentă ca mesaj „ORG:A:” — Flutter o
 * preia automat la următoarea deschidere, cu logica de sincronizare deja
 * existentă (numerotare, alarme etc.), fără cod separat pentru asta.
 *
 * Declanșatorul „liber” e strict (mesajul trebuie să fie DOAR „liber” sau
 * „liber <un cuvânt>”) ca să nu se confunde cu un SMS personal obișnuit care
 * conține întâmplător acel cuvânt undeva în text.
 */
class ClientBookingReceiver : BroadcastReceiver() {

    companion object {
        private const val PREFS_NAME      = "ClientBookingPrefs"
        private const val OFFERS_KEY      = "offers"
        private const val OFFER_TTL_MIN   = 20L
        private const val PAGE_SIZE       = 6
        private const val HORIZON_DAYS    = 14
        private const val MAX_TOTAL_SLOTS = 200

        // Toate SMS-urile de la clienți se procesează strict în ordinea sosirii
        // (FIFO) pe un singur fir de execuție — evită curse între două cereri
        // aproape simultane (ofertă suprascrisă, rezervare pierdută).
        private val EXECUTOR: ExecutorService = Executors.newSingleThreadExecutor()

        private val ISO_SHORT: DateTimeFormatter        = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
        private val DISPLAY_TIME_FMT: DateTimeFormatter  = DateTimeFormatter.ofPattern("HH:mm")
        private val DISPLAY_DATE_FMT: DateTimeFormatter  = DateTimeFormatter.ofPattern("dd.MM")

        private val LIBER_RE   = Regex("^liber(\\s+(\\S+))?$")
        private val NUMBER_RE  = Regex("^(\\d{1,2})$")
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i("OrgDiag", "onReceive action=${intent.action}")
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val pdus = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return

        val bySender = mutableMapOf<String, StringBuilder>()
        for (sms in pdus) {
            val sender = sms.originatingAddress ?: continue
            val body   = sms.messageBody        ?: continue
            bySender.getOrPut(sender) { StringBuilder() }.append(body)
        }
        Log.i("OrgDiag", "onReceive senders=${bySender.keys}")
        if (bySender.isEmpty()) return

        val pending = goAsync()
        val appContext = context.applicationContext
        EXECUTOR.execute {
            try {
                for ((sender, sb) in bySender) {
                    try {
                        handleMessage(appContext, sender, sb.toString())
                    } catch (e: Exception) {
                        Log.e("OrgDiag", "handleMessage threw for sender=$sender", e)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun digitsOnly(raw: String?): String = raw.orEmpty().filter { it.isDigit() }

    private fun normalize(s: String): String {
        val nfd = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        return nfd.replace(Regex("\\p{Mn}+"), "").lowercase().trim()
    }

    private fun handleMessage(context: Context, sender: String, rawBody: String) {
        Log.i("OrgDiag", "handleMessage sender=$sender rawBody=\"$rawBody\"")
        if (rawBody.startsWith("ORG:")) {
            Log.i("OrgDiag", "handleMessage: ignored, looks like sync message (ORG:)")
            return
        }

        val senderDigits = digitsOnly(sender)
        if (senderDigits.isEmpty()) {
            Log.w("OrgDiag", "handleMessage: senderDigits empty, aborting")
            return
        }
        if (isSyncPartner(context, senderDigits)) {
            Log.i("OrgDiag", "handleMessage: sender=$senderDigits matched sync_partner_phone, ignoring")
            return
        }

        val body = normalize(rawBody)
        Log.i("OrgDiag", "handleMessage: normalized body=\"$body\"")
        if (body.isEmpty()) return

        val liberMatch  = LIBER_RE.find(body)
        val numberMatch = NUMBER_RE.find(body)
        Log.i("OrgDiag", "handleMessage: liberMatch=${liberMatch != null} numberMatch=${numberMatch != null}")

        when {
            liberMatch != null -> {
                val token = liberMatch.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
                startOffer(context, sender, senderDigits, token)
            }
            body == "next" -> continueOffer(context, sender, senderDigits)
            numberMatch != null -> confirmOffer(context, sender, senderDigits, numberMatch.groupValues[1].toInt())
            else -> Log.i("OrgDiag", "handleMessage: no pattern matched, ignoring silently (by design)")
            // orice alt text e ignorat complet — reduce riscul de fals-pozitive
        }
    }

    // ── Excludere parteneri de sincronizare (device-to-device) ──────────────────
    private fun isSyncPartner(context: Context, senderDigits: String): Boolean {
        val prefs = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        val boards = BookingSettings.loadBoards(context)
        val keys = if (boards.isEmpty()) {
            listOf("flutter.sync_partner_phone")
        } else {
            boards.map { "flutter.sync_partner_phone_${it.id}" }
        }
        for (key in keys) {
            val partnerDigits = digitsOnly(prefs.getString(key, null))
            if (partnerDigits.isEmpty()) continue
            val minLen = minOf(senderDigits.length, partnerDigits.length)
            if (minLen < 7) continue
            if (senderDigits.takeLast(minLen) == partnerDigits.takeLast(minLen)) {
                Log.i("OrgDiag", "isSyncPartner: MATCH key=$key partnerDigits=$partnerDigits senderDigits=$senderDigits")
                return true
            }
        }
        return false
    }

    // ── Potrivire tabel după nume ────────────────────────────────────────────────
    private fun matchBoard(boards: List<BoardInfo>, token: String?): BoardInfo? {
        if (token == null) return boards.firstOrNull()
        val t = normalize(token)
        boards.firstOrNull { normalize(it.name) == t }?.let { return it }
        boards.firstOrNull { normalize(it.name).contains(t) || t.contains(normalize(it.name)) }?.let { return it }
        return null
    }

    // ── Stocare ofertă activă per client ─────────────────────────────────────────
    private fun bookingPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun loadOffers(context: Context): JSONObject = try {
        JSONObject(bookingPrefs(context).getString(OFFERS_KEY, "{}") ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }

    private fun saveOffers(context: Context, offers: JSONObject) {
        bookingPrefs(context).edit().putString(OFFERS_KEY, offers.toString()).apply()
    }

    private fun getOffer(context: Context, senderDigits: String): JSONObject? {
        val o = loadOffers(context).optJSONObject(senderDigits) ?: return null
        val ts = o.optLong("ts", 0L)
        if (System.currentTimeMillis() - ts > OFFER_TTL_MIN * 60_000L) return null
        return o
    }

    private fun setOffer(context: Context, senderDigits: String, boardId: String, offset: Int) {
        val offers = loadOffers(context)
        val o = JSONObject()
        o.put("board", boardId)
        o.put("offset", offset)
        o.put("ts", System.currentTimeMillis())
        offers.put(senderDigits, o)
        saveOffers(context, offers)
    }

    private fun clearOffer(context: Context, senderDigits: String) {
        val offers = loadOffers(context)
        offers.remove(senderDigits)
        saveOffers(context, offers)
    }

    // ── Flux „liber” / „liber <tabel>” ──────────────────────────────────────────
    private fun startOffer(context: Context, sender: String, senderDigits: String, token: String?) {
        val boards = BookingSettings.loadBoards(context)
        Log.i("OrgDiag", "startOffer: boards=${boards.map { it.id + "/" + it.name }} token=$token")
        if (boards.isEmpty()) {
            Log.w("OrgDiag", "startOffer: no boards found, aborting")
            return
        }

        val board = matchBoard(boards, token)
        if (board == null) {
            val names = boards.joinToString(", ") { it.name }
            sendSms(context, sender, "Nu am găsit tabelul \"$token\". Tabele disponibile: $names.")
            return
        }

        val settings = BookingSettings.loadSettings(context, board.id)
        Log.i("OrgDiag", "startOffer: board=${board.id} settings.enabled=${settings.enabled}")
        if (!settings.enabled) {
            sendSms(context, sender, "Rezervările prin SMS nu sunt active pentru ${board.name}.")
            return
        }

        sendPage(context, sender, senderDigits, board, settings, offset = 0, isFirstPage = true)
    }

    private fun continueOffer(context: Context, sender: String, senderDigits: String) {
        val offer = getOffer(context, senderDigits)
        if (offer == null) {
            sendSms(context, sender, "Nu am nicio căutare activă. Scrie LIBER pentru a vedea orele libere.")
            return
        }
        val boardId = offer.optString("board", "")
        val board = BookingSettings.loadBoards(context).firstOrNull { it.id == boardId }
        if (board == null) {
            clearOffer(context, senderDigits)
            return
        }
        val settings = BookingSettings.loadSettings(context, board.id)
        if (!settings.enabled) {
            clearOffer(context, senderDigits)
            return
        }
        val nextOffset = offer.optInt("offset", 0) + PAGE_SIZE
        sendPage(context, sender, senderDigits, board, settings, offset = nextOffset, isFirstPage = false)
    }

    private fun sendPage(
        context: Context,
        sender: String,
        senderDigits: String,
        board: BoardInfo,
        settings: BoardBookingSettings,
        offset: Int,
        isFirstPage: Boolean,
    ) {
        val page = computePage(context, board, settings, offset)

        if (page.slots.isEmpty()) {
            val msg = if (isFirstPage) {
                "Nu sunt ore libere în perioada verificată pentru ${board.name}."
            } else {
                "Nu mai sunt alte ore libere pentru ${board.name}. Scrie LIBER pentru a relua căutarea."
            }
            sendSms(context, sender, msg)
            clearOffer(context, senderDigits)
            return
        }

        setOffer(context, senderDigits, board.id, offset)

        val today = LocalDateTime.now().toLocalDate()
        val lines = page.slots.mapIndexed { i, slot ->
            val dateSuffix = if (slot.start.toLocalDate() != today) " (${slot.start.format(DISPLAY_DATE_FMT)})" else ""
            "${i + 1}. ${slot.start.format(DISPLAY_TIME_FMT)}-${slot.end.format(DISPLAY_TIME_FMT)}$dateSuffix"
        }
        val footer = "Răspunde cu numărul opțiunii pentru rezervare" + (if (page.hasMore) ", sau NEXT pentru alte ore." else ".")
        sendSms(context, sender, "Ore libere ${board.name}:\n${lines.joinToString("\n")}\n$footer")
    }

    private class Page(val slots: List<FreeSlot>, val hasMore: Boolean)

    // Cerem un slot „în plus” față de pagina curentă (peekTarget), doar ca să
    // detectăm dacă mai există ore libere după cele afișate — fără asta,
    // FreeSlotCalculator s-ar opri exact la finalul paginii și nu am putea ști
    // niciodată dacă „NEXT” chiar mai aduce ceva.
    private fun computePage(context: Context, board: BoardInfo, settings: BoardBookingSettings, offset: Int): Page {
        val busy = BookingSettings.loadBusyIntervals(context, board.id, settings.durationMin)
        val peekTarget = minOf(offset + PAGE_SIZE + 1, MAX_TOTAL_SLOTS)
        val all = FreeSlotCalculator.compute(
            busy, settings, LocalDateTime.now(), HORIZON_DAYS,
            maxResults = peekTarget,
        )
        val slots = if (offset < all.size) all.subList(offset, minOf(offset + PAGE_SIZE, all.size)) else emptyList()
        val hasMore = all.size > offset + slots.size
        return Page(slots, hasMore)
    }

    // ── Confirmare opțiune ───────────────────────────────────────────────────────
    private fun confirmOffer(context: Context, sender: String, senderDigits: String, choice: Int) {
        val offer = getOffer(context, senderDigits)
        if (offer == null) {
            sendSms(context, sender, "Nu am nicio ofertă activă pentru tine. Scrie LIBER pentru a vedea orele libere.")
            return
        }
        val boardId = offer.optString("board", "")
        val offset  = offer.optInt("offset", 0)
        val board = BookingSettings.loadBoards(context).firstOrNull { it.id == boardId }
        if (board == null) {
            clearOffer(context, senderDigits)
            return
        }
        val settings = BookingSettings.loadSettings(context, board.id)

        // Recalculăm oferta curentă din nou (nu memorăm sloturile în sine), ca să
        // reflectăm orice schimbare de la ultimul mesaj — inclusiv o eventuală
        // rezervare făcută între timp de alt client, pe același interval.
        val page = computePage(context, board, settings, offset)

        if (choice < 1 || choice > page.slots.size) {
            sendSms(
                context, sender,
                "Opțiune invalidă sau expirată. Răspunde cu un număr din ultimul mesaj primit, " +
                    "sau scrie LIBER pentru o căutare nouă."
            )
            return
        }

        val slot = page.slots[choice - 1]
        clearOffer(context, senderDigits)
        enqueueBooking(context, board.id, sender, slot.start, slot.end)

        val dateSuffix = if (slot.start.toLocalDate() != LocalDateTime.now().toLocalDate())
            " (${slot.start.format(DISPLAY_DATE_FMT)})" else ""
        sendSms(
            context, sender,
            "Programarea ta la ${board.name} pe ${slot.start.format(DISPLAY_TIME_FMT)}-" +
                "${slot.end.format(DISPLAY_TIME_FMT)}$dateSuffix a fost înregistrată. Te așteptăm!"
        )
    }

    private fun generateSyncId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val r = SecureRandom()
        return (1..16).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    // ── Scrie programarea confirmată în coada de sincronizare existentă ─────────
    // Reutilizează exact protocolul de sincronizare între tabele (mesaj „ORG:A:”)
    // — Flutter va prelua această „programare” la fel ca pe oricare alta primită
    // de la un dispozitiv pereche, cu logica deja existentă de merge/numerotare.
    private fun enqueueBooking(context: Context, boardId: String, sender: String, start: LocalDateTime, end: LocalDateTime) {
        val item = JSONObject()
        item.put("s", generateSyncId())
        item.put("n", sender)
        item.put("c", start.format(ISO_SHORT))
        item.put("e", end.format(ISO_SHORT))
        item.put("p1", sender)

        val msg = "ORG:A:$item"

        synchronized(SmsSyncReceiver.QUEUE_LOCK) {
            val prefs = context.getSharedPreferences(SmsSyncReceiver.PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getString(SmsSyncReceiver.QUEUE_KEY, "[]") ?: "[]"
            val arr = JSONArray(existing)
            val entry = JSONObject()
            entry.put("board", boardId)
            entry.put("msg", msg)
            arr.put(entry)
            prefs.edit().putString(SmsSyncReceiver.QUEUE_KEY, arr.toString()).apply()
        }
    }

    private fun sendSms(context: Context, phone: String, message: String) {
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = smsManager?.divideMessage(message)
            if (parts != null && parts.size > 1) {
                smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
            } else {
                smsManager?.sendTextMessage(phone, null, message, null, null)
            }
            Log.i("OrgDiag", "sendSms OK to=$phone")
        } catch (e: Exception) {
            Log.e("OrgDiag", "sendSms FAILED to=$phone", e)
        }
    }
}
