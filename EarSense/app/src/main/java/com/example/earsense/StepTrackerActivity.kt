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
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import java.io.IOException

class StepTrackerActivity : AppCompatActivity() {

    val sampleRate = 16000
    val channelConfig = AudioFormat.CHANNEL_IN_MONO
    val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
    val audioSource = MediaRecorder.AudioSource.MIC
    val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        channelConfig,
        audioEncoding
    )

    lateinit var audioRecord: AudioRecord
    lateinit var audioManager: AudioManager

    var stepCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_step_tracker)

        //Tool bar
        val toolBar: MaterialToolbar = findViewById(R.id.materialToolbar)
        setSupportActionBar(toolBar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        toolBar.setTitleTextColor(ContextCompat.getColor(this, R.color.white))
        supportActionBar?.title = "Step Tracker"
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

        // Reset Button
        val resetButton: Button = findViewById(R.id.buttonReset)
        resetButton.setOnClickListener {
            stepCount = 0
            updateStepCount()
        }

        //Debug Button
        val debugButton: Button = findViewById(R.id.buttonDebug)
        debugButton.setOnClickListener {
            changeIcon("walking")
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    override fun onStop() {
        super.onStop()
        stopRecording()
    }

    fun changeIcon(action: String) {
        val actionImageView = findViewById<ImageView>(R.id.imageAction)
        val iconRes = when (action) {
            "walking" -> R.drawable.walking
            "running" -> R.drawable.running
            "standing" -> R.drawable.standing
            else -> return
        }
        actionImageView.setImageResource(iconRes)
    }

    fun updateStepCount() {
        findViewById<TextView>(R.id.textSteps)?.let { textView ->
            if (textView.text.toString() != stepCount.toString()) {
                textView.text = stepCount.toString()
            }
        }
    }

    fun startRecording() {
        // Start recording in a separate thread
        Thread {
            try {
                // Request permission to record audio
                if (ActivityCompat.checkSelfPermission(
                        this,
                        android.Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(android.Manifest.permission.RECORD_AUDIO),
                        1
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
                    audioSource,
                    sampleRate,
                    channelConfig,
                    audioEncoding,
                    bufferSize
                )

                audioRecord.startRecording()

                while (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val audioData = ShortArray(bufferSize)

                    val readResult = audioRecord.read(audioData, 0, audioData.size)

                    if (readResult > 0) {
                        //Process Audio Data Here
                        val floatAudioData = audioData.map { it.toFloat() }
                        Log.d("AudioData", floatAudioData.toString())
                    }

                }

            } catch (e: IOException) {
                e.printStackTrace()
            }
        }.start()
    }

    fun stopRecording() {
        audioRecord.stop()
        audioRecord.release()
    }


}