package app.lumen.sports

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log

/**
 * Switches and configures the ticker for Lumen (which holds [PERMISSION], a signature permission:
 * only apps signed with Lumen's key get it) and for `adb shell` (which holds android.permission.DUMP;
 * apps can't). Nobody else can reach it: the manifest lets only holders of either permission open the
 * provider, and [call] checks again, since Android doesn't check provider calls itself.
 *
 *   content call --uri content://app.lumen.sports.control --method status
 *   content call --uri content://app.lumen.sports.control --method show [--extra edge:s:top ...]
 *   content call --uri content://app.lumen.sports.control --method hide | refresh
 *   content call --uri content://app.lumen.sports.control --method set --extra height:i:60 --extra opacity:f:0.8
 *   content call --uri content://app.lumen.sports.control --method preview --arg highlight|stale|offline
 *   content call --uri content://app.lumen.sports.control --method phone --arg on|off
 *   content call --uri content://app.lumen.sports.control --method pair [--arg new]   (link and QR code)
 *   content call --uri content://app.lumen.sports.control --method unpair
 *   content call --uri content://app.lumen.sports.control --method foreground --arg <package/activity.Class>
 *
 * `pair` returns the pairing link (with the secret) and its QR code to Lumen or the shell only; it
 * is never logged. `pair new` replaces the secret, so every phone paired before is refused.
 * Settings keys: see [TickerConfig]. ESPN credentials don't come through here: [AuthProvider] takes
 * them from `adb shell` only.
 */
class ControlProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = context!!
        val shell = context.checkCallingPermission(android.Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED
        val lumen = context.checkCallingPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
        if (!shell && !lumen) throw SecurityException("Lumen or adb shell only")
        val changes = extras?.let { b -> b.keySet().associateWith { @Suppress("DEPRECATION") b.get(it) } }.orEmpty()
        when (method) {
            METHOD_STATUS -> return Sports.status(context, includeLeague = true).toBundle()
            METHOD_SHOW -> Sports.show(context, changes)
            METHOD_HIDE -> Sports.hide(context)
            METHOD_SET -> Sports.set(context, changes)
            METHOD_REFRESH -> Sports.refresh(context)
            METHOD_PREVIEW -> Sports.preview(context, arg.orEmpty())
            METHOD_PHONE -> when (arg) {
                "on" -> PhoneService.setEnabled(context, true)
                "off" -> PhoneService.setEnabled(context, false)
                else -> return Bundle().apply { putBoolean(OK, false) }
            }
            METHOD_PAIR -> return pair(context, renew = arg == "new").also { Log.i(TAG, "pairing code shown (${if (arg == "new") "new" else "current"}) for ${if (lumen) "Lumen" else "adb shell"}") }
            METHOD_FOREGROUND -> {
                // From Lumen's Home button service, on every app change while auto mode runs: not logged.
                val follow = Sports.foreground(arg?.takeIf { it.length <= 300 }.orEmpty())
                return Bundle().apply { putBoolean(OK, true); putBoolean("follow", follow) }
            }
            METHOD_UNPAIR -> {
                PhoneSecret(context).clear()
                PhoneService.secretChanged()
            }
            else -> return Bundle().apply { putBoolean(OK, false) }
        }
        Log.i(TAG, "$method from ${if (lumen) "Lumen" else "adb shell"}")
        return Bundle().apply { putBoolean(OK, true) }
    }

    /**
     * The pairing link and QR code for the current secret (made on first use, or replaced when
     * [renew]). Needs phone control on and a network address to put in the link.
     */
    private fun pair(context: android.content.Context, renew: Boolean): Bundle {
        val address = PhoneService.state.address
            ?: return Bundle().apply { putBoolean(OK, false); putString("error", if (PhoneService.isEnabled(context)) "no-network" else "phone-off") }
        val store = PhoneSecret(context)
        val secret = (if (renew) null else store.load()) ?: store.create().also { PhoneService.secretChanged() }
        val url = PhoneService.pairingUrl(address, secret)
        secret.fill(0)
        return Bundle().apply {
            putBoolean(OK, true)
            putString("address", address)
            putString("url", url)
            putString("qr", QrCode.encode(url).rows())
        }
    }

    private fun SportsStatus.toBundle() = Bundle().apply {
        putBoolean(OK, true)
        putBoolean("enabled", enabled)
        putBoolean("visible", visible)
        putInt(TickerConfig.HEIGHT, settings.heightDp)
        putFloat(TickerConfig.OPACITY, settings.opacity)
        putInt(TickerConfig.SPEED, settings.speedDp)
        putInt(TickerConfig.FPS, settings.fps)
        putInt(TickerConfig.ALERT, settings.alertSeconds)
        putString(TickerConfig.EDGE, if (settings.top) TickerConfig.TOP else TickerConfig.BOTTOM)
        putBoolean(TickerConfig.AUTO, settings.auto)
        putString("espn", espn.name.lowercase())
        putBoolean("signedIn", signedIn)
        putLong("lastRefresh", lastRefresh ?: -1)
        team?.let { putString("team", it) }
        leagueId?.let { putString("league", it) }
        putBoolean("phone", phone.enabled)
        putBoolean("phoneListening", phone.listening)
        phone.address?.let { putString("phoneAddress", it) }
        putBoolean("phonePaired", phone.paired)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        private const val TAG = "LumenSports"
        const val PERMISSION = "app.lumen.sports.permission.CONTROL"
        const val METHOD_STATUS = "status"
        const val METHOD_SHOW = "show"
        const val METHOD_HIDE = "hide"
        const val METHOD_SET = "set"
        const val METHOD_REFRESH = "refresh"
        const val METHOD_PREVIEW = "preview"
        const val METHOD_PHONE = "phone"
        const val METHOD_PAIR = "pair"
        const val METHOD_UNPAIR = "unpair"
        const val METHOD_FOREGROUND = "foreground"
        private const val OK = "ok"
    }
}
