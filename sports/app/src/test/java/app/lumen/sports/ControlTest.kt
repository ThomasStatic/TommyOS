package app.lumen.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlTest {
    private val defaults = TickerConfig.DEFAULTS

    @Test fun `settings are clamped to their ranges`() {
        val v = TickerConfig.merge(defaults, mapOf("height" to 500, "opacity" to -2.0, "speed" to 1, "alert" to 99, "fps" to 45))
        assertEquals(96, v.heightDp)
        assertEquals(0f, v.opacity)
        assertEquals(10, v.speedDp)
        assertEquals(15, v.alertSeconds)
        assertEquals(30, v.fps)
    }

    @Test fun `numbers arrive as any numeric type or a numeric string`() {
        val v = TickerConfig.merge(defaults, mapOf("height" to 60L, "opacity" to 0.5f, "speed" to "75", "alert" to 3.9, "fps" to "60"))
        assertEquals(60, v.heightDp)
        assertEquals(0.5f, v.opacity)
        assertEquals(75, v.speedDp)
        assertEquals(3, v.alertSeconds)
        assertEquals(60, v.fps)
    }

    @Test fun `malformed values and unknown keys change nothing`() {
        val v = TickerConfig.merge(defaults, mapOf(
            "height" to "tall", "opacity" to Double.NaN, "speed" to null, "alert" to listOf(1), "edge" to "left", "volume" to 11,
        ))
        assertEquals(defaults, v)
    }

    @Test fun `edge accepts top and bottom in any case`() {
        val top = TickerConfig.merge(defaults, mapOf("edge" to " TOP "))
        assertTrue(top.top)
        assertFalse(TickerConfig.merge(top, mapOf("edge" to "bottom")).top)
    }

    @Test fun `only the given keys change`() {
        val start = defaults.copy(heightDp = 70, top = true)
        assertEquals(start.copy(opacity = 0.4f), TickerConfig.merge(start, mapOf("opacity" to 0.4)))
    }

    @Test fun `the phone sees no league data`() {
        val status = SportsStatus(
            enabled = true, visible = true, settings = defaults, espn = EspnState.EXPIRED, lastRefresh = 1_000L,
            signedIn = true, team = "Secret Team Name", leagueId = "2061761840",
            phone = SportsStatus.PhoneStatus(enabled = true, listening = true, address = "http://10.0.0.213:8737/", paired = true),
        )
        val json = status.toPhoneJson(now = 2_000L)
        val text = json.toString()
        assertFalse(text.contains("Secret"))
        assertFalse(text.contains("2061761840"))
        assertEquals(
            setOf("enabled", "visible", "edge", "auto", "height", "opacity", "speed", "alert", "espn", "signedIn", "lastRefresh", "now"),
            json.keys().asSequence().toSet(),
        )
        assertEquals("expired", json.getString("espn"))
        assertEquals("bottom", json.getString("edge"))
    }

    @Test fun `auto mode accepts booleans, numbers and on-off words`() {
        assertTrue(TickerConfig.merge(defaults, mapOf("auto" to "on")).auto)
        assertTrue(TickerConfig.merge(defaults, mapOf("auto" to true)).auto)
        assertTrue(TickerConfig.merge(defaults, mapOf("auto" to 1)).auto)
        val on = defaults.copy(auto = true)
        assertFalse(TickerConfig.merge(on, mapOf("auto" to "OFF")).auto)
        assertTrue(TickerConfig.merge(on, mapOf("auto" to "maybe")).auto)
    }

    @Test fun `auto rules must look like package and class names`() {
        assertEquals("a.b/c.", TickerConfig.merge(defaults, mapOf("apps" to "a.b/c.")).autoApps)
        assertEquals(AutoRules.DEFAULT, TickerConfig.merge(defaults, mapOf("apps" to "a.b; rm -rf")).autoApps)
        assertEquals(AutoRules.DEFAULT, TickerConfig.merge(defaults, mapOf("apps" to "x".repeat(600))).autoApps)
    }

    @Test fun `auto mode shows over TSN's player only`() {
        val rules = AutoRules.DEFAULT
        assertTrue(AutoRules.matches(rules, "ca.tsn.mobile.android/com.deltatre.d3studiotemplate.playback.BellDivaTvPlaybackActivity"))
        // TSN's browse screens and sign-in, other apps, Amazon's settings and PIN screens, and Lumen: hidden.
        assertFalse(AutoRules.matches(rules, "ca.tsn.mobile.android/com.deltatre.d3studiotemplate.launcher.LauncherActivity"))
        assertFalse(AutoRules.matches(rules, "ca.tsn.mobile.android/com.deltatre.android.iam.scale.service.identity.providers.adb2c.ui.AuthorizationActivity"))
        assertFalse(AutoRules.matches(rules, "com.netflix.ninja/com.netflix.ninja.MainActivity"))
        assertFalse(AutoRules.matches(rules, "com.amazon.tv.settings.v2/com.amazon.tv.settings.v2.tv.parentalcontrols.PinActivity"))
        assertFalse(AutoRules.matches(rules, "app.lumen.home/app.lumen.home.HomeActivity"))
        assertFalse(AutoRules.matches(rules, null)) // Nothing reported yet: stay hidden.
        assertFalse(AutoRules.matches(rules, "ca.tsn.mobile.android.fake/com.deltatre.d3studiotemplate.playback.X"))
    }

    @Test fun `a bare package rule matches every screen of that app`() {
        val rules = "com.netflix.ninja, ${AutoRules.TSN_PLAYER}"
        assertTrue(AutoRules.matches(rules, "com.netflix.ninja/anything.Activity"))
        assertTrue(AutoRules.matches(rules, "com.netflix.ninja/"))
        assertFalse(AutoRules.matches("", "com.netflix.ninja/x"))
    }

    @Test fun `no refresh yet is null for the phone`() {
        val status = SportsStatus(false, false, defaults, EspnState.UNKNOWN, null, signedIn = false)
        assertTrue(status.toPhoneJson(0).isNull("lastRefresh"))
    }
}
