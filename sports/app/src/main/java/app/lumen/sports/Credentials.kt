package app.lumen.sports

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * ESPN league ID and login cookies (espn_s2, SWID), encrypted with an AES-GCM key that lives in
 * the Android Keystore and can't be exported. Stored in no-backup storage; never logged.
 * Written only by [AuthReceiver] (reachable from `adb shell` alone).
 */
class Credentials(context: Context) {
    data class Value(val leagueId: String, val espnS2: String, val swid: String)

    private val file = File(context.noBackupFilesDir, FILE)

    fun save(value: Value) {
        val plain = JSONObject().put("league", value.leagueId).put("s2", value.espnS2).put("swid", value.swid).toString()
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        writeAtomically(file, Base64.encodeToString(sealed, Base64.NO_WRAP))
    }

    /** Null if never set, or unreadable (e.g. the key was lost); either way the ticker asks for auth. */
    fun load(): Value? = try {
        val sealed = Base64.decode(file.readText(), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
        }
        val o = JSONObject(String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8))
        Value(o.getString("league"), o.getString("s2"), o.getString("swid"))
    } catch (_: Exception) {
        null
    }

    fun clear() {
        file.delete()
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    companion object {
        private const val FILE = "espn.bin"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "espn"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
    }
}
