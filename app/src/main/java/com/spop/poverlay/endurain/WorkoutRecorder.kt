package com.spop.poverlay.endurain

import com.spop.poverlay.ConfigurationRepository
import com.spop.poverlay.sensor.heartrate.HeartRateManager
import com.spop.poverlay.sensor.interfaces.SensorInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class UploadStatus { IDLE, RECORDING, UPLOADING, SUCCESS, ERROR }

class WorkoutRecorder(
    private val sensorInterface: SensorInterface,
    private val configRepository: ConfigurationRepository,
    private val scope: CoroutineScope
) {
    private val records = mutableListOf<FitWriter.SensorRecord>()
    private var samplingJob: Job? = null

    // Latest sensor values — kept up-to-date by collector coroutines
    @Volatile private var latestPower    = 0f
    @Volatile private var latestCadence = 0f
    @Volatile private var latestSpeed   = 0f  // mph (raw from Peloton interface)
    @Volatile private var latestHr      = 0

    private val _uploadStatus = MutableStateFlow(UploadStatus.IDLE)
    val uploadStatus: StateFlow<UploadStatus> = _uploadStatus.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    /** Call once after creation to keep sensor values current */
    fun startSensorCollection() {
        scope.launch(Dispatchers.IO) { sensorInterface.power.collect   { latestPower    = it } }
        scope.launch(Dispatchers.IO) { sensorInterface.cadence.collect { latestCadence = it } }
        scope.launch(Dispatchers.IO) { sensorInterface.speed.collect   { latestSpeed   = it } }
        scope.launch(Dispatchers.IO) { HeartRateManager.heartRate.collect { latestHr  = it ?: 0 } }
    }

    /** Wire movement / session-reset flows from OverlaySensorViewModel */
    fun observeSession(isMoving: Flow<Boolean>, sessionReset: Flow<Unit>) {
        scope.launch {
            isMoving.collect { moving ->
                if (moving && !_isRecording.value) startRecording()
            }
        }
        scope.launch {
            sessionReset.collect {
                if (_isRecording.value) stopAndUpload()
            }
        }
    }

    fun startRecording() {
        if (_isRecording.value) return
        synchronized(records) { records.clear() }
        _isRecording.value  = true
        _uploadStatus.value = UploadStatus.RECORDING
        Timber.i("WorkoutRecorder: recording started")

        samplingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1_000L)
                synchronized(records) {
                    records.add(FitWriter.SensorRecord(
                        timestampUnixSec = System.currentTimeMillis() / 1000L,
                        powerWatts       = latestPower.toInt(),
                        cadenceRpm       = latestCadence.toInt(),
                        speedMps         = latestSpeed * 0.44704f,  // mph → m/s
                        heartRate        = latestHr
                    ))
                }
            }
        }
    }

    fun stopRecording() {
        samplingJob?.cancel()
        samplingJob      = null
        _isRecording.value = false
        Timber.i("WorkoutRecorder: stopped – ${records.size} records collected")
    }

    fun stopAndUpload() {
        stopRecording()

        val host = configRepository.endurainHost.value.trimEnd('/')
        val user = configRepository.endurainUsername.value
        val pass = configRepository.endurainPassword.value

        if (host.isBlank() || user.isBlank() || pass.isBlank()) {
            Timber.w("WorkoutRecorder: Endurain not configured – skipping upload")
            _uploadStatus.value = UploadStatus.IDLE
            return
        }

        val snapshot = synchronized(records) { records.toList() }
        if (snapshot.isEmpty()) {
            Timber.w("WorkoutRecorder: no records to upload")
            _uploadStatus.value = UploadStatus.IDLE
            return
        }

        scope.launch {
            _uploadStatus.value = UploadStatus.UPLOADING
            try {
                val fitBytes  = FitWriter.buildFitFile(snapshot)
                val client    = EndurainClient(host)
                val token     = client.login(user, pass)
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val success   = client.uploadActivity(token, fitBytes, "grupetto_$timestamp.fit")
                _uploadStatus.value = if (success) UploadStatus.SUCCESS else UploadStatus.ERROR
                Timber.i("WorkoutRecorder: upload ${if (success) "succeeded" else "failed"}")
            } catch (e: Exception) {
                Timber.e(e, "WorkoutRecorder: upload error")
                _uploadStatus.value = UploadStatus.ERROR
            }
        }
    }

    fun resetStatus() {
        if (_uploadStatus.value == UploadStatus.SUCCESS ||
            _uploadStatus.value == UploadStatus.ERROR) {
            _uploadStatus.value = UploadStatus.IDLE
        }
    }
}
