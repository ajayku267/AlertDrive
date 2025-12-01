package com.example.drowsydriverapp.utils

import android.content.Context
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Log
import com.example.drowsydriverapp.data.models.AlertLevel
import com.example.drowsydriverapp.data.models.AlertSettings
import kotlinx.coroutines.*

class SoundManager(private val context: Context) {
    companion object {
        private const val TAG = "SoundManager"
    }

    private var mediaPlayer: MediaPlayer? = null
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var _isPlaying = false
    private val isPlaying: Boolean get() = _isPlaying
    private var audioFocusRequest: AudioFocusRequest? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    @Volatile
    private var currentAlertLevel: AlertLevel = AlertLevel.NORMAL
    private var tts: TextToSpeech? = null
    @Volatile
    private var ttsReady = false
    private var currentUsesMediaStream: Boolean = true
    @Volatile
    private var lastAlertSettings: AlertSettings? = null

    init {
        Log.d(TAG, "Initializing SoundManager")
        setupMediaPlayer()
        initTextToSpeech()
    }

    private fun setupMediaPlayer(useMediaStream: Boolean = true) {
        try {
            Log.d(TAG, "Setting up MediaPlayer")
            stopSound() // Ensure clean state
            
            mediaPlayer?.release()
            currentUsesMediaStream = useMediaStream
            mediaPlayer = MediaPlayer().apply {
                val usage = if (useMediaStream) {
                    AudioAttributes.USAGE_MEDIA
                } else {
                    AudioAttributes.USAGE_ALARM
                }
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(usage)
                        .build()
                )
                setOnCompletionListener {
                    Log.d(TAG, "MediaPlayer completed playback")
                    if (!isLooping) {
                        _isPlaying = false
                        abandonAudioFocus()
                    }
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    _isPlaying = false
                    abandonAudioFocus()
                    // Try to recover from the error
                    scope.launch {
                        delay(1000)
                        setupMediaPlayer()
                    }
                    true
                }
                setOnPreparedListener {
                    Log.d(TAG, "MediaPlayer prepared, starting playback")
                    try {
                        if (requestAudioFocus()) {
                            start()
                            _isPlaying = true
                        } else {
                            Log.e(TAG, "Failed to get audio focus before playback")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error starting playback", e)
                        _isPlaying = false
                    }
                }
            }
            Log.d(TAG, "MediaPlayer setup completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up MediaPlayer", e)
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    private fun initTextToSpeech() {
        try {
            tts = TextToSpeech(context) { status ->
                Log.d(TAG, "TextToSpeech init status: $status")
                ttsReady = status == TextToSpeech.SUCCESS
                if (!ttsReady) {
                    Log.e(TAG, "TextToSpeech initialization failed with status $status")
                    tts = null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing TextToSpeech", e)
            tts = null
            ttsReady = false
        }
    }

    private fun requestAudioFocus(): Boolean {
        Log.d(TAG, "Requesting audio focus")
        
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setOnAudioFocusChangeListener { focusChange ->
                    Log.d(TAG, "Audio focus changed: $focusChange")
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            Log.d(TAG, "Audio focus lost, stopping sound")
                            stopSound(resetState = false)
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            Log.d(TAG, "Audio focus ducking")
                            mediaPlayer?.setVolume(0.3f, 0.3f)
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            Log.d(TAG, "Audio focus gained")
                            mediaPlayer?.setVolume(1.0f, 1.0f)
                            // Resume playback if needed for severe/critical alerts
                            if (currentAlertLevel in listOf(AlertLevel.SEVERE, AlertLevel.CRITICAL)) {
                                playAlertSound(currentAlertLevel, lastAlertSettings)
                            }
                        }
                    }
                }
                .build()

            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                { focusChange ->
                    Log.d(TAG, "Audio focus changed (legacy): $focusChange")
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            Log.d(TAG, "Audio focus lost (legacy), stopping sound")
                            stopSound(resetState = false)
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            Log.d(TAG, "Audio focus ducking (legacy)")
                            mediaPlayer?.setVolume(0.3f, 0.3f)
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            Log.d(TAG, "Audio focus gained (legacy)")
                            mediaPlayer?.setVolume(1.0f, 1.0f)
                            // Resume playback if needed for severe/critical alerts
                            if (currentAlertLevel in listOf(AlertLevel.SEVERE, AlertLevel.CRITICAL)) {
                                playAlertSound(currentAlertLevel, lastAlertSettings)
                            }
                        }
                    }
                },
                AudioManager.STREAM_ALARM,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
        
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.d(TAG, "Audio focus request result: $granted")
        return granted
    }

