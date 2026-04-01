package com.engfred.yvd.util

import android.content.Context
import androidx.core.content.edit

object PreferencesHelper {
    private const val PREFS_NAME = "yvd_prefs"
    private const val KEY_ONBOARDING_DONE = "onboarding_done"

    fun isOnboardingDone(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ONBOARDING_DONE, false)
    }

    fun setOnboardingDone(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                putBoolean(KEY_ONBOARDING_DONE, true)
            }
    }
}