package com.example.earsense

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import com.androidplot.xy.BoundaryMode
import com.androidplot.xy.LineAndPointFormatter
import com.androidplot.xy.XYPlot
import com.androidplot.xy.XYSeries
import com.google.android.material.appbar.MaterialToolbar
import org.jtransforms.fft.FloatFFT_1D
import java.io.IOException

class ActivityRecognitionActivity : AppCompatActivity() {
    val sampleRate = 16000
    val channelConfig = AudioFormat.CHANNEL_IN_MONO
    val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
    val audioSource = MediaRecorder.AudioSource.MIC
    val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate, channelConfig, audioEncoding
    )

    lateinit var audioRecord: AudioRecord
    lateinit var audioManager: AudioManager

    lateinit var currentProfile: String

    lateinit var predictionTextView: TextView
    lateinit var plot: XYPlot

    var plotMinY = 0.0
    var plotMaxY = 0.0

    lateinit var trainingFeatures: Array<DoubleArray>
    lateinit var trainingLabels: IntArray

    val activityTypes = arrayOf("Walking", "Running", "Speaking", "Still")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_activity_recognition)

        val toolBar: MaterialToolbar = findViewById(R.id.materialToolbar)
        setSupportActionBar(toolBar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        toolBar.setTitleTextColor(ContextCompat.getColor(this, R.color.white))
        supportActionBar?.title = "Activity Recognition"
        //Back button
        toolBar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // Start Listening Button
        val buttonStart: Button = findViewById(R.id.buttonStart)
        buttonStart.setOnClickListener {
            // Load training features and labels
            trainingFeatures = Utils.readDoubleArrayFromFile(filesDir, currentProfile, "activity")
            trainingLabels = Utils.readIntArrayFromFile(filesDir, currentProfile, "activity")

            startRecording()
        }

        // Stop Listening Button
        val buttonStop: Button = findViewById(R.id.buttonStop)
        buttonStop.setOnClickListener {
            stopRecording()
        }

        // Plot
        plot = findViewById(R.id.plot)
        plot.graph.marginLeft = 0f
        plot.graph.paddingLeft = 0f
        plot.graph.marginBottom = 0f
        plot.graph.paddingBottom = 0f
        plot.legend.isVisible = false

        // Prediction TextView
        predictionTextView = findViewById(R.id.textPrediction)

        currentProfile = Utils.getCurrentProfile(this)

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

    private fun startRecording() {
        if (this::audioRecord.isInitialized && audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            return
        }

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

                setRecordingDeviceToBluetooth(this)

                audioRecord = AudioRecord(
                    audioSource, sampleRate, channelConfig, audioEncoding, bufferSize
                )

                audioRecord.startRecording()

                var runningAudioData = mutableListOf<Double>()

                var lastPrediction = 0

                while (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val audioData = ShortArray(bufferSize)

                    val readResult = audioRecord.read(audioData, 0, audioData.size)

                    if (readResult > 0) {

                        val doubleAudioData = audioData.map { it.toDouble() }.toDoubleArray()

                        runningAudioData.addAll(doubleAudioData.toList())

                        val runningAudioDataArray = runningAudioData.toDoubleArray()

                        plot.clear()
                        plotAudioSignal(
                            plot, runningAudioDataArray, "", Color.RED
                        )


                        // Wait for 1 second of audio data
                        if (runningAudioData.size < sampleRate) {
                            continue
                        } else {
                            val runningAudioDataArray = runningAudioData.toDoubleArray()

                            val prediction = makePrediction(runningAudioDataArray)

                            if (prediction == lastPrediction) {
                                runOnUiThread {
                                    predictionTextView.text = activityTypes[prediction]
                                    changeIcon(activityTypes[prediction])
                                }
                            }
                            lastPrediction = prediction
                            runningAudioData = mutableListOf()
                        }
                    }

                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }.start()

    }

    fun stopRecording() {
        if (this::audioRecord.isInitialized && audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            audioRecord.stop()
            audioRecord.release()
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
            plot.clear()
        }
    }

    fun setRecordingDeviceToBluetooth(context: Context) {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
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
    }

    fun makePrediction(audioData: DoubleArray): Int {
        // Calculate energy
        val energy = audioData.sumOf { Math.abs(it) }
        val stillEnergyCutOff = 3000000.0

        // Apply ftt
        val speakingFFTCutoff = 120

        val floatFFTSegment = audioData.map { it.toFloat() }.toFloatArray()
        val fft = FloatFFT_1D(floatFFTSegment.size.toLong())
        fft.realForward(floatFFTSegment)
        val doubleFFTAudioData = floatFFTSegment.map { it.toDouble() }.toDoubleArray()
        // find index of maximum value
        val maxIndex = doubleFFTAudioData.withIndex().maxByOrNull { it.value }?.index

        if (energy < stillEnergyCutOff) {
            return 3 // Still
        }

        if (maxIndex != null && maxIndex > speakingFFTCutoff) {
            return 2 // Speaking
        }

        if (energy < 10000000) {
            return 0 // Walking
        } else {
            return 1 // Running
        }
    }

    fun changeIcon(activity: String) {
        val actionImageView = findViewById<ImageView>(R.id.imageActivity)
        val iconRes = when (activity) {
            "Walking" -> R.drawable.walking
            "Running" -> R.drawable.running
            "Still" -> R.drawable.standing
            "Speaking" -> R.drawable.speaking
            else -> return
        }
        actionImageView.setImageResource(iconRes)
    }

    private fun plotAudioSignal(plot: XYPlot, audioSignal: DoubleArray, title: String, color: Int) {
        // Create an XYSeries to hold the data
        val series: XYSeries = object : XYSeries {
            override fun size(): Int {
                return audioSignal.size
            }

            override fun getX(i: Int): Number {
                return i // X values will just be the indices of the signal
            }

            override fun getY(i: Int): Number {
                return audioSignal[i] // Y values are the actual audio signal values
            }

            override fun getTitle(): String {
                return title
            }
        }

        // Create a formatter to style the plot (line style, color, etc.)
        val seriesFormat = LineAndPointFormatter(
            color, null, null, null
        )

        // Add the series to the plot
        plot.addSeries(series, seriesFormat)

        // Adjust the range and domain of the plot
        // Y-AXIS
        var minValue = audioSignal.minOrNull() ?: 0.0  // Get the minimum value in the signal
        var maxValue = audioSignal.maxOrNull() ?: 0.0  // Get the maximum value in the signal

        if (minValue < plotMinY) {
            plotMinY = minValue
        }
        if (maxValue > plotMaxY) {
            plotMaxY = maxValue
        }

        minValue = plotMinY
        maxValue = plotMaxY

        // Add a small buffer around the data to ensure it doesn't touch the axis edges
        val padding = 0.1 * (maxValue - minValue)  // 10% padding
        plot.setRangeBoundaries(
            minValue - padding, maxValue + padding, BoundaryMode.FIXED
        )

        // X-AXIS
        plot.setDomainBoundaries(
            0, audioSignal.size.toFloat(), BoundaryMode.FIXED
        )

        plot.redraw()
    }
}