    private fun abandonAudioFocus() {
        Log.d(TAG, "Abandoning audio focus")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { 
                audioManager.abandonAudioFocusRequest(it)
                audioFocusRequest = null
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    fun playAlertSound(alertLevel: AlertLevel) {
        playAlertSound(alertLevel, null)
    }

    fun playAlertSound(alertLevel: AlertLevel, settings: AlertSettings?) {
        Log.d(TAG, "Playing alert sound for level: $alertLevel")
        
        // Don't play the same alert level again unless it's NORMAL (which stops sound)
        if (alertLevel == currentAlertLevel && alertLevel != AlertLevel.NORMAL) {
            Log.d(TAG, "Skipping alert sound - same level already playing")
            return
        }
        
        currentAlertLevel = alertLevel
        scope.launch {
            val effectiveSettings = settings ?: lastAlertSettings ?: AlertSettings()
            lastAlertSettings = effectiveSettings
            when (alertLevel) {
                AlertLevel.WARNING -> playWarningSound(effectiveSettings)
                AlertLevel.SEVERE -> playSevereSound(effectiveSettings)
                AlertLevel.CRITICAL -> playCriticalSound(effectiveSettings)
                else -> {
                    Log.d(TAG, "Normal alert level, stopping sound")
                    stopSound()
                }
            }
        }
    }

    private fun playWarningSound(settings: AlertSettings) {
        Log.d(TAG, "Attempting to play warning sound")
        if (settings.soundEnabled) {
            val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            Log.d(TAG, "Using notification URI: $notification")
            playSound(notification, false, settings.routeToBluetoothMedia)
        }
        if (settings.vibrationEnabled) {
            vibrateDevice(500)
        }
        if (settings.voiceEnabled) {
            speakAlert("Warning: You appear drowsy", settings.languageCode)
        }
    }

    private fun playSevereSound(settings: AlertSettings) {
        Log.d(TAG, "Attempting to play severe sound")
        if (settings.soundEnabled) {
            val alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            Log.d(TAG, "Using alarm URI: $alarm")
            playSound(alarm, true, settings.routeToBluetoothMedia)
        }
        if (settings.vibrationEnabled) {
            vibrateDevice(1000)
        }
        if (settings.voiceEnabled) {
            speakAlert("Severe drowsiness detected. Please take a break.", settings.languageCode)
        }
    }

    private fun playCriticalSound(settings: AlertSettings) {
        Log.d(TAG, "Attempting to play critical sound")
        if (settings.soundEnabled) {
            val alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            Log.d(TAG, "Using alarm URI: $alarm")
            playSound(alarm, true, settings.routeToBluetoothMedia)
        }
        if (settings.vibrationEnabled) {
            vibratePattern()
        }
        if (settings.voiceEnabled) {
            speakAlert("Critical alert. Pull over safely now.", settings.languageCode)
        }
    }

    private fun playSound(uri: android.net.Uri, loop: Boolean, routeToBluetoothMedia: Boolean) {
        try {
            Log.d(TAG, "Preparing to play sound from URI: $uri (loop: $loop)")
            // Stop any existing playback but keep current alert state so dedup logic works.
            stopSound(resetState = false)
            
            if (mediaPlayer == null) {
                Log.d(TAG, "MediaPlayer was null, setting up new instance")
                setupMediaPlayer(routeToBluetoothMedia)
            } else if (currentUsesMediaStream != routeToBluetoothMedia) {
                Log.d(TAG, "Recreating MediaPlayer for new audio route")
                setupMediaPlayer(routeToBluetoothMedia)
            }
            
            mediaPlayer?.apply {
                reset()
                Log.d(TAG, "Setting data source")
                setDataSource(context, uri)
                isLooping = loop
                setVolume(1.0f, 1.0f)
                Log.d(TAG, "Starting async preparation")
                prepareAsync()
            } ?: run {
                Log.e(TAG, "MediaPlayer is still null after setup attempt")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing sound", e)
            _isPlaying = false
            abandonAudioFocus()
            // Try to recover
            scope.launch {
                delay(1000)
                setupMediaPlayer()
            }
        }
    }

    private fun speakAlert(text: String, languageCode: String) {
        try {
            val engine = tts?.takeIf { ttsReady } ?: return
            val locale = java.util.Locale.forLanguageTag(languageCode)
            engine.language = locale
            engine.speak(text, TextToSpeech.QUEUE_ADD, null, "drowsy_alert")
        } catch (e: Exception) {
            Log.e(TAG, "Error speaking alert text", e)
        }
    }

    private fun vibrateDevice(duration: Long) {
        try {
            Log.d(TAG, "Vibrating device for $duration ms")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error vibrating device", e)
        }
    }

    private fun vibratePattern() {
        try {
            Log.d(TAG, "Starting vibration pattern")
            val pattern = longArrayOf(0, 500, 200, 500, 200, 500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in vibration pattern", e)
        }
    }

    fun stopSound(resetState: Boolean = true) {
        Log.d(TAG, "Stopping sound")
        try {
            mediaPlayer?.apply {
                if (isPlaying) {
                    stop()
                }
                reset()
            }
            vibrator.cancel()
            _isPlaying = false
            abandonAudioFocus()
            if (resetState) {
                currentAlertLevel = AlertLevel.NORMAL
                lastAlertSettings = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping sound", e)
            // Try to recover
            mediaPlayer?.release()
            mediaPlayer = null
            setupMediaPlayer()
        }
    }

    fun release() {
        Log.d(TAG, "Releasing SoundManager resources")
        try {
            scope.cancel()
            stopSound()
            mediaPlayer?.release()
            mediaPlayer = null
            vibrator.cancel()
            _isPlaying = false
            abandonAudioFocus()
            tts?.shutdown()
            tts = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing SoundManager", e)
        }
    }
}
