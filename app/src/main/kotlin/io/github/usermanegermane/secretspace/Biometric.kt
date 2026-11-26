package io.github.usermanegermane.secretspace

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * Unlock again with a fingerprint, face or the phone's PIN -- the phone's
 * Windows Hello (the PC's hello.py), with the owner's rules: off until
 * turned on, per account; the master password once each time the app
 * starts; nothing written to disk that it could open.
 *
 * How: a key pair in the Android Keystore whose private half only works
 * after the user proves themselves to the phone. After an unlock with the
 * master password, the password is sealed to the public half and kept in
 * this process's memory only; a fingerprint later opens it again. When
 * the app's process ends the sealed copy is gone, so the next start asks
 * for the master password. Enrolling a new fingerprint makes the key
 * useless (Android invalidates it), and the password is asked for again.
 */
object Biometric {
    private const val PREFS = "unlock"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORM = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
    private val OAEP = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)

    /** What may prove the user: a strong fingerprint or face, and from Android 11 the PIN too. */
    val pinAllowed: Boolean = Build.VERSION.SDK_INT >= 30
    val authenticators: Int = if (pinAllowed) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_STRONG

    /** A master password sealed to this process's key: (wrapped AES key, IV, ciphertext). */
    private class Sealed(val wrapped: ByteArray, val iv: ByteArray, val body: ByteArray)
    private val held = mutableMapOf<String, Sealed>()

    private fun alias(username: String) = "secretspace-unlock-$username"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Null when it can be used, else why not, in words. */
    fun whyNot(context: Context): String? = when (BiometricManager.from(context).canAuthenticate(authenticators)) {
        BiometricManager.BIOMETRIC_SUCCESS -> null
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
            if (Build.VERSION.SDK_INT >= 30) "Set up a screen lock (PIN) or a fingerprint in Android's settings first."
            else "Set up a fingerprint in Android's settings first."
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE, BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
            "This phone has no fingerprint or face sensor that apps can use."
        else -> "This phone cannot do it now."
    }

    fun isOn(context: Context, username: String) = prefs(context).getBoolean(username, false)

    /** Ready to open ``username`` without the password: on, and the password held since the last unlock. */
    fun canOpen(context: Context, username: String) = isOn(context, username) && username in held

    private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun makeKey(username: String) {
        val spec = KeyGenParameterSpec.Builder(alias(username), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= 30) setUserAuthenticationParameters(0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            }
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE).apply { initialize(spec) }.generateKeyPair()
    }

    /** Seal ``password`` to the account's key: the public half needs no proof. */
    private fun seal(username: String, password: String): Sealed {
        val certificate = keyStore().getCertificate(alias(username)) ?: throw IllegalStateException("no key")
        val rsa = Cipher.getInstance(TRANSFORM)
        // The public key goes through a plain copy: the Keystore's own copy may refuse OAEP's SHA-256 digest.
        val public = java.security.KeyFactory.getInstance(certificate.publicKey.algorithm)
            .generatePublic(java.security.spec.X509EncodedKeySpec(certificate.publicKey.encoded))
        rsa.init(Cipher.ENCRYPT_MODE, public, OAEP)
        val aes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val gcm = Cipher.getInstance("AES/GCM/NoPadding")
        gcm.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, iv))
        val body = gcm.doFinal(password.toByteArray(Charsets.UTF_8))
        val wrapped = rsa.doFinal(aes)
        aes.fill(0)
        return Sealed(wrapped, iv, body)
    }

    /** After an unlock with the master password: keep it sealed, if this account has it on. */
    fun remember(context: Context, username: String, password: String) {
        if (!isOn(context, username)) return
        try {
            held[username] = seal(username, password)
        } catch (e: Exception) {
            turnOff(context, username)          // the key is gone (a new fingerprint, a reset lock screen)
        }
    }

    fun forget(username: String) {
        held.remove(username)
    }

    fun turnOff(context: Context, username: String) {
        held.remove(username)
        prefs(context).edit().remove(username).apply()
        try {
            keyStore().deleteEntry(alias(username))
        } catch (e: Exception) {
            // nothing there
        }
    }

    private fun decryptCipher(username: String): Cipher {
        val key = keyStore().getKey(alias(username), null) as? PrivateKey ?: throw IllegalStateException("no key")
        return Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key, OAEP) }
    }

    private fun open(cipher: Cipher, sealed: Sealed): String {
        val aes = cipher.doFinal(sealed.wrapped)
        try {
            val gcm = Cipher.getInstance("AES/GCM/NoPadding")
            gcm.init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, sealed.iv))
            return gcm.doFinal(sealed.body).toString(Charsets.UTF_8)
        } finally {
            aes.fill(0)
        }
    }

    private fun prompt(activity: FragmentActivity, title: String, subtitle: String, cipher: Cipher,
                       done: (Cipher) -> Unit, failed: (String?) -> Unit) {
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(subtitle)
            .setAllowedAuthenticators(authenticators)
            .apply { if (authenticators and DEVICE_CREDENTIAL == 0) setNegativeButtonText("Use the master password") }
            .setConfirmationRequired(false)
            .build()
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val c = result.cryptoObject?.cipher
                if (c == null) failed("The phone did not unlock the key.") else done(c)
            }

            override fun onAuthenticationError(code: Int, text: CharSequence) {
                val cancelled = code == BiometricPrompt.ERROR_USER_CANCELED || code == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    code == BiometricPrompt.ERROR_CANCELED
                failed(if (cancelled) null else text.toString())
            }
        }
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
            .authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    /**
     * Turn it on for an open account, the master password just checked:
     * make the key, prove once that this phone opens it, keep the password.
     * ``failed(null)`` = cancelled.
     */
    fun turnOn(activity: FragmentActivity, username: String, password: String, done: () -> Unit, failed: (String?) -> Unit) {
        try {
            makeKey(username)
            val sealed = seal(username, password)
            prompt(activity, "Unlock with fingerprint", "Confirm it is you, to turn this on for “$username”",
                decryptCipher(username), { cipher ->
                    try {
                        if (open(cipher, sealed) != password) throw IllegalStateException()
                        prefs(activity).edit().putBoolean(username, true).apply()
                        held[username] = sealed
                        done()
                    } catch (e: Exception) {
                        turnOff(activity, username)
                        failed("The phone's key did not work.")
                    }
                }, { why -> turnOff(activity, username); failed(why) })
        } catch (e: Exception) {
            turnOff(activity, username)
            failed("The phone's key store refused: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** The master password back, after a fingerprint; ``failed(null)`` = cancelled. */
    fun unlock(activity: FragmentActivity, username: String, done: (String) -> Unit, failed: (String?) -> Unit) {
        val sealed = held[username] ?: return failed("Enter the master password.")
        val cipher = try {
            decryptCipher(username)
        } catch (e: KeyPermanentlyInvalidatedException) {
            turnOff(activity, username)
            return failed("A fingerprint or the screen lock changed on this phone, so unlocking with it was turned " +
                "off. Enter the master password, then turn it on again in Settings.")
        } catch (e: Exception) {
            turnOff(activity, username)
            return failed("Unlocking with fingerprint is not available any more. Enter the master password.")
        }
        prompt(activity, "Unlock SecretSpace", username, cipher, { c ->
            try {
                done(open(c, sealed))
            } catch (e: Exception) {
                forget(username)
                failed("Enter the master password.")
            }
        }, failed)
    }
}
