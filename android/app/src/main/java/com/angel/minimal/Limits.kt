package com.angel.minimal

/**
 * One app limit: a daily allowance its apps share ([mins] > 0), or a block until a set time ([until] > 0).
 * An empty [name] means a single app's own limit, set from its long-press menu; a named one is a group.
 */
data class Rule(val name: String = "", val apps: List<String> = emptyList(), val mins: Int = 0, val until: Long = 0)

data class Hit(val rule: Rule, val blocked: Boolean, val used: Long)

object Limits {
    /** The rule that stops [pkg] from opening right now, if any. Blocks win over daily limits. */
    fun check(rules: List<Rule>, pkg: String, usage: Map<String, Long>, now: Long): Hit? {
        rules.firstOrNull { pkg in it.apps && it.until > now }?.let { return Hit(it, blocked = true, used = 0) }
        for (r in rules) {
            if (pkg !in r.apps || r.mins <= 0) continue
            val used = r.apps.sumOf { usage[it] ?: 0L }
            if (used >= r.mins * 60_000L) return Hit(r, blocked = false, used = used)
        }
        return null
    }

    /** Time left on a block: "2d 5h", "3h 10m", "12m". */
    fun left(ms: Long): String {
        val h = ms / 3_600_000
        return if (h >= 24) "${h / 24}d ${h % 24}h" else ScreenTime.format(maxOf(ms, 60_000))
    }
}
