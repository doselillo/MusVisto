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
import com.doselfurioso.musvisto.logic.AIProfile
import com.doselfurioso.musvisto.logic.AndroidGameLogger
import com.doselfurioso.musvisto.logic.GameRepository
import com.doselfurioso.musvisto.logic.MusGameLogic
import com.doselfurioso.musvisto.presentation.CharacterSetupScreen
import com.doselfurioso.musvisto.presentation.GameScreen
import com.doselfurioso.musvisto.presentation.GameViewModel
import com.doselfurioso.musvisto.presentation.GesturesScreen
import com.doselfurioso.musvisto.presentation.MainMenuScreen
import com.doselfurioso.musvisto.presentation.MainMenuViewModel
import com.doselfurioso.musvisto.presentation.OptionsScreen
import com.doselfurioso.musvisto.ui.theme.MusVistoTheme
import kotlin.random.Random

class MainActivity : ComponentActivity() {

    private val gameRepository by lazy { GameRepository(applicationContext) }

    private val mainMenuViewModelFactory by lazy {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(MainMenuViewModel::class.java)) {
                    @Suppress("UNCHECKED_CAST")
                    return MainMenuViewModel(gameRepository) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class")
            }
        }
    }

    private val gameViewModelFactory by lazy {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(GameViewModel::class.java)) {
                    val random = Random(System.currentTimeMillis())
                    val gameLogic = MusGameLogic(random, AndroidGameLogger)
                    // #34: factory de AILogic por perfil. Todas las IA comparten
                    // el mismo `random` (interleave determinista por orden de turno).
                    val aiLogicFactory: (AIProfile) -> AILogic = { profile ->
                        AILogic(gameLogic, random, profile)
                    }
                    val gameRepository = GameRepository(applicationContext)

                    @Suppress("UNCHECKED_CAST")
                    return GameViewModel(gameLogic, aiLogicFactory, gameRepository) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MusVistoTheme {
                AppNavigation(factory = gameViewModelFactory,
                    mainMenuFactory = mainMenuViewModelFactory)
            }
        }
    }
}

@Composable
fun AppNavigation(factory: ViewModelProvider.Factory,  mainMenuFactory: ViewModelProvider.Factory) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "main_menu") {
        composable("main_menu") {
            MainMenuScreen(
                navController = navController,
                viewModel = viewModel(factory = mainMenuFactory)
            )
        }
        composable("game_screen") {
            // Esta línea ahora funcionará porque las dependencias de 'viewModel' están en el proyecto
            GameScreen(gameViewModel = viewModel(factory = factory),
                navController = navController,
            )
        }
        composable("character_setup_screen") {
            CharacterSetupScreen(
                navController = navController,
                viewModel = viewModel(factory = mainMenuFactory)
            )
        }
        composable("gestures_screen") {
            GesturesScreen(navController = navController)
        }
        composable("options_screen") {
            // NOTA: esto crea una instancia de MainMenuViewModel DISTINTA de la
            // del menú (cada destino tiene su ViewModelStoreOwner). Hoy es inocuo
            // (Options relee settings del repo y el menú no los muestra). Si en el
            // futuro el menú refleja un ajuste editable aquí, compartir la misma
            // instancia o que el menú relea settings al volver (ver compose-ui-reviewer).
            OptionsScreen(
                navController = navController,
                viewModel = viewModel(factory = mainMenuFactory)
            )
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

