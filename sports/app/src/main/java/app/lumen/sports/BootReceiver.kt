package app.lumen.sports

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restarts the phone listener after a reboot or an app update, if phone control is switched on, so
 * the phone works as soon as the TV does. Never starts the ticker: that stays off until asked.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!PhoneService.isEnabled(context)) return
        Log.i("LumenSports", "${intent.action}: starting phone control")
        context.startForegroundService(Intent(context, PhoneService::class.java))
    }
}
