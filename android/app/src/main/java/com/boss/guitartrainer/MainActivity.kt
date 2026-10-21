package com.boss.guitartrainer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.boss.guitartrainer.dsp.Direction
import com.boss.guitartrainer.ui.AppState
import com.boss.guitartrainer.ui.BottomTabs
import com.boss.guitartrainer.ui.CalibrationScreen
import com.boss.guitartrainer.ui.ChordDetailScreen
import com.boss.guitartrainer.ui.ChordLibraryScreen
import com.boss.guitartrainer.ui.GameScreen
import com.boss.guitartrainer.ui.GuitarTheme
import com.boss.guitartrainer.ui.HomeScreen
import com.boss.guitartrainer.ui.MetronomeScreen
import com.boss.guitartrainer.ui.PracticeHubScreen
import com.boss.guitartrainer.ui.PracticeSetupScreen
import com.boss.guitartrainer.ui.ResultsScreen
import com.boss.guitartrainer.ui.SettingsScreen
import com.boss.guitartrainer.ui.SongDetailScreen
import com.boss.guitartrainer.ui.SongsScreen
import com.boss.guitartrainer.ui.StatsScreen
import com.boss.guitartrainer.ui.TAB_ROUTES
import com.boss.guitartrainer.ui.TunerScreen
import com.boss.guitartrainer.ui.morningPaper
import com.boss.guitartrainer.ui.navigateTab

class MainActivity : ComponentActivity() {
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @OptIn(ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light, see-through system bars over the cream "morning paper" background.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.argb(0x80, 0x1b, 0x1b, 0x1b)),
        )
        Direction.init(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
        handleShare(intent)
        setContent {
            GuitarTheme {
                val nav = rememberNavController()
                val shared by AppState.sharedLink.collectAsState()
                LaunchedEffect(shared) {
                    if (shared != null && nav.currentDestination?.route != "songs") nav.navigateTab("songs")
                }
                val entry by nav.currentBackStackEntryAsState()
                val route = entry?.destination?.route
                val showTabs = route in TAB_ROUTES && !WindowInsets.isImeVisible
                Box(Modifier.fillMaxSize().morningPaper()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        NavHost(
                            nav, startDestination = "home", modifier = Modifier.weight(1f).fillMaxWidth(),
                            enterTransition = { fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 28 } },
                            exitTransition = { fadeOut(tween(160)) },
                            popEnterTransition = { fadeIn(tween(220)) },
                            popExitTransition = { fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 28 } },
                        ) {
                            composable("home") { HomeScreen(nav) }
                            composable("hub") { PracticeHubScreen(nav) }
                            composable("me") { StatsScreen(nav, asTab = true) }
                            composable("tuner") { TunerScreen(nav) }
                            composable("metronome") { MetronomeScreen(nav) }
                            composable("chords") { ChordLibraryScreen(nav) }
                            composable("chord/{name}", arguments = listOf(navArgument("name") { type = NavType.StringType })) {
                                ChordDetailScreen(nav, it.arguments?.getString("name") ?: "C")
                            }
                            composable("practice/{mode}", arguments = listOf(navArgument("mode") { type = NavType.StringType })) {
                                PracticeSetupScreen(nav, it.arguments?.getString("mode") ?: "strum")
                            }
                            composable("game") { GameScreen(nav) }
                            composable("results") { ResultsScreen(nav) }
                            composable("calibration") { CalibrationScreen(nav) }
                            composable("songs") { SongsScreen(nav) }
                            composable("song/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                                SongDetailScreen(nav, it.arguments?.getString("id") ?: "")
                            }
                            composable("stats") { StatsScreen(nav, asTab = false) }
                            composable("settings") { SettingsScreen(nav) }
                        }
                        AnimatedVisibility(showTabs, enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
                            BottomTabs(route) { nav.navigateTab(it) }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            AppState.extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT))?.let { AppState.sharedLink.value = it }
        }
    }
}
