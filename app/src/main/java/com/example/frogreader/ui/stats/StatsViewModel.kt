package com.example.frogreader.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.frogreader.FrogReaderApp
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.ReadingStats
import com.example.frogreader.data.SettingsRepository
import com.example.frogreader.data.StatsRepository
import com.example.frogreader.data.model.Book
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class StatsViewModel(
    statsRepository: StatsRepository,
    private val settingsRepository: SettingsRepository,
    bookRepository: BookRepository,
) : ViewModel() {

    val stats: StateFlow<ReadingStats> = statsRepository.stats

    val appSettings: StateFlow<AppSettings> = settingsRepository.appSettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val books: StateFlow<List<Book>> = bookRepository.books

    fun setDailyGoal(minutes: Int) {
        viewModelScope.launch {
            settingsRepository.updateApp { it.copy(dailyGoalMinutes = minutes) }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as FrogReaderApp
                StatsViewModel(app.statsRepository, app.settingsRepository, app.bookRepository)
            }
        }
    }
}
