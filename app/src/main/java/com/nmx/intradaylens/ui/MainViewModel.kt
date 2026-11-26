package com.nmx.intradaylens.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nmx.intradaylens.data.DayStep
import com.nmx.intradaylens.data.Headline
import com.nmx.intradaylens.data.JournalPick
import com.nmx.intradaylens.data.RefreshMode
import com.nmx.intradaylens.data.Repository
import com.nmx.intradaylens.data.Settings
import com.nmx.intradaylens.data.Snapshot
import com.nmx.intradaylens.engine.Pick
import com.nmx.intradaylens.work.Alerts
import com.nmx.intradaylens.work.LiveService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class UiState(
    val loading: Boolean = false,
    val progress: Pair<Int, Int>? = null,
    val snapshot: Snapshot? = null,
    val error: String? = null,
    val journal: List<JournalPick> = emptyList(),
    val settings: Settings = Settings(Settings.DEFAULT_UNIVERSE),
    val selected: Pick? = null,
    val news: List<Headline>? = null
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository.get(app)
    private val _state = MutableStateFlow(UiState(settings = repo.store.settings(), journal = repo.store.journal(), snapshot = repo.lastSnapshot))
    val state: StateFlow<UiState> = _state
    private var refreshJob: Job? = null
    private var newsJob: Job? = null

    init {
        refresh()
        // While the app is open during market hours, refresh every 60 seconds (fast "live" mode)
        viewModelScope.launch {
            while (isActive) {
                delay(60_000L)
                val step = DayStep.now()
                if ((step == DayStep.ENTRY || step == DayStep.MANAGE || step == DayStep.EXIT) && !_state.value.loading)
                    refresh(mode = RefreshMode.LIVE, quiet = true)
            }
        }
    }

    fun refresh(force: Boolean = false, mode: RefreshMode = RefreshMode.NORMAL, quiet: Boolean = false) {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            if (!quiet) _state.update { it.copy(loading = true, error = null, progress = null) }
            try {
                val (snap, changes) = repo.refresh(
                    progress = { d, t -> if (!quiet) _state.update { s -> s.copy(progress = d to t) } },
                    forceFull = force, mode = mode
                )
                Alerts.process(getApplication<Application>(), snap, changes)
                _state.update { s ->
                    s.copy(
                        loading = false, snapshot = snap, journal = repo.store.journal(), progress = null,
                        selected = s.selected?.let { sel ->
                            (snap.picks + snap.indexPicks).firstOrNull { it.symbol == sel.symbol && it.date == sel.date } ?: sel
                        },
                        error = when {
                            snap.picks.isEmpty() && snap.failed.size > 10 -> "Couldn't download prices. Check your internet and tap Refresh."
                            snap.failed.isNotEmpty() -> "No data for: ${snap.failed.joinToString()} (check symbol names in Settings)"
                            else -> null
                        }
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, progress = null, error = "Refresh failed: ${e.message}") }
            }
        }
    }

    fun select(p: Pick?) {
        _state.update { it.copy(selected = p, news = null) }
        newsJob?.cancel()
        if (p != null) newsJob = viewModelScope.launch {
            val n = repo.news(p.symbol)
            _state.update { if (it.selected?.symbol == p.symbol) it.copy(news = n) else it }
        }
    }

    fun session(p: Pick) = repo.sessionFor(p.symbol, p.date)
    fun newsTone(h: List<Headline>) = repo.newsTone(h)
    fun stockEvent(h: List<Headline>) = repo.stockEvent(h)
    fun model(p: Pick) = repo.model(p.symbol)
    fun isTaken(p: Pick) = _state.value.journal.any { it.date == p.date.toString() && it.symbol == p.symbol && it.taken }

    fun toggleTaken(p: Pick) {
        repo.toggleTaken(p)
        _state.update { it.copy(journal = repo.store.journal()) }
    }

    fun saveSettings(s: Settings) {
        val old = _state.value.settings
        val universeChanged = s.universe != old.universe
        val pointsChanged = s.indexRiskPts != old.indexRiskPts || s.indexTargetPts != old.indexTargetPts
        repo.store.saveSettings(s)
        _state.update { it.copy(settings = s) }
        if (s.liveAlerts && !old.liveAlerts) LiveService.startIfNeeded(getApplication<Application>())
        if (!s.liveAlerts && old.liveAlerts) LiveService.stop(getApplication<Application>())
        when {
            universeChanged -> { repo.store.modelDay = null; repo.store.indexModelDay = null; refresh(force = true) }
            pointsChanged -> { repo.store.indexModelDay = null; refresh() }
            else -> refresh(quiet = true)
        }
    }

    fun clearJournal() {
        repo.store.clearJournal()
        _state.update { it.copy(journal = emptyList()) }
    }
}
