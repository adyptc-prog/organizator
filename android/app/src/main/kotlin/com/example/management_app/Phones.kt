package com.example.management_app

/** Compararea numerelor de telefon indiferent de format (+40…, 07…, spații). */
object Phones {
    fun digitsOnly(raw: String?): String = raw.orEmpty().filter { it.isDigit() }

    /** Același număr dacă ultimele cifre (minim 7) coincid. */
    fun sameNumber(a: String?, b: String?): Boolean {
        val da = digitsOnly(a)
        val db = digitsOnly(b)
        val minLen = minOf(da.length, db.length)
        if (minLen < 7) return false
        return da.takeLast(minLen) == db.takeLast(minLen)
    }
}
