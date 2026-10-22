package com.trustmarktool

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.trustmarktool.ui.MainScreen
import com.trustmarktool.ui.theme.TrustMarkToolTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TrustMarkToolTheme {
                MainScreen(viewModel)
            }
        }
    }
}
