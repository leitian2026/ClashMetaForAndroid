package com.github.kr328.clash.custom

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// CUSTOM (CUSTOM.md #32): the encryption of the cloud backup. The file is
//
//   "CMFAENC1" (8 bytes) | iterations (4) | salt (16) | iv (12) | AES-256-GCM ciphertext + tag
//
// The key is PBKDF2-HMAC-SHA256 of the backup password (written out here because the platform's
// PBKDF2WithHmacSHA256 only exists from Android 8 and the app supports Android 5). The header up to
// the salt is authenticated as additional data. A wrong password (or a damaged file) fails the tag check.
object BackupCrypto {
    class WrongPasswordException : Exception("wrong password or damaged file")

    private val MAGIC = "CMFAENC1".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 60_000
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
    private const val HEADER = 8 + 4 + SALT_SIZE // the part that is also authenticated

    fun encrypt(plain: ByteArray, password: String): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_SIZE).also { random.nextBytes(it) }
        val iv = ByteArray(IV_SIZE).also { random.nextBytes(it) }

        val header = ByteBuffer.allocate(HEADER)
            .put(MAGIC).putInt(ITERATIONS).put(salt)
            .array()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(deriveKey(password, salt, ITERATIONS), "AES"),
            GCMParameterSpec(128, iv),
        )
        cipher.updateAAD(header)

        return header + iv + cipher.doFinal(plain)
    }

    /** Throws [WrongPasswordException] for a wrong password / damaged file, [IllegalArgumentException] for a file that is no backup. */
    fun decrypt(data: ByteArray, password: String): ByteArray {
        require(data.size > HEADER + IV_SIZE + 16 && data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            "not an encrypted backup"
        }

        val buffer = ByteBuffer.wrap(data)
        buffer.position(MAGIC.size)

        val iterations = buffer.int

        require(iterations in 1..2_000_000) { "bad backup header" }

        val salt = ByteArray(SALT_SIZE).also { buffer.get(it) }
        val iv = ByteArray(IV_SIZE).also { buffer.get(it) }

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")

            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(deriveKey(password, salt, iterations), "AES"),
                GCMParameterSpec(128, iv),
            )
            cipher.updateAAD(data, 0, HEADER)

            cipher.doFinal(data, HEADER + IV_SIZE, data.size - HEADER - IV_SIZE)
        } catch (e: GeneralSecurityException) {
            throw WrongPasswordException()
        }
    }

    // PBKDF2 (RFC 8018) with HMAC-SHA256, one block = 32 bytes = the AES-256 key
    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")

        mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA256"))

        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))

        var u = mac.doFinal()
        val result = u.copyOf()

        for (i in 2..iterations) {
            u = mac.doFinal(u)

            for (j in result.indices) {
                result[j] = (result[j].toInt() xor u[j].toInt()).toByte()
            }
        }

        return result
    }
}
