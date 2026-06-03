package com.jisungbin.networkinspector.core

import com.jisungbin.networkinspector.engine.NetworkRow

/**
 * Whether [row] is matched by [rule]: enabled, method matches (or ANY), and the rule's URL pattern
 * (scheme stripped) is a substring of the row URL. Used by the stream loop in [SessionController]
 * to attribute a mock hit to the rule that caused it.
 */
internal fun matchesRow(row: NetworkRow, rule: InterceptRule): Boolean {
    if (!rule.enabled) return false
    if (rule.method != "ANY" && rule.method.uppercase() != row.method.uppercase()) return false
    val needle = rule.urlPattern.removePrefix("https://").removePrefix("http://")
    return needle.isNotBlank() && needle in row.url
}
