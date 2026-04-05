package com.engfred.yvd

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.engfred.yvd.domain.model.AppTheme
import com.engfred.yvd.domain.model.UpdateInfo
import com.engfred.yvd.domain.usecases.CheckForUpdateUseCase
import com.engfred.yvd.service.FloatingBubbleService
import com.engfred.yvd.ui.MainScreen
import com.engfred.yvd.ui.components.UpdateDialog
import com.engfred.yvd.ui.home.HomeViewModel
import com.engfred.yvd.ui.onboarding.OnboardingScreen
import com.engfred.yvd.ui.splash.AnimatedSplashScreen
import com.engfred.yvd.ui.theme.YVDTheme
import com.engfred.yvd.util.AppLifecycleTracker
import com.engfred.yvd.util.PreferencesHelper
import com.engfred.yvd.util.UrlValidator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()
    private val homeViewModel: HomeViewModel by viewModels()

    @Inject lateinit var checkForUpdateUseCase: CheckForUpdateUseCase

    // State variable to track the last clipboard text we processed
    private var lastProcessedClipboardText: String? = null

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

                var onboardingDone by remember {
                    mutableStateOf(PreferencesHelper.isOnboardingDone(this@MainActivity))
                }

                var showSplash by remember { mutableStateOf(true) }

                // Update Dialog States
                var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
                var showUpdateDialog by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    // 1. Instantly check cached updates
                    val cachedInfo = PreferencesHelper.getCachedUpdateInfo(this@MainActivity)
                    if (cachedInfo != null && checkForUpdateUseCase.isNewerVersion(cachedInfo.latestVersion, BuildConfig.VERSION_NAME)) {
                        updateInfo = cachedInfo
                        showUpdateDialog = true
                    }

                    // 2. Silently refresh cache via GitHub API if older than 12 hours
                    val lastCheck = PreferencesHelper.getLastUpdateCheck(this@MainActivity)
                    val now = System.currentTimeMillis()
                    val twelveHoursMs = 12L * 60 * 60 * 1000

                    if (now - lastCheck > twelveHoursMs) {
                        lifecycleScope.launch {
                            val info = checkForUpdateUseCase(BuildConfig.VERSION_NAME)
                            PreferencesHelper.setLastUpdateCheck(this@MainActivity, now)
                            if (info != null) {
                                PreferencesHelper.saveCachedUpdateInfo(this@MainActivity, info)
                                if (updateInfo?.latestVersion != info.latestVersion) {
                                    updateInfo = info
                                    showUpdateDialog = true
                                }
                            }
                        }
                    }
                }

                YVDTheme(darkTheme = useDarkTheme) {
                    Crossfade(
                        targetState = showSplash,
                        label = "SplashTransition",
                        animationSpec = tween(durationMillis = 600)
                    ) { isSplashActive ->
                        if (isSplashActive) {
                            AnimatedSplashScreen(
                                onFinished = { showSplash = false }
                            )
                        } else {
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

                    val currentUpdateInfo = updateInfo
                    if (showUpdateDialog && currentUpdateInfo != null) {
                        UpdateDialog(
                            updateInfo = currentUpdateInfo,
                            onDownload = { showUpdateDialog = false },
                            onRemindLater = { showUpdateDialog = false },
                            onDismiss = { showUpdateDialog = false }
                        )
                    }
                }
            }
        }

        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        AppLifecycleTracker.isInForeground = true
        stopService(Intent(this, FloatingBubbleService::class.java))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim()

        if (!clip.isNullOrBlank()) {
            val sanitizedClip = UrlValidator.sanitize(clip)
            val currentInput = homeViewModel.state.value.urlInput

            // Only trigger if the clipboard link is completely different from the one currently in the search bar.
            // This prevents the infinite dialog loop when bottom sheets close and the window regains focus.
            if (
                UrlValidator.isValidYouTubeUrl(sanitizedClip) &&
                clip != lastProcessedClipboardText &&
                sanitizedClip != currentInput
            ) {
                lastProcessedClipboardText = clip // Remember this link
                homeViewModel.handleIncomingUrl(sanitizedClip)
            }
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

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    intent.getStringExtra(Intent.EXTRA_TEXT)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { clip ->
                            lastProcessedClipboardText = clip // Remember this link
                            homeViewModel.handleIncomingUrl(clip)
                        }
                }
            }
        }
    }
}