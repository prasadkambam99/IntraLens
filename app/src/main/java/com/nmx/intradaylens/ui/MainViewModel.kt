package com.nmx.intradaylens.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nmx.intradaylens.data.Headline
import com.nmx.intradaylens.data.JournalPick
import com.nmx.intradaylens.data.Phase
import com.nmx.intradaylens.data.Repository
import com.nmx.intradaylens.data.Settings
import com.nmx.intradaylens.data.Snapshot
import com.nmx.intradaylens.engine.Pick
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
    private val _state = MutableStateFlow(UiState(settings = repo.store.settings(), journal = repo.store.journal()))
    val state: StateFlow<UiState> = _state
    private var refreshJob: Job? = null
    private var newsJob: Job? = null

    init {
        refresh()
        // Auto-refresh every 3 minutes while the market is live and the app is open
        viewModelScope.launch {
            while (isActive) {
                delay(3 * 60_000L)
                if (Repository.phaseNow() == Phase.LIVE && !_state.value.loading) refresh()
            }
        }
    }

    fun refresh(force: Boolean = false) {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, progress = null) }
            try {
                val (snap, _) = repo.refresh(progress = { d, t -> _state.update { s -> s.copy(progress = d to t) } }, forceFull = force)
                _state.update { s ->
                    s.copy(
                        loading = false, snapshot = snap, journal = repo.store.journal(), progress = null,
                        selected = s.selected?.let { sel -> snap.picks.firstOrNull { it.symbol == sel.symbol } ?: sel },
                        error = when {
                            snap.picks.isEmpty() && snap.failed.size > snap.picks.size -> "Could not download prices. Check internet and pull to refresh."
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
    fun model() = repo.model()

    fun saveSettings(s: Settings) {
        val old = _state.value.settings
        val universeChanged = s.universe != old.universe
        val pointsChanged = s.indexRiskPts != old.indexRiskPts || s.indexTargetPts != old.indexTargetPts
        repo.store.saveSettings(s)
        _state.update { it.copy(settings = s) }
        if (universeChanged) { repo.store.modelDay = null; repo.store.indexModelDay = null; refresh(force = true) }
        else if (pointsChanged) { repo.store.indexModelDay = null; refresh() }
    }

    fun clearJournal() {
        repo.store.clearJournal()
        _state.update { it.copy(journal = emptyList()) }
    }
}
