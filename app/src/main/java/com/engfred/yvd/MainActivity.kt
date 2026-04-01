package com.engfred.yvd

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.engfred.yvd.domain.model.AppTheme
import com.engfred.yvd.service.FloatingBubbleService
import com.engfred.yvd.ui.MainScreen
import com.engfred.yvd.ui.home.HomeViewModel
import com.engfred.yvd.ui.onboarding.OnboardingScreen
import com.engfred.yvd.ui.theme.YVDTheme
import com.engfred.yvd.util.AppLifecycleTracker
import com.engfred.yvd.util.BubblePermissionHelper
import com.engfred.yvd.util.PreferencesHelper
import com.engfred.yvd.util.UrlValidator
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()
    private val homeViewModel: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        splashScreen.setKeepOnScreenCondition {
            mainViewModel.theme.value == null
        }

        setContent {
            val appTheme by mainViewModel.theme.collectAsState()
            if (appTheme != null) {
                val useDarkTheme = when (appTheme) {
                    AppTheme.LIGHT -> false
                    AppTheme.DARK -> true
                    else -> isSystemInDarkTheme()
                }

                YVDTheme(darkTheme = useDarkTheme) {
                    // Check onboarding once, driven by remember so it survives recomposition
                    var onboardingDone by remember {
                        mutableStateOf(PreferencesHelper.isOnboardingDone(this@MainActivity))
                    }

                    if (!onboardingDone) {
                        OnboardingScreen(
                            onFinished = {
                                PreferencesHelper.setOnboardingDone(this@MainActivity)
                                onboardingDone = true
                            }
                        )
                    } else {
                        MainScreen(homeViewModel = homeViewModel)
                    }
                }
            }
        }

        // In MainActivity.onCreate()
        if (BubblePermissionHelper.canDrawOverlays(this)) {
            ContextCompat.startForegroundService(
                this, Intent(this, FloatingBubbleService::class.java)
            )
        } else {
            android.app.AlertDialog.Builder(this)
                .setTitle("Enable Floating Bubble")
                .setMessage("YV Downloader uses a floating bubble so you can quickly return to the app after copying a YouTube link. Please enable 'Appear on top' on the next screen.")
                .setPositiveButton("Grant Permission") { _, _ ->
                    BubblePermissionHelper.openOverlaySettings(this)
                }
                .setNegativeButton("Not Now", null)
                .show()
        }

        // Handle URL from Share sheet (e.g. YouTube → Share → YV Downloader)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        AppLifecycleTracker.isInForeground = true

        if (BubblePermissionHelper.canDrawOverlays(this)) {
            ContextCompat.startForegroundService(
                this, Intent(this, FloatingBubbleService::class.java)
            )
        }

        startService(Intent(this, FloatingBubbleService::class.java).apply {
            action = FloatingBubbleService.ACTION_HIDE
        })
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return

        // Window now truly has focus — clipboard access is guaranteed at this point.
        // Auto-load if user copied a YouTube link while away (e.g. in YouTube app).
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
        if (
            !clip.isNullOrBlank() &&
            UrlValidator.isValidYouTubeUrl(UrlValidator.sanitize(clip)) &&
            clip != homeViewModel.state.value.urlInput  // don't reload same URL twice
        ) {
            homeViewModel.handleIncomingUrl(clip)
        }
    }

    override fun onPause() {
        super.onPause()
        AppLifecycleTracker.isInForeground = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    /**
     * Handles URLs arriving from two sources:
     * 1. Share sheet — user taps Share in YouTube and picks this app (ACTION_SEND)
     * 2. Direct deep link if needed in future (ACTION_VIEW)
     */
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    intent.getStringExtra(Intent.EXTRA_TEXT)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { homeViewModel.handleIncomingUrl(it) }
                }
            }
        }
    }
}