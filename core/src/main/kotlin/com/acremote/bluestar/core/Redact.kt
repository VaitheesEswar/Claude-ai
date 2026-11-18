package com.acremote.bluestar.core

/**
 * Masks secrets and personal details in a parsed JSON tree (maps/lists) so the
 * Diagnostics screen can be shared safely.
 */
object Redact {
    private val SENSITIVE = Regex(
        "(token|session|secret|password|passwd|key|cert|auth|email|phone|mobile|^mi$|otp|cred)",
        RegexOption.IGNORE_CASE,
    )

    fun tree(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associate { (k, v) ->
            val key = k.toString()
            key to if (SENSITIVE.containsMatchIn(key) && v != null && v !is Map<*, *> && v !is List<*>) "***" else tree(v)
        }
        is List<*> -> value.map { tree(it) }
        else -> value
    }
}
