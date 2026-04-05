/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.connect

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Manages cryptographic operations for Metrolist Connect.
 *
 * Key derivation: HMAC-SHA256(accountIdentifier, "metrolist-connect-v1")
 * Handshake: X25519 ECDH (via BouncyCastle) with HKDF-derived session keys
 * Transport: AES-256-GCM encryption for all post-handshake messages
 *
 * BouncyCastle is used instead of JCA X25519 because several Android vendors
 * (notably Xiaomi/Redmi) ship with incomplete JCA providers that omit X25519,
 * even on API 31+. BouncyCastle is self-contained and uniformly available.
 */
class ConnectKeyManager {

    companion object {
        private const val ACCOUNT_KEY_CONTEXT = "metrolist-connect-v1"
        private const val HKDF_INFO = "metrolist-connect-session-v1"
        private const val AES_GCM_TAG_LENGTH = 128
        private const val AES_GCM_NONCE_LENGTH = 12
        private const val KEY_FINGERPRINT_LENGTH = 8
    }

    private val secureRandom = SecureRandom()

    // --- Account key derivation ---

    /**
     * Derives a deterministic account key from a stable account identifier.
     * Same account on different devices will produce the same key.
     */
    fun deriveAccountKey(accountIdentifier: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(accountIdentifier.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(ACCOUNT_KEY_CONTEXT.toByteArray(Charsets.UTF_8))
    }

    /**
     * Returns a short fingerprint of the account key for mDNS TXT records.
     * Used to filter discovered devices to the same account without exposing the key.
     */
    fun accountKeyFingerprint(accountKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(accountKey)
        return hash.take(KEY_FINGERPRINT_LENGTH).joinToString("") { "%02x".format(it) }
    }

    // --- X25519 ECDH key exchange (via BouncyCastle) ---

    /**
     * Represents an ephemeral X25519 keypair.
     */
    data class X25519KeyPair(
        val privateKey: X25519PrivateKeyParameters,
        val publicKey: X25519PublicKeyParameters,
    )

    /**
     * Generates an ephemeral X25519 keypair for the handshake.
     * Provides forward secrecy — even if the accountKey is compromised,
     * past sessions cannot be decrypted.
     *
     * Uses BouncyCastle for universal compatibility across all Android vendors.
     */
    fun generateEphemeralKeyPair(): X25519KeyPair {
        val generator = X25519KeyPairGenerator()
        generator.init(X25519KeyGenerationParameters(secureRandom))
        val keyPair = generator.generateKeyPair()
        return X25519KeyPair(
            privateKey = keyPair.private as X25519PrivateKeyParameters,
            publicKey = keyPair.public as X25519PublicKeyParameters,
        )
    }

    /**
     * Returns the raw 32-byte public key for transmission.
     */
    fun publicKeyBytes(keyPair: X25519KeyPair): ByteArray = keyPair.publicKey.encoded

    /**
     * Performs X25519 key agreement to produce a 32-byte shared secret.
     */
    fun performKeyExchange(myKeyPair: X25519KeyPair, peerPublicKeyBytes: ByteArray): ByteArray {
        val peerPublicKey = X25519PublicKeyParameters(peerPublicKeyBytes, 0)
        val agreement = X25519Agreement()
        agreement.init(myKeyPair.privateKey)
        val secret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(peerPublicKey, secret, 0)
        return secret
    }

    // --- Session key derivation via HKDF ---

    /**
     * Derives session encryption key from the ECDH shared secret + account key.
     * Uses HKDF-SHA256 (extract-then-expand).
     *
     * ikm = sharedSecret || accountKey
     * salt = controllerNonce || deviceNonce
     */
    fun deriveSessionKey(
        controllerNonce: ByteArray,
        deviceNonce: ByteArray,
        sharedSecret: ByteArray,
        accountKey: ByteArray,
    ): ByteArray {
        val ikm = sharedSecret + accountKey
        val salt = controllerNonce + deviceNonce

        // HKDF-Extract
        val prk = hmacSha256(salt, ikm)
        // HKDF-Expand (single iteration yields 32 bytes = AES-256 key)
        return hmacSha256(prk, HKDF_INFO.toByteArray(Charsets.UTF_8) + byteArrayOf(0x01))
    }

    // --- AES-256-GCM encryption ---

    /**
     * Encrypts plaintext with AES-256-GCM.
     * Returns nonce (12 bytes) || ciphertext+tag.
     */
    fun encrypt(plaintext: ByteArray, sessionKey: ByteArray): ByteArray {
        val nonce = ByteArray(AES_GCM_NONCE_LENGTH)
        secureRandom.nextBytes(nonce)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(sessionKey, "AES"),
            GCMParameterSpec(AES_GCM_TAG_LENGTH, nonce),
        )
        val ciphertext = cipher.doFinal(plaintext)
        return nonce + ciphertext
    }

