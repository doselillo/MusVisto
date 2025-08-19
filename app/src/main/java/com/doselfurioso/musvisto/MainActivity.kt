package com.doselfurioso.musvisto

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.doselfurioso.musvisto.ui.theme.MusVistoTheme
import dagger.hilt.android.AndroidEntryPoint
import androidx.activity.viewModels
import com.doselfurioso.musvisto.presentation.GameScreen
import com.doselfurioso.musvisto.presentation.GameViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // This is how we get an instance of our Hilt-powered ViewModel.
    private val viewModel: GameViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            // Here we tell the activity to display our GameScreen
            GameScreen()
        }

    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    MusVistoTheme {
        Greeting("Android")
    }
}