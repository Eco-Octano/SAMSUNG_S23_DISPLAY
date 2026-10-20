package com.ecooctano.telemetry.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Modos do app. Qualquer aparelho pode usar qualquer um. */
enum class AppMode { DISPLAY, ANALYSIS }

/** Guarda o último modo escolhido (null = ainda não escolhido: mostra a tela de seleção). */
class AppModeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("app_mode", Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(read())
    val mode: StateFlow<AppMode?> = _mode.asStateFlow()

    fun select(mode: AppMode) {
        prefs.edit().putString(KEY, mode.name).apply()
        _mode.value = mode
    }

    private fun read(): AppMode? =
        prefs.getString(KEY, null)?.let { name -> AppMode.entries.firstOrNull { it.name == name } }

    private companion object {
        const val KEY = "mode"
    }
}
