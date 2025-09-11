package com.doselfurioso.musvisto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.doselfurioso.musvisto.logic.AILogic
import com.doselfurioso.musvisto.logic.GameRepository
import com.doselfurioso.musvisto.logic.MusGameLogic
import com.doselfurioso.musvisto.presentation.GameScreen
import com.doselfurioso.musvisto.presentation.GameViewModel
import com.doselfurioso.musvisto.presentation.GesturesScreen
import com.doselfurioso.musvisto.presentation.MainMenuScreen
import com.doselfurioso.musvisto.ui.theme.MusVistoTheme
import kotlin.random.Random

class MainActivity : ComponentActivity() {

    private val gameViewModelFactory by lazy {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(GameViewModel::class.java)) {
                    val random = Random(System.currentTimeMillis())
                    val gameLogic = MusGameLogic(random)
                    val aiLogic = AILogic(gameLogic, random)
                    val gameRepository = GameRepository(applicationContext)

                    @Suppress("UNCHECKED_CAST")
                    return GameViewModel(gameLogic, aiLogic, gameRepository) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MusVistoTheme {
                AppNavigation(factory = gameViewModelFactory)
            }
        }
    }
}

@Composable
fun AppNavigation(factory: ViewModelProvider.Factory) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "main_menu") {
        composable("main_menu") {
            MainMenuScreen(navController = navController)
        }
        composable("game_screen") {
            // Esta línea ahora funcionará porque las dependencias de 'viewModel' están en el proyecto
            GameScreen(gameViewModel = viewModel(factory = factory))
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

