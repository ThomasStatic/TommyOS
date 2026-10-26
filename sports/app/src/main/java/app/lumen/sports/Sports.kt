package app.lumen.sports

import android.content.Context
import android.content.Intent

/**
 * The one way to switch and configure the ticker, used by [ControlProvider] (Lumen and `adb shell`)
 * and the phone controller. Settings are saved here, then a running ticker is told to apply them;
 * nothing here starts the ticker except [show].
 */
object Sports {
    fun show(context: Context, changes: Map<String, Any?> = emptyMap()) {
        TickerConfig(context).update(changes)
        context.startForegroundService(Intent(context, TickerService::class.java))
    }

    /** Hides the ticker and stops its service. A no-op when it is already off. */
    fun hide(context: Context) = TickerService.sendIfRunning(context, TickerService.ACTION_HIDE)

    /** Saves settings; a showing ticker applies them at once. Never shows a hidden ticker. */
    fun set(context: Context, changes: Map<String, Any?>) {
        TickerConfig(context).update(changes)
        TickerService.sendIfRunning(context, TickerService.ACTION_APPLY)
    }

    fun refresh(context: Context) = TickerService.sendIfRunning(context, TickerService.ACTION_REFRESH)

    /** The app in front, `package/activity.Class`, as Lumen last reported it; null until it does. */
    @Volatile var foreground: String? = null
        private set

    /**
     * Lumen reports the app in front (only while asked to: see TickerService.follow). Returns whether it
     * should keep reporting, so a flag left behind by a killed ticker clears itself on the next change.
     */
    fun forgetForeground() {
        foreground = null
    }

    fun foreground(value: String): Boolean {
        foreground = value
        TickerService.foregroundChanged()
        return TickerService.wantsForeground
    }

    fun preview(context: Context, what: String) =
        TickerService.sendIfRunning(context, TickerService.ACTION_PREVIEW) { it.putExtra(TickerService.EXTRA_PREVIEW, what) }

    /** Status for Lumen (with [SportsStatus.team] and [SportsStatus.leagueId]); the phone gets [SportsStatus.toPhoneJson]. */
    fun status(context: Context, includeLeague: Boolean): SportsStatus {
        val live = TickerService.live
        val cached = if (live.lastRefresh == null || includeLeague) TickerService.cache(context).load() else null
        val creds = Credentials(context)
        return SportsStatus(
            enabled = TickerService.isRunning,
            visible = live.visible,
            settings = TickerConfig(context).load(),
            espn = espnState(context),
            lastRefresh = live.lastRefresh ?: cached?.fetchedAt,
            signedIn = creds.exists(),
            team = if (includeLeague) cached?.mine?.left?.name else null,
            leagueId = if (includeLeague) creds.load()?.leagueId else null,
            phone = SportsStatus.PhoneStatus(
                enabled = PhoneService.isEnabled(context),
                listening = PhoneService.state.listening,
                address = PhoneService.state.address,
                paired = PhoneSecret(context).exists(),
            ),
        )
    }

    // ESPN's state at the last fetch, kept so Lumen can show "sign-in expired" while the ticker is off.
    private const val STATUS_PREFS = "status"
    private const val ESPN = "espn"

    fun espnState(context: Context): EspnState =
        context.getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE).getString(ESPN, null)
            ?.let { runCatching { EspnState.valueOf(it) }.getOrNull() } ?: EspnState.UNKNOWN

    fun saveEspnState(context: Context, state: EspnState) {
        if (espnState(context) == state) return
        context.getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE).edit().putString(ESPN, state.name).apply()
    }
}
