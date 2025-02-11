package com.example.earsense

import android.content.Context
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
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import java.io.IOException

class BreathingActivity : AppCompatActivity() {

    val sampleRate = 16000
    val channelConfig = AudioFormat.CHANNEL_IN_MONO
    val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
    val audioSource = MediaRecorder.AudioSource.MIC
    val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate, channelConfig, audioEncoding
    )

    lateinit var audioRecord: AudioRecord
    lateinit var audioManager: AudioManager

    lateinit var waveFormView: WaveFormView

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

        // WaveFormView
        waveFormView = findViewById(R.id.waveFormView)
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

                while (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val audioData = ShortArray(bufferSize)

                    val readResult = audioRecord.read(audioData, 0, audioData.size)

                    if (readResult > 0) {
                        //Process Audio Data
                        val doubleAudioArray = audioData.map { it.toDouble() }.toDoubleArray()
                        val amplitude = doubleAudioArray.maxOrNull() ?: 0.0
                        waveFormView.addAmplitude(amplitude.toFloat())
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
}