package com.camarodiag.gen5

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.camarodiag.gen5.ui.CamaroDiagUpdateCleanup
import com.camarodiag.gen5.auth.CamaroAuthGate
import com.camarodiag.gen5.ui.CamaroDiagApp
import com.camarodiag.gen5.ui.MainViewModel
import com.camarodiag.gen5.ui.theme.CamaroDiagTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recover missed MY_PACKAGE_REPLACED broadcasts after process death:
        // delete downloaded update APKs only if the installed versionCode advanced.
        lifecycleScope.launch(Dispatchers.IO) {
            CamaroDiagUpdateCleanup.cleanupAfterSuccessfulInstall(applicationContext)
        }
        // Keep Compose content within the system bars on Android 10–16.
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContent {
            CamaroDiagTheme {
                CamaroAuthGate(
                    onAccountChanged = viewModel::onAccountSessionChanged,
                    onAccountDeleted = viewModel::onAccountDeleted
                ) { openAccount -> CamaroDiagApp(viewModel = viewModel, onOpenAccount = openAccount) }
            }
        }
    }
}
