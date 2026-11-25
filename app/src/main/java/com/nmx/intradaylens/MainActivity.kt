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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nmx.intradaylens.ui.AppColors
import com.nmx.intradaylens.ui.C
import com.nmx.intradaylens.ui.DetailScreen
import com.nmx.intradaylens.ui.JournalScreen
import com.nmx.intradaylens.ui.MainViewModel
import com.nmx.intradaylens.ui.ModelScreen
import com.nmx.intradaylens.ui.SettingsScreen
import com.nmx.intradaylens.ui.TodayScreen
import com.nmx.intradaylens.ui.WatchlistScreen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val tabs = listOf(
            "Today" to Icons.Filled.Home,
            "Watchlist" to Icons.Filled.Star,
            "Journal" to Icons.Filled.DateRange,
            "AI Model" to Icons.Filled.Build,
            "Settings" to Icons.Filled.Settings
        )
        setContent {
            MaterialTheme(colorScheme = AppColors) {
                val st by vm.state.collectAsStateWithLifecycle()
                var tab by rememberSaveable { mutableIntStateOf(0) }
                val selected = st.selected
                BackHandler(enabled = selected != null) { vm.select(null) }
                Scaffold(
                    containerColor = C.Bg,
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(if (selected != null) com.nmx.intradaylens.data.Repository.label(selected.symbol) else "Intraday Lens", fontWeight = FontWeight.Bold)
                            },
                            navigationIcon = {
                                if (selected != null) IconButton(onClick = { vm.select(null) }) {
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
                        if (selected == null) NavigationBar(containerColor = C.Card) {
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
                        if (selected != null) DetailScreen(selected, st, vm)
                        else when (tab) {
                            0 -> TodayScreen(st, vm)
                            1 -> WatchlistScreen(st)
                            2 -> JournalScreen(st)
                            3 -> ModelScreen(st)
                            else -> SettingsScreen(st, vm)
                        }
                    }
                }
            }
        }
    }
}
