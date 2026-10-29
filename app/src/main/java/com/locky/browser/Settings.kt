package com.locky.browser

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A boolean setting that is persisted and observable from Compose. */
class BoolPref(private val prefs: SharedPreferences, private val key: String, default: Boolean) {
    var value by mutableStateOf(prefs.getBoolean(key, default))
        private set

    fun set(v: Boolean) {
        value = v
        prefs.edit().putBoolean(key, v).apply()
    }
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("locky_settings", Context.MODE_PRIVATE)

    val adBlock = BoolPref(prefs, "adBlock", true)
    val popupBlock = BoolPref(prefs, "popupBlock", true)
    val redirectGuard = BoolPref(prefs, "redirectGuard", true)
    val dataSaver = BoolPref(prefs, "dataSaver", false)
    val httpsOnly = BoolPref(prefs, "httpsOnly", true)
    val blockThirdPartyCookies = BoolPref(prefs, "blockThirdPartyCookies", true)
    val phishingWarning = BoolPref(prefs, "phishingWarning", true)
    val appLock = BoolPref(prefs, "appLock", false)
}
