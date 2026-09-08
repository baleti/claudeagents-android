package dev.local.claudeagents

/** Compact "2m"/"3h"/"5d"/"2w" relative-age formatting -- same idea as the
 * desktop panel's fmtDuration (ClaudeUsageExpanded.qml) but collapsed to a
 * single unit, since a phone table column has far less width to spend. */
object Fmt {
    fun ago(epochSeconds: Double): String {
        val deltaS = (System.currentTimeMillis() / 1000.0) - epochSeconds
        val s = deltaS.toLong().coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m"
            s < 86400 -> "${s / 3600}h"
            s < 7 * 86400 -> "${s / 86400}d"
            s < 30 * 86400 -> "${s / (7 * 86400)}w"
            else -> "${s / (30 * 86400)}mo"
        }
    }

    // Same idea as the desktop panel's fmtTokens (ClaudeUsageExpanded.qml):
    // 135242 -> "135k", 850 -> "850", null (no assistant usage recorded
    // yet, e.g. a brand-new conversation) -> "--".
    fun tokens(n: Int?): String {
        if (n == null) return "--"
        if (n < 1000) return n.toString()
        val k = n / 1000.0
        return (if (n < 10000) "%.1f".format(k) else k.toInt().toString()) + "k"
    }
}
