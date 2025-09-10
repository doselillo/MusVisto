package com.doselfurioso.musvisto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.doselfurioso.musvisto.ui.theme.MusVistoTheme
import dagger.hilt.android.AndroidEntryPoint
import androidx.activity.viewModels
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.doselfurioso.musvisto.presentation.GameScreen
import com.doselfurioso.musvisto.presentation.GameViewModel
import com.doselfurioso.musvisto.presentation.GesturesScreen
import com.doselfurioso.musvisto.presentation.MainMenuScreen

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // This is how we get an instance of our Hilt-powered ViewModel.
    private val viewModel: GameViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MusVistoTheme {
                AppNavigation()
            }
        }

    }
}


@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "main_menu") {
        composable("main_menu") {
            MainMenuScreen(navController = navController)
        }
        composable("game_screen") {
            GameScreen() // Hilt se encarga de proveer el ViewModel aquí
        }
        composable("gestures_screen") {
            GesturesScreen(navController = navController)
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

