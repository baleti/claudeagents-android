package dev.local.claudeagents

import android.content.Context

/**
 * App-wide behavior toggles that don't belong to pairing (TokenStore) or
 * read-aloud (TtsSettings) -- kept in its own prefs file for the same
 * reason those are split out.
 */
object AppSettings {
    private const val PREFS = "claudeagents_app_prefs"

    // Asked for explicitly 2026-09-11: whether Back from a conversation
    // walks backward through whichever conversations were actually
    // visited (the default -- see ChatActivity's own switch-without-
    // finish() doc) or always drops straight back to the main
    // conversation list regardless of how many were visited via drawer
    // switches or swipe navigation.
    fun getBackToMainMenu(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("back_to_main_menu", false)

    fun setBackToMainMenu(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("back_to_main_menu", value).apply()
    }
}
