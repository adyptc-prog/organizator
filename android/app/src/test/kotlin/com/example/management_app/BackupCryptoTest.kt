package com.example.management_app

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class BackupCryptoTest {

    private val plain = BackupFormat.encode(
        mapOf("flutter.management_items_b1" to """[{"name":"Ana Popescu","phoneNumber":"0712345678"}]"""),
        BackupFormat.Identity("organizator-1", null),
        createdAt = 1000L,
    )
    // Puține iterații în teste — formatul și corectitudinea nu depind de număr.
    private fun enc(pw: String = "parola-sigura") = BackupCrypto.encrypt(plain, pw, 1000L, iterations = 1000)

    @Test
    fun `PBKDF2 propriu da acelasi rezultat ca implementarea JDK`() {
        val salt = "sare-de-test".toByteArray()
        val ours = BackupCrypto.pbkdf2Sha256("parola", salt, 1000, 32)
        val jdk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec("parola".toCharArray(), salt, 1000, 256)).encoded
        assertArrayEquals(jdk, ours)
    }

    @Test
    fun `vectorul de test din RFC 7914`() {
        val dk = BackupCrypto.pbkdf2Sha256("passwd", "salt".toByteArray(), 1, 64)
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            dk.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun `criptat si decriptat cu aceeasi parola da backup-ul original`() {
        assertEquals(plain, BackupCrypto.decrypt(enc(), "parola-sigura"))
    }

    @Test
    fun `fisierul criptat nu contine datele clientilor in clar`() {
        val content = enc()
        assertFalse(content.contains("Ana Popescu"))
        assertFalse(content.contains("0712345678"))
        assertTrue(BackupCrypto.isEncrypted(content))
        assertFalse(BackupCrypto.isEncrypted(plain))
    }

    @Test
    fun `parola gresita e respinsa`() {
        assertThrows(BackupCrypto.WrongPasswordException::class.java) {
            BackupCrypto.decrypt(enc(), "alta-parola")
        }
    }

    @Test
    fun `fisierul modificat e respins`() {
        val root = JSONObject(enc())
        val payload = root.getString("payload")
        val flipped = (if (payload[10] == 'A') 'B' else 'A')
        root.put("payload", payload.substring(0, 10) + flipped + payload.substring(11))
        assertThrows(BackupCrypto.WrongPasswordException::class.java) {
            BackupCrypto.decrypt(root.toString(), "parola-sigura")
        }
    }

    @Test
    fun `fiecare backup are sare si IV proprii`() {
        val a = JSONObject(enc())
        val b = JSONObject(enc())
        assertTrue(a.getJSONObject("kdf").getString("salt") != b.getJSONObject("kdf").getString("salt"))
        assertTrue(a.getString("payload") != b.getString("payload"))
    }

    @Test
    fun `parola prea scurta nu e acceptata`() {
        assertFalse(BackupCrypto.isValidPassword("1234567"))
        assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.encrypt(plain, "scurta", 0L)
        }
    }

    @Test
    fun `fisierul care nu e backup e respins clar`() {
        assertThrows(BackupFormat.InvalidBackupException::class.java) {
            BackupCrypto.decrypt("""{"app":"altceva","formatVersion":2}""", "parola-sigura")
        }
    }
}
