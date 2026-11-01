package app.lumen.sports

/**
 * Which foreground screens show the ticker in auto mode. Rules are comma-separated: `package` (any
 * screen of that app) or `package/class.prefix` (screens whose activity class starts with the
 * prefix). The default is TSN's player only: not TSN's browse screens (which a bottom bar covers)
 * and not its sign-in screen. Everything else, Amazon's settings and PIN screens included, hides it.
 */
object AutoRules {
    const val TSN_PLAYER = "ca.tsn.mobile.android/com.deltatre.d3studiotemplate.playback."
    const val DEFAULT = TSN_PLAYER
    private val SHAPE = Regex("[A-Za-z0-9._$/,]{0,512}")

    fun valid(rules: String) = SHAPE.matches(rules)

    /** [foreground] is `package/activity.Class` as reported by Lumen; null (not known yet) never matches. */
    fun matches(rules: String, foreground: String?): Boolean {
        val pkg = foreground?.substringBefore('/') ?: return false
        val cls = foreground.substringAfter('/', "")
        return rules.split(',').map { it.trim() }.filter { it.isNotEmpty() }.any { rule ->
            if ('/' in rule) pkg == rule.substringBefore('/') && cls.startsWith(rule.substringAfter('/'))
            else pkg == rule
        }
    }
}
