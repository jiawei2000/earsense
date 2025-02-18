package com.example.earsense

import android.content.Context
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
import com.github.psambit9791.jdsp.signal.peaks.FindPeak
import com.google.android.material.appbar.MaterialToolbar
import org.jtransforms.fft.FloatFFT_1D
import java.io.IOException

class StepTrackerActivity : AppCompatActivity() {

    val sampleRate = 16000
    val channelConfig = AudioFormat.CHANNEL_IN_MONO
    val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
    val audioSource = MediaRecorder.AudioSource.MIC
    val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate, channelConfig, audioEncoding
    )

    lateinit var audioRecord: AudioRecord
    lateinit var audioManager: AudioManager

    lateinit var plot: XYPlot
    lateinit var plot2: XYPlot

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
            setRecordingDeviceToBluetooth(this)
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
//            changeIcon("walking")
        }

        // Plot
        plot = findViewById(R.id.plot1)
        plot2 = findViewById(R.id.plot2)

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
                        this, android.Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 1
                    )
                }

                audioRecord = AudioRecord(
                    audioSource, sampleRate, channelConfig, audioEncoding, bufferSize
                )
                audioRecord.startRecording()

                var bufferCount = 0
                var runningAudioData = mutableListOf<Double>()
                var recordedPeaks = mutableListOf<Int>()

                while (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val audioData = ShortArray(bufferSize)

                    val readResult = audioRecord.read(audioData, 0, audioData.size)

                    if (readResult > 0) {
                        //Process Audio Data
                        bufferCount++
                        val doubleAudioArray = audioData.map { it.toDouble() }.toDoubleArray()

                        val lowpassSegment = DoubleArray(doubleAudioArray.size)
                        // Apply low-pass filter
                        val filter =
                            PassFilter(
                                50.toFloat(),
                                sampleRate,
                                PassFilter.PassType.Lowpass,
                                1.toFloat()
                            )
                        for (i in doubleAudioArray.indices) {
                            filter.Update(doubleAudioArray[i].toFloat())
                            lowpassSegment[i] = filter.getValue().toDouble()
                        }

                        // Append to running audio data
                        runningAudioData.addAll(lowpassSegment.toList())
                        val runningAudioArray = runningAudioData.toDoubleArray()

                        runOnUiThread {
                            plot.clear()
                            plotAudioSignal(plot, runningAudioArray, "Audio Signal", Color.RED)
                        }

                        // Only process 1 second of audio data
                        if (runningAudioData.size < sampleRate) {
                            continue
                        }

                        // Remove oldest buffer if audio signal too long
                        runningAudioData = runningAudioData.drop(bufferSize).toMutableList()


                        val peaks = findPeaks(runningAudioArray, 700.0)
                        var lastPeak = 0
                        var lastValidPeak = 0

                        if (peaks.isNotEmpty()) {
                            // Apply fft and check cutoff for speaking
                            val speakingFFTCutoff = 120
                            val floatFFTSegment =
                                runningAudioData.map { it.toFloat() }.toFloatArray()
                            val fft = FloatFFT_1D(floatFFTSegment.size.toLong())
                            fft.realForward(floatFFTSegment)
                            val doubleFFTAudioData =
                                floatFFTSegment.map { it.toDouble() }.toDoubleArray()
                            // find index of maximum value
                            val maxIndex =
                                doubleFFTAudioData.withIndex().maxByOrNull { it.value }?.index

                            runOnUiThread {
                                plot2.clear()
                                plotAudioSignal(plot2, doubleFFTAudioData, "FFT", Color.YELLOW)
                            }

                            if (maxIndex != null && maxIndex > speakingFFTCutoff) {
                                continue
                            }
                        }

                        for (peak in peaks) {
                            val distance = peak - lastPeak
                            lastPeak = peak
                            // Only allow peak if not too close to last peak and at the edge of audioData or peak is repeated
                            if (distance > 3000 && peak > 3000 && peak < 13000) {
                                lastValidPeak = peak
                            }
                        }

                        if (lastValidPeak != 0) {
                            val adjustedPeakIndex = lastValidPeak + (bufferCount * bufferSize)
                            if (adjustedPeakIndex !in recordedPeaks) {
                                recordedPeaks.add(adjustedPeakIndex)
                                stepCount++
                                runOnUiThread {
                                    updateStepCount()
                                }
                            }
                        }
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
        var minValue = audioSignal.minOrNull() ?: 0.0  // Get the minimum value in the signal
        var maxValue = audioSignal.maxOrNull() ?: 0.0  // Get the maximum value in the signal

//        val minValue = -8000
//        val maxValue = 10000

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