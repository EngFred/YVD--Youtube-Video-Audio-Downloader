package com.engfred.yvd.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.engfred.yvd.domain.model.AppTheme
import com.engfred.yvd.domain.repository.ThemeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import androidx.core.content.edit

class ThemeRepositoryImpl @Inject constructor(
    context: Context
) : ThemeRepository {

    private val prefs: SharedPreferences = context.getSharedPreferences("yvd_prefs", Context.MODE_PRIVATE)
    private val _theme = MutableStateFlow(loadTheme())

    override val theme: Flow<AppTheme> = _theme.asStateFlow()

    override suspend fun setTheme(theme: AppTheme) {
        prefs.edit { putString("app_theme", theme.name) }
        _theme.value = theme
    }

    private fun loadTheme(): AppTheme {
        val themeName = prefs.getString("app_theme", AppTheme.LIGHT.name)
        return try {
            AppTheme.valueOf(themeName ?: AppTheme.LIGHT.name)
        } catch (e: Exception) {
            AppTheme.LIGHT
        }
    }
}