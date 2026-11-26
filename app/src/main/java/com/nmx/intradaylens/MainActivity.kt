package com.nmx.intradaylens

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nmx.intradaylens.data.Repository
import com.nmx.intradaylens.ui.AppColors
import com.nmx.intradaylens.ui.C
import com.nmx.intradaylens.ui.DetailScreen
import com.nmx.intradaylens.ui.MainViewModel
import com.nmx.intradaylens.ui.ModelScreen
import com.nmx.intradaylens.ui.ResultsScreen
import com.nmx.intradaylens.ui.SettingsScreen
import com.nmx.intradaylens.ui.TodayScreen
import com.nmx.intradaylens.ui.WatchlistScreen
import com.nmx.intradaylens.work.LiveService

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { LiveService.startIfNeeded(this) }
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val tabs = listOf(
            "Today" to Icons.Filled.Home,
            "Results" to Icons.Filled.DateRange,
            "Settings" to Icons.Filled.Settings
        )
        setContent {
            MaterialTheme(colorScheme = AppColors) {
                val st by vm.state.collectAsStateWithLifecycle()
                var tab by rememberSaveable { mutableIntStateOf(0) }
                // "watchlist" / "model" sub-screens opened from Settings
                var sub by rememberSaveable { mutableStateOf<String?>(null) }
                val selected = st.selected
                BackHandler(enabled = selected != null || sub != null) {
                    if (selected != null) vm.select(null) else sub = null
                }
                val title = when {
                    selected != null -> Repository.label(selected.symbol)
                    sub == "watchlist" -> "Watchlist"
                    sub == "model" -> "AI accuracy"
                    else -> "Intraday Lens"
                }
                Scaffold(
                    containerColor = C.Bg,
                    topBar = {
                        TopAppBar(
                            title = { Text(title, fontWeight = FontWeight.Bold) },
                            navigationIcon = {
                                if (selected != null || sub != null) IconButton(onClick = {
                                    if (selected != null) vm.select(null) else sub = null
                                }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            },
                            actions = {
                                IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = C.Bg, titleContentColor = C.Text,
                                actionIconContentColor = C.Text, navigationIconContentColor = C.Text)
                        )
                    },
                    bottomBar = {
                        if (selected == null && sub == null) NavigationBar(containerColor = C.Card) {
                            tabs.forEachIndexed { i, (label, icon) ->
                                NavigationBarItem(
                                    selected = tab == i,
                                    onClick = { tab = i },
                                    icon = { Icon(icon, contentDescription = label) },
                                    label = { Text(label) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = C.Gold, selectedTextColor = C.Gold,
                                        unselectedIconColor = C.Muted, unselectedTextColor = C.Muted, indicatorColor = C.Card2
                                    )
                                )
                            }
                        }
                    }
                ) { pad ->
                    Box(Modifier.padding(pad)) {
                        when {
                            selected != null -> DetailScreen(selected, st, vm)
                            sub == "watchlist" -> WatchlistScreen(st)
                            sub == "model" -> ModelScreen(st)
                            tab == 0 -> TodayScreen(st, vm)
                            tab == 1 -> ResultsScreen(st)
                            else -> SettingsScreen(st, vm, openWatchlist = { sub = "watchlist" }, openModel = { sub = "model" })
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        LiveService.startIfNeeded(this)
    }
}
