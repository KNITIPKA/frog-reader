package com.example.frogreader.ui.profile

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.frogreader.FrogReaderApp
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.SettingsRepository
import com.example.frogreader.data.StatsRepository
import com.example.frogreader.data.model.ProfileSearchCategory
import com.example.frogreader.data.search.AchievementStringResolver
import com.example.frogreader.data.search.ContextAchievementStringResolver
import com.example.frogreader.data.search.DefaultEnglishAchievementStrings
import com.example.frogreader.data.search.ProfileSearchRepository
import com.example.frogreader.data.search.ProfileSearchResults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Persistence contract for Profile Global Search category preferences, allowing both live
 * DataStore-backed [SettingsRepository] and deterministic JVM unit tests.
 */
interface ProfileSearchPreferences {
    val categoryPreferences: Flow<Pair<List<ProfileSearchCategory>, Set<ProfileSearchCategory>>>
    val dailyGoalMinutes: Flow<Int>
    suspend fun saveCategories(
        order: List<ProfileSearchCategory>,
        hidden: Set<ProfileSearchCategory>,
    )
}

internal class SettingsRepositoryProfileSearchPreferences(
    private val settingsRepository: SettingsRepository,
) : ProfileSearchPreferences {
    override val categoryPreferences: Flow<Pair<List<ProfileSearchCategory>, Set<ProfileSearchCategory>>> =
        settingsRepository.appSettings
            .map { it.profileSearchCategoryOrder to it.profileSearchHiddenCategories }
            .distinctUntilChanged()

    override val dailyGoalMinutes: Flow<Int> =
        settingsRepository.appSettings
            .map { it.dailyGoalMinutes }
            .distinctUntilChanged()

    override suspend fun saveCategories(
        order: List<ProfileSearchCategory>,
        hidden: Set<ProfileSearchCategory>,
    ) {
        settingsRepository.setProfileSearchCategories(order, hidden)
    }
}

@Immutable
data class ProfileSearchUiState(
    val query: String = "",
    val results: ProfileSearchResults = ProfileSearchResults(query = ""),
    val categoryOrder: List<ProfileSearchCategory> = ProfileSearchCategory.DEFAULT_ORDER,
    val hiddenCategories: Set<ProfileSearchCategory> = emptySet(),
    val isCategoryOrderExpanded: Boolean = false,
    val expandedCategories: Set<ProfileSearchCategory> = emptySet(),
) {
    /** Total hits across visible (unhidden) categories only. */
    val visibleResultCount: Int
        get() = results.visibleTotalCount(hiddenCategories)

    val allCategoriesHidden: Boolean
        get() = hiddenCategories.containsAll(ProfileSearchCategory.entries)
}

