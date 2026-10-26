package app.lumen.sports

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * ESPN league ID and login cookies (espn_s2, SWID), sealed in a [SealedFile]. Stored in no-backup
 * storage; never logged. Written only by [AuthProvider] (reachable from `adb shell` alone).
 */
class Credentials(context: Context) {
    data class Value(val leagueId: String, val espnS2: String, val swid: String)

    private val sealed = SealedFile(File(context.noBackupFilesDir, "espn.bin"), "espn")

    fun save(value: Value) =
        sealed.write(JSONObject().put("league", value.leagueId).put("s2", value.espnS2).put("swid", value.swid).toString().toByteArray(Charsets.UTF_8))

    /** Whether credentials have been saved (without decrypting them). */
    fun exists() = sealed.exists()

    /** Null if never set, or unreadable (e.g. the key was lost); either way the ticker asks for auth. */
    fun load(): Value? = try {
        val o = JSONObject(String(sealed.read() ?: return null, Charsets.UTF_8))
        Value(o.getString("league"), o.getString("s2"), o.getString("swid"))
    } catch (_: Exception) {
        null
    }

    fun clear() = sealed.clear()
}

/**
 * The phone controller's pairing secret: 256 random bits, sealed in a [SealedFile]. Shown only as the
 * pairing QR code (Lumen, `scripts/sports.sh phone pair`); never logged or sent by the TV.
 */
class PhoneSecret(context: Context) {
    private val sealed = SealedFile(File(context.noBackupFilesDir, "phone.bin"), "phone")

    fun exists() = sealed.exists()

    fun load(): ByteArray? = try { sealed.read() } catch (_: Exception) { null }

    /** A new secret, replacing the old one: every phone paired with the old one is refused from now on. */
    fun create(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it); sealed.write(it) }

    fun clear() = sealed.clear()
}

/**
 * A small file encrypted with an AES-GCM key that lives in the Android Keystore under [alias] and
 * can't be exported. Stored as base64(iv + ciphertext), written atomically.
 */
class SealedFile(private val file: File, private val alias: String) {
    fun exists() = file.isFile

    fun write(plain: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(plain)
        writeAtomically(file, Base64.encodeToString(sealed, Base64.NO_WRAP))
    }

    /** Null if never written; throws if it can't be decrypted (e.g. the key was lost). */
    fun read(): ByteArray? {
        if (!file.isFile) return null
        val sealed = Base64.decode(file.readText(), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
        }
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    fun clear() {
        file.delete()
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(alias)
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
