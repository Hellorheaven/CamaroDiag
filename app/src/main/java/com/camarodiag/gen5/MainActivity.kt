package com.camarodiag.gen5

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.activity.viewModels
import com.camarodiag.gen5.auth.CamaroAuthGate
import com.camarodiag.gen5.ui.CamaroDiagApp
import com.camarodiag.gen5.ui.MainViewModel
import com.camarodiag.gen5.ui.theme.CamaroDiagTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep Compose content within the system bars on Android 10–16.
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContent {
            CamaroDiagTheme {
                CamaroAuthGate(
                    onAccountChanged = viewModel::onAccountSessionChanged,
                    onAccountDeleted = viewModel::onAccountDeleted
                ) { CamaroDiagApp(viewModel = viewModel) }
            }
        }
    }
}
