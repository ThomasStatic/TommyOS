package app.lumen.sports

import org.json.JSONObject

/** Where ESPN stood at the last fetch. [UNKNOWN] until the first fetch after install or sign-in. */
enum class EspnState { OK, SIGNED_OUT, EXPIRED, UNAVAILABLE, UNKNOWN }

/**
 * What Lumen's Sports settings and the phone page show. [team] and [leagueId] are for Lumen only
 * (a signature-checked caller on the TV): [toPhoneJson] leaves them out, along with everything else
 * from the league, so nothing about the league or its members leaves the TV.
 */
data class SportsStatus(
    /** The ticker is switched on (its service runs). */
    val enabled: Boolean,
    /** Its window is on screen. */
    val visible: Boolean,
    val settings: TickerConfig.Value,
    val espn: EspnState,
    /** When ESPN last answered with scores (epoch ms), from the running ticker or the saved snapshot. */
    val lastRefresh: Long?,
    val signedIn: Boolean,
    val team: String? = null,
    val leagueId: String? = null,
    val phone: PhoneStatus = PhoneStatus(),
) {
    data class PhoneStatus(
        /** Phone control is switched on (the listener runs, and starts at boot). */
        val enabled: Boolean = false,
        /** The listener is bound; false while there is no Wi-Fi. */
        val listening: Boolean = false,
        /** http://address:port/ when listening; the pairing secret is never part of the status. */
        val address: String? = null,
        val paired: Boolean = false,
    )

    fun toPhoneJson(now: Long): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("visible", visible)
        .put("edge", if (settings.top) TickerConfig.TOP else TickerConfig.BOTTOM)
        .put("auto", settings.auto)
        .put("height", settings.heightDp)
        .put("opacity", settings.opacity.toDouble())
        .put("speed", settings.speedDp)
        .put("alert", settings.alertSeconds)
        .put("espn", espn.name.lowercase())
        .put("signedIn", signedIn)
        .put("lastRefresh", lastRefresh ?: JSONObject.NULL)
        .put("now", now)
}
