package com.boss.guitartrainer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.boss.guitartrainer.dsp.Direction
import com.boss.guitartrainer.ui.AppState
import com.boss.guitartrainer.ui.CalibrationScreen
import com.boss.guitartrainer.ui.ChordDetailScreen
import com.boss.guitartrainer.ui.ChordLibraryScreen
import com.boss.guitartrainer.ui.GameScreen
import com.boss.guitartrainer.ui.GuitarTheme
import com.boss.guitartrainer.ui.HomeScreen
import com.boss.guitartrainer.ui.MetronomeScreen
import com.boss.guitartrainer.ui.PracticeSetupScreen
import com.boss.guitartrainer.ui.ResultsScreen
import com.boss.guitartrainer.ui.SettingsScreen
import com.boss.guitartrainer.ui.SongDetailScreen
import com.boss.guitartrainer.ui.SongsScreen
import com.boss.guitartrainer.ui.StatsScreen
import com.boss.guitartrainer.ui.TunerScreen

class MainActivity : ComponentActivity() {
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                    if (shared != null && nav.currentDestination?.route != "songs") nav.navigate("songs")
                }
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    NavHost(nav, startDestination = "home") {
                        composable("home") { HomeScreen(nav) }
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
                        composable("stats") { StatsScreen(nav) }
                        composable("settings") { SettingsScreen(nav) }
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