class ProfileSearchViewModel(
    private val bookRepository: BookRepository,
    private val statsRepository: StatsRepository,
    private val searchRepository: ProfileSearchRepository = ProfileSearchRepository(bookRepository.filesRoot),
    private val preferences: ProfileSearchPreferences? = null,
    private val achievementStrings: AchievementStringResolver = DefaultEnglishAchievementStrings,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _categoryOrder = MutableStateFlow(ProfileSearchCategory.DEFAULT_ORDER)
    private val _hiddenCategories = MutableStateFlow<Set<ProfileSearchCategory>>(emptySet())
    private val _isCategoryOrderExpanded = MutableStateFlow(false)
    private val _expandedCategories = MutableStateFlow<Set<ProfileSearchCategory>>(emptySet())

    private val pendingSaves =
        Channel<Pair<List<ProfileSearchCategory>, Set<ProfileSearchCategory>>>(Channel.CONFLATED)

    private val dailyGoalMinutes: StateFlow<Int> =
        (preferences?.dailyGoalMinutes ?: MutableStateFlow(30))
            .stateIn(viewModelScope, SharingStarted.Eagerly, 30)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val searchResults: StateFlow<ProfileSearchResults> =
        combine(
            _query,
            bookRepository.books,
            bookRepository.readingQueue,
            bookRepository.readingLog,
            combine(statsRepository.stats, dailyGoalMinutes, ::Pair),
        ) { currentQuery, books, queue, log, (stats, goal) ->
            SearchInputs(currentQuery, books, queue, log, stats, goal)
        }
            .mapLatest { input ->
                if (input.query.isBlank()) {
                    ProfileSearchResults(query = input.query)
                } else {
                    try {
                        searchRepository.search(
                            query = input.query,
                            books = input.books,
                            readingQueue = input.queue,
                            readingLog = input.log,
                            stats = input.stats,
                            dailyGoalMinutes = input.goalMinutes,
                            achievementStrings = achievementStrings,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        ProfileSearchResults(query = input.query)
                    }
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = ProfileSearchResults(query = ""),
            )

    val uiState: StateFlow<ProfileSearchUiState> =
        combine(
            _query,
            searchResults,
            _categoryOrder,
            _hiddenCategories,
            combine(_isCategoryOrderExpanded, _expandedCategories, ::Pair),
        ) { currentQuery, results, order, hidden, (orderExpanded, expandedCats) ->
            ProfileSearchUiState(
                query = currentQuery,
                results = results,
                categoryOrder = order,
                hiddenCategories = hidden,
                isCategoryOrderExpanded = orderExpanded,
                expandedCategories = expandedCats,
            )
        }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = ProfileSearchUiState(),
            )

    init {
        preferences?.let { prefs ->
            viewModelScope.launch {
                prefs.categoryPreferences.collect { (savedOrder, savedHidden) ->
                    _categoryOrder.value = ProfileSearchCategory.normalizeOrder(savedOrder)
                    _hiddenCategories.value = savedHidden
                }
            }
            viewModelScope.launch {
                for ((order, hidden) in pendingSaves) {
                    runCatching { prefs.saveCategories(order, hidden) }
                }
            }
        }
    }

    fun setQuery(value: String) {
        if (_query.value == value) return
        _query.value = value
        if (value.isBlank()) {
            _expandedCategories.value = emptySet()
        }
    }

    fun clearQuery() {
        setQuery("")
    }

    fun toggleCategoryOrderCard() {
        _isCategoryOrderExpanded.value = !_isCategoryOrderExpanded.value
    }

    fun dismissCategoryOrderCard() {
        _isCategoryOrderExpanded.value = false
    }

    fun toggleCategoryVisibility(category: ProfileSearchCategory) {
        val current = _hiddenCategories.value
        val updated = if (category in current) current - category else current + category
        _hiddenCategories.value = updated
        enqueueCategorySave(_categoryOrder.value, updated)
    }

    fun moveCategory(fromIndex: Int, toIndex: Int): Boolean {
        val current = _categoryOrder.value
        if (fromIndex !in current.indices || toIndex !in current.indices || fromIndex == toIndex) {
            return false
        }
        val mutable = current.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        mutable.add(toIndex, moved)
        val normalized = ProfileSearchCategory.normalizeOrder(mutable)
        _categoryOrder.value = normalized
        enqueueCategorySave(normalized, _hiddenCategories.value)
        return true
    }

    fun toggleCategoryExpanded(category: ProfileSearchCategory) {
        val current = _expandedCategories.value
        _expandedCategories.value = if (category in current) current - category else current + category
    }

    private fun enqueueCategorySave(
        order: List<ProfileSearchCategory>,
        hidden: Set<ProfileSearchCategory>,
    ) {
        pendingSaves.trySend(order to hidden)
    }

    private data class SearchInputs(
        val query: String,
        val books: List<com.example.frogreader.data.model.Book>,
        val queue: List<com.example.frogreader.data.model.ReadingQueueEntry>,
        val log: List<com.example.frogreader.data.model.ReadingLogEntry>,
        val stats: com.example.frogreader.data.ReadingStats,
        val goalMinutes: Int,
    )

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as FrogReaderApp
                ProfileSearchViewModel(
                    bookRepository = app.bookRepository,
                    statsRepository = app.statsRepository,
                    searchRepository = ProfileSearchRepository(app.bookRepository.filesRoot),
                    preferences = SettingsRepositoryProfileSearchPreferences(app.settingsRepository),
                    achievementStrings = ContextAchievementStringResolver(app),
                )
            }
        }
    }
}