    /**
     * Decrypts ciphertext produced by [encrypt].
     * Input format: nonce (12 bytes) || ciphertext+tag.
     * @throws javax.crypto.AEADBadTagException if key mismatch or corruption.
     */
    fun decrypt(encrypted: ByteArray, sessionKey: ByteArray): ByteArray {
        require(encrypted.size > AES_GCM_NONCE_LENGTH) { "Encrypted data too short" }

        val nonce = encrypted.copyOfRange(0, AES_GCM_NONCE_LENGTH)
        val ciphertext = encrypted.copyOfRange(AES_GCM_NONCE_LENGTH, encrypted.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(sessionKey, "AES"),
            GCMParameterSpec(AES_GCM_TAG_LENGTH, nonce),
        )
        return cipher.doFinal(ciphertext)
    }

    // --- Pairing proof ---

    /**
     * Creates identity proof: AEAD(sessionKey, accountKey || controllerNonce || deviceNonce || timestamp).
     * The receiver verifies that the accountKey matches its own.
     */
    fun createPairingProof(
        sessionKey: ByteArray,
        accountKey: ByteArray,
        controllerNonce: ByteArray,
        deviceNonce: ByteArray,
    ): ByteArray {
        val timestamp = System.currentTimeMillis()
        val timestampBytes = ByteArray(8)
        for (i in 0..7) {
            timestampBytes[i] = (timestamp shr (56 - i * 8) and 0xFF).toByte()
        }
        val plaintext = accountKey + controllerNonce + deviceNonce + timestampBytes
        return encrypt(plaintext, sessionKey)
    }

    /**
     * Verifies a pairing proof by decrypting and checking the accountKey.
     * Returns true if the proof is valid (same account). Silent reject otherwise.
     */
    fun verifyPairingProof(
        proof: ByteArray,
        sessionKey: ByteArray,
        expectedAccountKey: ByteArray,
        controllerNonce: ByteArray,
        deviceNonce: ByteArray,
    ): Boolean {
        return try {
            val plaintext = decrypt(proof, sessionKey)
            if (plaintext.size < expectedAccountKey.size + controllerNonce.size + deviceNonce.size + 8) {
                return false
            }
            val proofAccountKey = plaintext.copyOfRange(0, expectedAccountKey.size)
            val proofControllerNonce =
                plaintext.copyOfRange(expectedAccountKey.size, expectedAccountKey.size + controllerNonce.size)
            val proofDeviceNonce =
                plaintext.copyOfRange(
                    expectedAccountKey.size + controllerNonce.size,
                    expectedAccountKey.size + controllerNonce.size + deviceNonce.size,
                )

            proofAccountKey.contentEquals(expectedAccountKey) &&
                proofControllerNonce.contentEquals(controllerNonce) &&
                proofDeviceNonce.contentEquals(deviceNonce)
        } catch (_: Exception) {
            false
        }
    }

    // --- Nonce generation ---

    fun generateNonce(size: Int = 32): ByteArray {
        val nonce = ByteArray(size)
        secureRandom.nextBytes(nonce)
        return nonce
    }

    // --- Utility ---

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
