package com.embedded.rtlflasher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.embedded.rtlflasher.ui.FlasherScreen
import com.embedded.rtlflasher.ui.FlasherViewModel
import com.embedded.rtlflasher.ui.theme.RTL8720DNFlasherTheme

class MainActivity : ComponentActivity() {

    private val viewModel: FlasherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RTL8720DNFlasherTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FlasherScreen(viewModel = viewModel)
                }
            }
        }
    }
}
