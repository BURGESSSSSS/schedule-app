package com.burgess.schedule.ui.theme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map

// Temporary platform storage until Maven dependencies are reachable; isolated for migration.
object Appearance {
    private fun preferences(context: Context) = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private fun changes(context: Context) = callbackFlow {
        val prefs = preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(prefs) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(prefs)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun scale(context: Context) = changes(context).map { it.getFloat("motion_scale", 1f) }
    fun theme(context: Context) = changes(context).map { it.getString("theme", "跟随系统") ?: "跟随系统" }
    suspend fun setScale(context: Context, value: Float) { preferences(context).edit().putFloat("motion_scale", value).apply() }
    suspend fun setTheme(context: Context, value: String) { preferences(context).edit().putString("theme", value).apply() }
}
