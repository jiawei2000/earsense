package com.example.earsense

import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
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

    var audioRecord: AudioRecord? = null
    var audioManager: AudioManager? = null

    lateinit var currentProfile: String

    lateinit var predictionTextView: TextView
    lateinit var plot: XYPlot

    var plotMinY = 0.0
    var plotMaxY = 0.0

    lateinit var trainingFeatures: Array<DoubleArray>
    lateinit var trainingLabels: IntArray

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
        plot = findViewById(R.id.plot)

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

                audioRecord = AudioRecord(
                    audioSource,
                    sampleRate,
                    channelConfig,
                    audioEncoding,
                    bufferSize
                )

                audioRecord?.startRecording()

                var runningAudioData = mutableListOf<Double>()
                var runningLowPass = mutableListOf<Double>()

                var predictions = mutableListOf<Int>()

                while (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val audioData = ShortArray(bufferSize)

                    val readResult = audioRecord?.read(audioData, 0, audioData.size)

                    if (readResult != null && readResult > 0) {

                        val doubleAudioData = audioData.map { it.toDouble() }.toDoubleArray()

                        //Calculate amplitude
                        val amplitude = doubleAudioData.maxOrNull() ?: 0.0
                        runningAudioData.addAll(doubleAudioData.toList())

                        val lowpassSegment = DoubleArray(doubleAudioData.size)
                        // Apply low-pass filter
                        val filter = PassFilter(
                            50.toFloat(),
                            sampleRate,
                            PassFilter.PassType.Lowpass,
                            1.toFloat()
                        )
                        for (i in doubleAudioData.indices) {
                            filter.Update(doubleAudioData[i].toFloat())
                            lowpassSegment[i] = filter.getValue().toDouble()
                        }

                    }

                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }.start()

    }

    fun stopRecording() {
        audioRecord?.stop()
        audioRecord?.release()
        audioManager?.stopBluetoothSco()
        audioManager?.isBluetoothScoOn = false
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

    private fun plotAudioSignal(audioSignal: DoubleArray, title: String, color: Int) {
        // Step 1: Create an XYSeries to hold the data
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

        // Step 2: Create a formatter to style the plot (line style, color, etc.)
        val seriesFormat = LineAndPointFormatter(
            color,
            null,
            null,
            null
        )

        // Step 3: Add the series to the plot
        plot.addSeries(series, seriesFormat)


        // Step 5: Adjust the range and domain of the plot
        // Y - AXIS
        val minValue = audioSignal.minOrNull() ?: 0.0  // Get the minimum value in the signal
        val maxValue = audioSignal.maxOrNull() ?: 0.0  // Get the maximum value in the signal

        if (minValue < plotMinY) {
            plotMinY = minValue
        }

        if (maxValue > plotMaxY) {
            plotMaxY = maxValue
        }

//        val minValue = -17000
//        val maxValue = 19000

//      Add a small buffer around the data to ensure it doesn't touch the axis edges
        val padding = 0.1 * (plotMaxY - plotMinY)  // 10% padding
        plot.setRangeBoundaries(
            plotMinY - padding,
            plotMaxY + padding,
            BoundaryMode.FIXED
        ) // Y-axis range


        // X - AXIS
        plot.setDomainBoundaries(
            0,
//            audioSignal.size.toFloat(),
            500.0,
            BoundaryMode.FIXED
        ) // Set X-axis range based on the signal size

        // Step 6: Redraw the plot to reflect changes
        plot.redraw()
    }
}