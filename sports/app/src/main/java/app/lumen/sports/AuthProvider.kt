package app.lumen.sports

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log

/**
 * Receives the ESPN league ID and cookies from `scripts/sports.sh auth`, as a provider call rather
 * than a broadcast: the system keeps recent broadcasts' extras in plain text in its broadcast history
 * (in `dumpsys activity broadcasts`, and so in bug reports), which would expose the cookie; provider
 * calls leave no such record. The manifest guards it with android.permission.DUMP, which `adb shell`
 * holds and ordinary apps can't, so no app on the TV can plant or overwrite credentials; [call]
 * checks again. Values are never logged. Signing out also forgets the saved scores.
 *
 *   content call --uri content://app.lumen.sports.auth --method auth \
 *       --extra league:s:<id> --extra espn_s2:s:<cookie> --extra swid:s:<{...}>
 *   content call --uri content://app.lumen.sports.auth --method sign-out
 */
class AuthProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = context!!
        if (context.checkCallingPermission(android.Manifest.permission.DUMP) != PackageManager.PERMISSION_GRANTED) throw SecurityException("adb shell only")
        val creds = Credentials(context)
        when (method) {
            METHOD_AUTH -> {
                val league = extras?.getString("league")?.trim().orEmpty()
                val s2 = extras?.getString("espn_s2")?.trim().orEmpty()
                val swid = extras?.getString("swid")?.trim().orEmpty()
                if (!league.all(Char::isDigit) || league.isEmpty() || s2.isEmpty() || !SWID.matches(swid)) {
                    Log.w(TAG, "auth rejected: league, espn_s2 or swid missing or malformed")
                    return result(RESULT_REJECTED)
                }
                creds.save(Credentials.Value(league, s2, swid.uppercase()))
                Sports.saveEspnState(context, EspnState.UNKNOWN)
                Log.i(TAG, "auth saved for league $league")
            }
            METHOD_SIGN_OUT -> {
                creds.clear()
                TickerService.cache(context).clear()
                Sports.saveEspnState(context, EspnState.SIGNED_OUT)
                Log.i(TAG, "signed out")
            }
            else -> return result(RESULT_REJECTED)
        }
        // A running ticker picks the change up immediately; this never starts one.
        TickerService.sendIfRunning(context, TickerService.ACTION_REFRESH)
        return result(RESULT_SAVED)
    }

    private fun result(code: Int) = Bundle().apply { putInt("result", code) }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        private const val TAG = "LumenSports"
        const val METHOD_AUTH = "auth"
        const val METHOD_SIGN_OUT = "sign-out"
        private const val RESULT_SAVED = 1
        private const val RESULT_REJECTED = 2
        private val SWID = Regex("""\{[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}\}""")
    }
}
