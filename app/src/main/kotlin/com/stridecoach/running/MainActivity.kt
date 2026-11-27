package com.stridecoach.running

import android.content.Intent
import android.graphics.Color.TRANSPARENT
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
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
    private lateinit var vm: StrideCoachViewModel

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        setBars(light = false, nav = KdeNavBg.toArgb())
        val app = application as StrideCoachApplication
        val fac = StrideCoachViewModel.Factory(app.repository, app.preferencesStore)
        vm = ViewModelProvider(this, fac)[StrideCoachViewModel::class.java]
        onUri(intent)
        setContent { Root(app, fac) }
    }

    @Composable
    private fun Root(app: StrideCoachApplication, fac: ViewModelProvider.Factory) {
        val prefs by app.preferencesStore.preferences.collectAsState(initial = CoachPreferences())
        StrideCoachTheme(themeMode = prefs.themeMode, accentName = prefs.accentName) {
            val light = StrideThemeState.isLight
            val nav = KdeNavBg.toArgb()
            LaunchedEffect(light, nav) { setBars(light, nav) }
            val model: StrideCoachViewModel = viewModel(factory = fac)
            AppShell(model, window)
        }
    }

    private fun setBars(light: Boolean, nav: Int) {
        val top = if (light) SystemBarStyle.light(TRANSPARENT, TRANSPARENT) else SystemBarStyle.dark(TRANSPARENT)
        val bot = if (light) SystemBarStyle.light(nav, nav) else SystemBarStyle.dark(nav)
        enableEdgeToEdge(statusBarStyle = top, navigationBarStyle = bot)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        onUri(i)
    }

    private fun onUri(i: Intent?) {
        val u = uriOf(i) ?: return
        vm.importAutoDetectedUri(contentResolver, u)
    }

    private fun uriOf(i: Intent?): Uri? {
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
