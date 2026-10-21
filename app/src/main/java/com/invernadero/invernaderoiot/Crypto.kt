package com.invernadero.invernaderoiot

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object Crypto {
    private val rnd = SecureRandom()

    private fun pbkdf2(secret: CharArray, salt: ByteArray, iter: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(secret, salt, iter, 256)).encoded

    fun newSalt(): ByteArray = ByteArray(16).also { rnd.nextBytes(it) }

    fun hashPassword(pw: String, salt: ByteArray): ByteArray = pbkdf2(pw.toCharArray(), salt, 120_000)

    fun verify(pw: String, salt: ByteArray, expected: ByteArray): Boolean =
        MessageDigest.isEqual(hashPassword(pw, salt), expected)

    fun channelKey(pin: String): SecretKey =
        SecretKeySpec(pbkdf2(pin.toCharArray(), "invernadero-iot-v1".toByteArray(), 50_000), "AES")

    fun seal(key: SecretKey, plain: String): String {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return Base64.encodeToString(iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    fun open(key: SecretKey, data: String): String? = try {
        val raw = Base64.decode(data, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw, 0, 12))
        String(c.doFinal(raw, 12, raw.size - 12))
    } catch (e: Exception) { null }
}