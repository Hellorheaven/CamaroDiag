package com.camarodiag.gen5

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.camarodiag.gen5.auth.CamaroAuthGate
import com.camarodiag.gen5.ui.CamaroDiagApp
import com.camarodiag.gen5.ui.MainViewModel
import com.camarodiag.gen5.ui.theme.CamaroDiagTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CamaroDiagTheme {
                CamaroAuthGate(
                    onAccountChanged = viewModel::onAccountSessionChanged
                ) { CamaroDiagApp(viewModel = viewModel) }
            }
        }
    }
}
