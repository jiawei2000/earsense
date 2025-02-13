package com.example.earsense

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import org.slf4j.helpers.Util
import java.io.IOException

class BreathingActivity : AppCompatActivity() {

    val sampleRate = 16000
    val channelConfig = AudioFormat.CHANNEL_IN_MONO
    val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
    val audioSource = MediaRecorder.AudioSource.MIC
    val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    lateinit var audioRecord: AudioRecord
    lateinit var audioManager: AudioManager

    lateinit var waveFormView: WaveFormView

    lateinit var predictionText: TextView

    val breathingModes = arrayOf("Nose Inhaled", "Nose Exhale", "Mouth Inhale", "Mouth Exhale")

    lateinit var currentProfile: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_breathing)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        //Tool bar
        val toolBar: MaterialToolbar = findViewById(R.id.materialToolbar)
        setSupportActionBar(toolBar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        toolBar.setTitleTextColor(ContextCompat.getColor(this, R.color.white))
        supportActionBar?.title = "Breathing Recognition"
        toolBar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // Start Listening Button
        val startListeningButton: Button = findViewById(R.id.buttonStart)
        startListeningButton.setOnClickListener {
            startRecording()
        }

        // Stop Listening Button
        val stopListeningButton: Button = findViewById(R.id.buttonStop)
        stopListeningButton.setOnClickListener {
            stopRecording()
        }

        // Train Model Button
        val trainModelButton: Button = findViewById(R.id.buttonTrainModel)
        trainModelButton.setOnClickListener {
            // Go to BreathingTrainingActivity
            val intent = Intent(this, BreathingTrainingActivity::class.java)
            startActivity(intent)
        }

        // Debug Button
        val debugButton: Button = findViewById(R.id.buttonDebug)
        debugButton.setOnClickListener {
        }

        // WaveFormView
        waveFormView = findViewById(R.id.waveFormView)

        // Prediction Text
        predictionText = findViewById(R.id.textPrediction)

        currentProfile = Utils.getCurrentProfile(this)
    }

    override fun onStop() {
        super.onStop()
        stopRecording()
    }

    fun startRecording() {
        // Start recording in a separate thread
        Thread {
            try {
                // Request permission to record audio
                if (ActivityCompat.checkSelfPermission(
                        this, android.Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 1
                    )
                }

                // Select Bluetooth device
                audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                for (device in audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            audioManager.setCommunicationDevice(device)
                        } else {
                            audioManager.startBluetoothSco()
                        }
                        audioManager.setBluetoothScoOn(true)
                        break
                    }
                }

                audioRecord = AudioRecord(
                    audioSource, sampleRate, channelConfig, audioEncoding, bufferSize
                )
                audioRecord.startRecording()

                val windowSize = sampleRate
                val overlapSize = windowSize / 2
                var bufferOffset = 0
                var runningAudioBuffer = ShortArray(windowSize)

                val knnModel = Utils.loadModelFromFile(currentProfile, filesDir, "breathingModel")

                while (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {

                    val audioData = ShortArray(bufferSize)

                    val readResult = audioRecord.read(audioData, 0, bufferSize)

                    if (readResult > 0) {
                        //Process Audio Data
                        val doubleAudioArray = audioData.map { it.toDouble() }.toDoubleArray()
                        val amplitude = doubleAudioArray.maxOrNull() ?: 0.0
                        waveFormView.addAmplitude(amplitude.toFloat())

                        // Only process if buffer is full
                        if (bufferOffset + readResult < windowSize) {
                            System.arraycopy(
                                audioData,
                                0,
                                runningAudioBuffer,
                                bufferOffset,
                                readResult
                            )
                            bufferOffset += readResult
                            continue
                        }

                        // Apply High Pass Filter
                        val filter = PassFilter(
                            500.toFloat(),
                            sampleRate,
                            PassFilter.PassType.Highpass,
                            1.toFloat()
                        )

                        // Convert runningAudioBuffer to double
                        val doubleRunningAudioArray = runningAudioBuffer.map { it.toDouble() }.toDoubleArray()

                        for (i in doubleRunningAudioArray.indices) {
                            filter.Update(doubleRunningAudioArray[i].toFloat())
                            doubleRunningAudioArray[i] = filter.getValue().toDouble()
                        }

                        if (amplitude < 440) {
                            // If amplitude is too low, skip
                            continue
                        } else {
                            Log.d("AAAAAAAAAAAA", "Amplitude: $amplitude")
                            // Extract segment around peak
                            val segment = extractSegmentAroundPeak(doubleRunningAudioArray)
                            val prediction = knnModel!!.predict(segment)

                            // Update prediction text
                            runOnUiThread {
                                predictionText.text = "Prediction: ${breathingModes[prediction]}"
                            }

                        }

                        // Slide existing buffer by overlap size
                        System.arraycopy(
                            runningAudioBuffer,
                            overlapSize,
                            runningAudioBuffer,
                            0,
                            windowSize - overlapSize
                        )

                        // Reset bufferOffset
                        bufferOffset = overlapSize

                    }

                }
            } catch (e: IOException) {
                Log.d("ERROR in StepTracker", e.toString())
            }
        }.start()
    }

    fun stopRecording() {
        if (this::audioRecord.isInitialized) {
            audioRecord.stop()
            audioRecord.release()
        }
    }

    fun extractSegmentAroundPeak(window: DoubleArray): DoubleArray {
        val segmentDuration = 0.4
        val segmentSize = (segmentDuration * sampleRate).toInt() // Number of samples in 0.4 seconds
        val peakIndex = window.indices.maxByOrNull { window[it].toInt() } ?: 0 // Find peak index

        // Calculate segment start and end
        // extract 0.15 seconds before and 0.25 seconds after the peak
        var start = peakIndex - (0.15 * sampleRate).toInt()
        var end = peakIndex + (0.25 * sampleRate).toInt()

        // Handle out-of-bounds cases
        if (start < 0) {
            end -= start // Extend end
            start = 0
        }
        if (end > window.size) {
            start -= (end - window.size) // Extend start
            end = window.size
        }
        // Extract the segment
        return window.copyOfRange(start.coerceAtLeast(0), end.coerceAtMost(window.size))
    }
}