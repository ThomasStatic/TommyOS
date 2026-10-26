package app.lumen.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Lumen Sports asks the Home button service to report app changes while its ticker runs in auto mode,
 * and to stop afterwards. Only apps signed with Lumen's key can send this (the manifest requires the
 * Sports control permission of the sender). Runs in the ":redirect" process, next to the service.
 */
class SportsFollowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        HomeRedirectService.setFollowSports(context, intent.getBooleanExtra("follow", false))
    }
}
