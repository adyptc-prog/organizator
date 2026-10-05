package com.example.management_app

import android.content.Context
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Dreptul de folosire al funcțiilor care trimit SMS-uri pe cheltuiala
 * aplicației (botul de rezervări, reminderele programate): licență activă
 * sau trial de 30 de zile încă activ.
 *
 * Verificat nativ, la momentul trimiterii — funcționează și cu aplicația
 * închisă, și după repornirea telefonului. Timpul folosit e cel „efectiv”
 * al licenței (LicenseStore.effectiveNow): ceasul dat înapoi nu prelungește
 * trial-ul.
 */
object Entitlement {

    const val TRIAL_DAYS = 30
    private const val DAY_MS = 24L * 60L * 60L * 1000L
    // Scris de Dart (LicenseService) la prima pornire, ca dată locală ISO-8601.
    private const val TRIAL_START_KEY = "flutter.trial_start_date"

    /** Data din Dart (DateTime.toIso8601String(), ora locală) în epoch ms. */
    fun parseDartLocal(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try {
            LocalDateTime.parse(iso).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    // Fără dată de start aplicația n-a fost încă deschisă — trial-ul n-a început.
    fun isTrialActive(trialStartMs: Long?, nowMs: Long): Boolean =
        trialStartMs == null || nowMs < trialStartMs + TRIAL_DAYS * DAY_MS

    fun trialDaysLeft(trialStartMs: Long?, nowMs: Long): Int {
        if (trialStartMs == null) return TRIAL_DAYS
        val left = (trialStartMs + TRIAL_DAYS * DAY_MS - nowMs) / DAY_MS
        return left.coerceAtLeast(0L).toInt()
    }

    fun trialStartMs(context: Context): Long? = parseDartLocal(
        context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            .getString(TRIAL_START_KEY, null)
    )

    fun isActive(context: Context): Boolean =
        LicenseStore.check(context).isActive ||
            isTrialActive(trialStartMs(context), LicenseStore.effectiveNow(context))
}
