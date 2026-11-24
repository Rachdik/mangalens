package com.mangalens.translation

import android.content.Context

/**
 * Pencatatan penggunaan token agregat (bukan tagihan resmi provider).
 */
object TokenUsageStore {
    private const val PREFS = "mangalens_token_usage"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun add(context: Context, usage: TokenUsage?, provider: String, model: String) {
        if (usage == null) return
        val p = prefs(context)
        p.edit()
            .putInt("requests", p.getInt("requests", 0) + 1)
            .putLong("input", p.getLong("input", 0) + (usage.inputTokens ?: 0))
            .putLong("output", p.getLong("output", 0) + (usage.outputTokens ?: 0))
            .putLong("total", p.getLong("total", 0) + (usage.totalTokens ?: 0))
            .putString("last_provider", provider)
            .putString("last_model", model)
            .apply()
    }

    fun summary(context: Context): String {
        val p = prefs(context)
        return "Requests=${p.getInt("requests", 0)}, " +
            "input=${p.getLong("input", 0)}, " +
            "output=${p.getLong("output", 0)}, " +
            "total=${p.getLong("total", 0)}, " +
            "last=${p.getString("last_provider", "-")}/${p.getString("last_model", "-")}"
    }

    fun reset(context: Context) {
        prefs(context).edit().clear().apply()
    }
}