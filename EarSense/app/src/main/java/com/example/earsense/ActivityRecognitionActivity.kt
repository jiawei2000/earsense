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
import com.github.psambit9791.jdsp.signal.peaks.FindPeak
import com.google.android.material.appbar.MaterialToolbar
import java.io.IOException

class ActivityRecognitionActivity : AppCompatActivity() {
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

    lateinit var currentProfile: String

    lateinit var predictionTextView: TextView
    lateinit var plot1: XYPlot
    lateinit var plot2: XYPlot

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

        // Debug Button
        val buttonPlayAudio: Button = findViewById(R.id.buttonDebug)
        buttonPlayAudio.setOnClickListener {
//            buttonDebug()
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

        // Train Model Button
        val trainModelButton: Button = findViewById(R.id.buttonTrainModel)
        trainModelButton.setOnClickListener {
            val intent = Intent(this, ActivityTrainingActivity::class.java)
            startActivity(intent)
        }

        // Plot
        plot1 = findViewById(R.id.plot1)
        plot2 = findViewById(R.id.plot2)

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

                setRecordingDeviceToBluetooth(this)

                audioRecord = AudioRecord(
                    audioSource,
                    sampleRate,
                    channelConfig,
                    audioEncoding,
                    bufferSize
                )

                audioRecord.startRecording()

                var runningAudioData = mutableListOf<Double>()

                var predictions = mutableListOf<Int>()

                while (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val audioData = ShortArray(bufferSize)

                    val readResult = audioRecord.read(audioData, 0, audioData.size)

                    if (readResult != null && readResult > 0) {

                        val doubleAudioData = audioData.map { it.toDouble() }.toDoubleArray()

                        runningAudioData.addAll(doubleAudioData.toList())

                        // Wait for 1 second of audio data
                        if (runningAudioData.size < sampleRate) {
                            continue
                        } else {

                            val runningAudioDataArray = runningAudioData.toDoubleArray()

                            // Calculate energy
                            val energy = runningAudioDataArray.sumByDouble { Math.abs(it) }

                            // Log training features value

                            // Find which activity has the closest energy value to the current energy
                            val closestActivity = trainingFeatures.minByOrNull {
                                Math.abs(it[0] - energy)
                            }

                            // Prediction is the index of the closest activity
                            val prediction = trainingLabels[trainingFeatures.indexOf(closestActivity)]

                            // Log closest activity
                            Log.d("AAAAAAAAAA", "Closest Activity: ${trainingLabels[trainingFeatures.indexOf(closestActivity)]}")

                            runOnUiThread {
                                plot2.clear()
                                plotAudioSignal(
                                    plot2,
                                    runningAudioDataArray,
                                    "Audio Signal",
                                    Color.YELLOW
                                )
                                predictionTextView.text = "Prediction: ${activityTypes[prediction]}"
                            }



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
        audioRecord.stop()
        audioRecord.release()
        audioManager.stopBluetoothSco()
        audioManager.isBluetoothScoOn = false
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

    fun extractSegmentAroundPeak(window: DoubleArray, peakIndex: Int): DoubleArray {
        val segmentDuration = 0.4
        val segmentSize = (segmentDuration * sampleRate).toInt() // Number of samples in 0.4 seconds

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

    fun findPeaks(audioData: DoubleArray, minPeakAmplitude: Double): IntArray {
        val fp = FindPeak(audioData)

        // Detect peaks
        val peaks = fp.detectPeaks().peaks

        //Filter peaks based on minimum amplitude
        val filteredPeaks = peaks.filter { audioData[it] >= minPeakAmplitude }.toIntArray()
        return filteredPeaks
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
            color,
            null,
            null,
            null
        )

        // Add the series to the plot
        plot.addSeries(series, seriesFormat)

        // Adjust the range and domain of the plot
        // Y-AXIS
        val minValue = audioSignal.minOrNull() ?: 0.0  // Get the minimum value in the signal
        val maxValue = audioSignal.maxOrNull() ?: 0.0  // Get the maximum value in the signal

//        val minValue = -17000
//        val maxValue = 19000

        // Add a small buffer around the data to ensure it doesn't touch the axis edges
        val padding = 0.1 * (maxValue - minValue)  // 10% padding
        plot.setRangeBoundaries(
            minValue - padding,
            maxValue + padding,
            BoundaryMode.FIXED
        )

        // X-AXIS
        plot.setDomainBoundaries(
            0,
            audioSignal.size.toFloat(),
            BoundaryMode.FIXED
        )

        plot.redraw()
    }
}