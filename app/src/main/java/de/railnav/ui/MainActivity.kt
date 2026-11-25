package de.railnav.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.DepartureBoard
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import de.railnav.notify.TripNotifications

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TripNotifications.createChannels(this)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent { RailNavTheme { App(vm, ::requestNotifications) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        i ?: return
        i.getStringExtra("plan")?.let { vm.openSaved(it, i.getBooleanExtra("alternatives", false)); return }
        vm.importLink(i)
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !TripNotifications.canPost(this)) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
fun RailNavTheme(content: @Composable () -> Unit) {
    val red = Color(0xFFC0102A)
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFFFF8A95), secondary = Color(0xFF9FC9FF), tertiary = Color(0xFF80CBC4))
    else lightColorScheme(primary = red, secondary = Color(0xFF1565C0), tertiary = Color(0xFF00695C))
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun App(vm: AppViewModel, requestNotifications: () -> Unit) {
    BackHandler(enabled = vm.stack.size > 1) { vm.back() }
    val top = vm.screen
    val showBar = top == Screen.Search || top == Screen.Saved || top == Screen.Settings || top == Screen.Board
    Scaffold(bottomBar = {
        if (showBar) NavigationBar {
            NavigationBarItem(top == Screen.Search, { vm.tab(Screen.Search) }, { Icon(Icons.Filled.Search, null) }, label = { Text("Поиск") })
            NavigationBarItem(top == Screen.Board, { vm.tab(Screen.Board) }, { Icon(Icons.Filled.DepartureBoard, null) }, label = { Text("Табло") })
            NavigationBarItem(top == Screen.Saved, { vm.tab(Screen.Saved) }, { Icon(Icons.Filled.Bookmarks, null) }, label = { Text("Поездки") })
            NavigationBarItem(top == Screen.Settings, { vm.tab(Screen.Settings) }, { Icon(Icons.Filled.Settings, null) }, label = { Text("Настройки") })
        }
    }) { pad ->
        Box(Modifier.fillMaxSize().padding(if (showBar) pad else androidx.compose.foundation.layout.PaddingValues())) {
            when (top) {
                Screen.Search -> SearchScreen(vm)
                Screen.Results -> ResultsScreen(vm)
                Screen.Detail -> DetailScreen(vm, requestNotifications)
                Screen.Alternatives -> AlternativesScreen(vm)
                Screen.Saved -> SavedScreen(vm)
                Screen.Settings -> SettingsScreen(vm)
                Screen.Board -> BoardScreen(vm)
                Screen.OfflineData -> OfflineDataScreen(vm)
                Screen.Trip -> TripScreen(vm)
            }
        }
    }
}
