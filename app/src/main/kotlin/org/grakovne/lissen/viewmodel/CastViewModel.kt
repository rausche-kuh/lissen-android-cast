package org.grakovne.lissen.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.grakovne.lissen.cast.CastDevice
import org.grakovne.lissen.cast.CastSession
import org.grakovne.lissen.persistence.preferences.LibraryPreferences
import javax.inject.Inject

@HiltViewModel
class CastViewModel
  @Inject
  constructor(
    private val castSession: CastSession,
    libraryPreferences: LibraryPreferences,
  ) : ViewModel() {
    val active: StateFlow<CastDevice?> = castSession.device

    val connecting: StateFlow<Boolean> = castSession.connecting

    /** Cast devices stream from the server, so offline mode has nothing to offer them. */
    val available: StateFlow<Boolean> =
      libraryPreferences.forceCacheFlow
        .map { it.not() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), libraryPreferences.isForceCache().not())

    /** Null until the first scan has finished. */
    private val _devices = MutableStateFlow<List<CastDevice>?>(null)
    val devices: StateFlow<List<CastDevice>?> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private var scanJob: Job? = null

    fun startScan() {
      if (scanJob?.isActive == true) return

      _devices.value = null
      _scanning.value = true
      scanJob =
        viewModelScope
          .launch { castSession.scan().collect { _devices.value = it } }
          .also { job -> job.invokeOnCompletion { _scanning.value = false } }
    }

    fun stopScan() {
      scanJob?.cancel()
      scanJob = null
    }

    fun connect(device: CastDevice) = castSession.connect(device)

    fun disconnect() = castSession.disconnect()
  }
