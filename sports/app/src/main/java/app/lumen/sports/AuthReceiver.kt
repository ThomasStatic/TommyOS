package app.lumen.sports

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives the ESPN league ID and cookies from `scripts/sports.sh auth`. The manifest guards it with
 * android.permission.DUMP, which `adb shell` holds and ordinary apps can't, so no app on the TV can
 * plant or overwrite credentials. Values are never logged.
 *
 *   am broadcast -n app.lumen.sports/.AuthReceiver -a app.lumen.sports.AUTH \
 *       --es league <id> --es espn_s2 <cookie> --es swid <{...}>
 *   am broadcast -n app.lumen.sports/.AuthReceiver -a app.lumen.sports.SIGN_OUT
 */
class AuthReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val creds = Credentials(context)
        when (intent.action) {
            ACTION_AUTH -> {
                val league = intent.getStringExtra("league")?.trim().orEmpty()
                val s2 = intent.getStringExtra("espn_s2")?.trim().orEmpty()
                val swid = intent.getStringExtra("swid")?.trim().orEmpty()
                if (!league.all(Char::isDigit) || league.isEmpty() || s2.isEmpty() || !SWID.matches(swid)) {
                    Log.w(TAG, "auth rejected: league, espn_s2 or swid missing or malformed")
                    resultCode = RESULT_REJECTED
                    return
                }
                creds.save(Credentials.Value(league, s2, swid.uppercase()))
                Log.i(TAG, "auth saved for league $league")
            }
            ACTION_SIGN_OUT -> {
                creds.clear()
                Log.i(TAG, "signed out")
            }
            else -> return
        }
        resultCode = RESULT_SAVED
        // A running ticker picks the change up immediately; this never starts one.
        TickerService.refreshIfRunning(context)
    }

    companion object {
        private const val TAG = "LumenSports"
        const val ACTION_AUTH = "app.lumen.sports.AUTH"
        const val ACTION_SIGN_OUT = "app.lumen.sports.SIGN_OUT"
        private const val RESULT_SAVED = 1
        private const val RESULT_REJECTED = 2
        private val SWID = Regex("""\{[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}\}""")
    }
}
