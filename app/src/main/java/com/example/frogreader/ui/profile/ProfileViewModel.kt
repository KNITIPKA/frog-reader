package com.example.frogreader.ui.profile

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.frogreader.FrogReaderApp
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.SettingsRepository
import com.example.frogreader.data.StatsRepository
import com.example.frogreader.R
import java.io.IOException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class AvatarEditorState(
    val visible: Boolean = false,
    val photo: Bitmap? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: Int? = null,
)

class ProfileViewModel(
    private val settingsRepository: SettingsRepository,
    bookRepository: BookRepository,
    statsRepository: StatsRepository,
    private val contentResolver: ContentResolver,
) : ViewModel() {
    val settings = settingsRepository.appSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    val books = bookRepository.books
    val readingQueue = bookRepository.readingQueue
    val readingLog = bookRepository.readingLog
    val stats = statsRepository.stats
    val nowMillis = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), System.currentTimeMillis())
    val today = nowMillis.map { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocalDate.now())
    val avatar = settingsRepository.appSettings.map { it.profileAvatarBase64 }
        .distinctUntilChanged().map { decodeAvatar(it) }.flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _avatarEditor = MutableStateFlow(AvatarEditorState())
    internal val avatarEditor = _avatarEditor.asStateFlow()
    private var avatarJob: Job? = null

    fun chooseAvatar(uri: Uri) {
        if (_avatarEditor.value.saving) return
        avatarJob?.cancel()
        _avatarEditor.value = AvatarEditorState(visible = true, loading = true)
        avatarJob = viewModelScope.launch {
            try {
                val photo = withContext(Dispatchers.IO) { readAvatarPhoto(contentResolver, uri) }
                _avatarEditor.value = AvatarEditorState(visible = true, photo = photo)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _avatarEditor.value = AvatarEditorState(visible = true, error = R.string.profile_photo_load_error)
            }
        }
    }

    fun dismissAvatarEditor() {
        if (_avatarEditor.value.saving) return
        avatarJob?.cancel()
        _avatarEditor.value = AvatarEditorState()
    }

    internal fun saveAvatar(crop: AvatarCrop) {
        val editor = _avatarEditor.value
        val photo = editor.photo ?: return
        if (editor.saving) return
        _avatarEditor.value = editor.copy(saving = true, error = null)
        avatarJob = viewModelScope.launch {
            try {
                val encoded = withContext(Dispatchers.IO) { encodeAvatar(photo, crop) }
                settingsRepository.updateApp { it.copy(profileAvatarBase64 = encoded) }
                _avatarEditor.value = AvatarEditorState()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _avatarEditor.value = editor.copy(error = R.string.profile_photo_save_error)
            }
        }
    }

    private val pendingNames = Channel<String>(Channel.CONFLATED)
    private val _nameSaveError = MutableStateFlow(false)
    val nameSaveError = _nameSaveError.asStateFlow()

    init {
        // Serialize writes and retain the newest input while a write is pending.
        // The view model owns saving, so dismissing the keyboard cannot cancel it.
        viewModelScope.launch {
            for (name in pendingNames) {
                try {
                    settingsRepository.updateApp { it.copy(profileName = name) }
                } catch (_: IOException) {
                    _nameSaveError.value = true
                }
            }
        }
    }

    fun updateName(name: String) {
        val normalized = limitProfileName(name.trim())
        if (normalized.isNotBlank()) pendingNames.trySend(normalized)
    }

    fun dismissNameSaveError() { _nameSaveError.value = false }

    suspend fun saveDailyGoal(minutes: Int) {
        settingsRepository.updateApp { it.copy(dailyGoalMinutes = readingGoalMinutes(minutes.toFloat())) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as FrogReaderApp
                ProfileViewModel(app.settingsRepository, app.bookRepository, app.statsRepository, app.contentResolver)
            }
        }
    }
}
