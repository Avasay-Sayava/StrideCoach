package com.stridecoach.running

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stridecoach.running.core.data.repository.CoachPreferences
import com.stridecoach.running.ui.AppShell
import com.stridecoach.running.ui.StrideCoachViewModel
import com.stridecoach.running.ui.theme.KdeNavBg
import com.stridecoach.running.ui.theme.StrideCoachTheme
import com.stridecoach.running.ui.theme.StrideThemeState

class MainActivity : ComponentActivity() {
    private lateinit var vmRef: StrideCoachViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(KdeNavBg.toArgb())
        )
        val app = application as StrideCoachApplication
        val factory = StrideCoachViewModel.Factory(app.repository, app.preferencesStore)
        vmRef = ViewModelProvider(this, factory)[StrideCoachViewModel::class.java]
        handleIntentUri(intent)

        setContent {
            val prefs by app.preferencesStore.preferences.collectAsState(initial = CoachPreferences())
            StrideCoachTheme(
                themeMode = prefs.themeMode,
                accentName = prefs.accentName
            ) {
                val isLight = StrideThemeState.isLight
                val navArgb = KdeNavBg.toArgb()
                LaunchedEffect(isLight, navArgb) { applySystemBarStyle(isLight, navArgb) }
                val vm: StrideCoachViewModel = viewModel(factory = factory)
                AppShell(vm, window)
            }
        }
    }

    private fun applySystemBarStyle(isLight: Boolean, navArgb: Int) {
        val statusStyle = if (isLight) {
            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        }
        val navStyle = if (isLight) SystemBarStyle.light(navArgb, navArgb) else SystemBarStyle.dark(navArgb)
        enableEdgeToEdge(statusBarStyle = statusStyle, navigationBarStyle = navStyle)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentUri(intent)
    }

    private fun handleIntentUri(i: Intent?) {
        val u = readUri(i) ?: return
        vmRef.importAutoDetectedUri(contentResolver, u)
    }

    private fun readUri(i: Intent?): Uri? {
        if (i == null) return null
        if (i.action == Intent.ACTION_VIEW) return i.data
        if (i.action != Intent.ACTION_SEND) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            i.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }
}
