package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.presentation.di.AppContainer
import com.example.presentation.di.MainViewModelFactory
import com.example.presentation.ui.MainAppScreen
import com.example.presentation.viewmodel.MainViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val appContainer: AppContainer by lazy {
        AppContainer(applicationContext)
    }

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(appContainer)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainAppScreen(viewModel = viewModel)
                }
            }
        }
    }
}
