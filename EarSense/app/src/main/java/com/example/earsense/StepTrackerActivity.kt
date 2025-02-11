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
import com.github.psambit9791.jdsp.filter.FIRWin1
import com.github.psambit9791.jdsp.signal.peaks.FindPeak
import com.github.psambit9791.jdsp.transform.Hilbert
import com.google.android.material.appbar.MaterialToolbar
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

    lateinit var waveFormView: WaveFormView

    var stepCount = 0
    var startTime = 0L

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

        // Waveform View
        waveFormView = findViewById(R.id.waveFormView)

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

                var runningAudioData = mutableListOf<Double>()

                var lastPeakTime = 0L

                while (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val audioData = ShortArray(bufferSize)

                    val readResult = audioRecord.read(audioData, 0, audioData.size)

                    //Log current time
                    startTime = System.currentTimeMillis()

                    if (readResult > 0) {
                        //Process Audio Data
                        val doubleAudioArray = audioData.map { it.toDouble() }.toDoubleArray()

                        // Apply Low pass filter 50hz low pass filter
                        val filteredAudioData = applyLowPassFilter(
                            doubleAudioArray, sampleRate.toDouble(), 50.0
                        )


                        val amplitude = filteredAudioData.max() * 1000
                        waveFormView.addAmplitude(amplitude.toFloat())

                        // Append to running audio data
                        runningAudioData.addAll(filteredAudioData.toList())

                        // Only process 1 second of audio data
                        if (runningAudioData.size < sampleRate) {
                            continue
                        }

                        // Detect peaks and troughs
                        val peaks = findPeaks(runningAudioData.toDoubleArray(), 0.8)

                        if (peaks.size < 2) {
                            runOnUiThread() {
                                changeIcon("standing")
                            }
                        } else {
                            for (peak in peaks) {
                                val timeFromLastPeak = System.currentTimeMillis() - lastPeakTime
                                val peakAmplitude = runningAudioData[peak]
                                Log.d("AAAAAAAAA", "Peak: $peakAmplitude, Time: $timeFromLastPeak")

                                if (timeFromLastPeak > 100) {
                                    runOnUiThread() {
                                        stepCount++
                                        updateStepCount()
                                        if (timeFromLastPeak > 1000) {
                                            changeIcon("walking")
                                        } else {
                                            changeIcon("running")
                                        }
                                    }
                                    lastPeakTime = System.currentTimeMillis()
                                }
                            }
                        }

//                        var lastPeak = 0

//                        for (peak in peaks) {
//                            //Log distance to last peak
//                            Log.d("AAAAAAAAA", "Distance: ${peak - lastPeak}")
//
//                            //log peak amplitude
//                            Log.d("AAAAAAAAA", "Peak: ${runningAudioData[peak]}")
//
//                            // Check minimum distance between peaks
//                            if (peak - lastPeak > 0.3 * sampleRate) {
//                                // Run in ui thread
//                                lastPeak = peak
//                                runOnUiThread {
//                                    stepCount++
//                                    updateStepCount()
//
//                                }
//                            }
//                        }
                        runningAudioData = mutableListOf<Double>()

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

    fun applyLowPassFilter(
        doubleAudioData: DoubleArray, samplingRate: Double, cutoffFreq: Double
    ): DoubleArray {

        // Define filter parameters
        val filterWidth = 5.0  // Filter transition width
        val rippleValue = 60.0  // Stopband ripple in dB
        val cutoff = doubleArrayOf(cutoffFreq)  // Cutoff frequency

        // Create FIR windowed filter
        val fw = FIRWin1(rippleValue, filterWidth, samplingRate)

        // Compute FIR filter coefficients
        val coefficients = fw.computeCoefficients(cutoff, FIRWin1.FIRfilterType.LOWPASS, true)

        // Apply FIR filter
        val filteredSignal = fw.firfilter(coefficients, doubleAudioData)

        // Convert back to FloatArray
        return filteredSignal
    }

    fun computeHilbertEnvelopes(signal: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val hilbert = Hilbert(signal)

        Log.d("BBBBBBBBB", "reached AA")

        hilbert.transform(true)  // Apply Hilbert transform

        Log.d("BBBBBBBBB", "reached BB")

        val analyticSignal = hilbert.output  // Get the analytic signal

        Log.d("BBBBBBBBB", "reached CC")

        val upperEnvelope = analyticSignal.map { it[0] }.toDoubleArray()
        val lowerEnvelope = analyticSignal.map { it[1] }.toDoubleArray()

        return Pair(upperEnvelope, lowerEnvelope)
    }

    fun findPeaks(audioData: DoubleArray, minPeakAmplitude: Double): IntArray {

        val fp = FindPeak(audioData)

        // Detect peaks
        val peaks = fp.detectPeaks().peaks

        //Filter peaks based on minimum amplitude
        val filteredPeaks = peaks.filter { audioData[it] >= minPeakAmplitude }.toIntArray()

        // Detect troughs
        val troughs = fp.detectTroughs().peaks

//        val minPeakHeight = 1000.0  // Minimum peak height
//        val minDistance = 300  // Minimum distance between peaks
//
//        // Filter peaks based on minimum height
//        val filteredPeaks = peaks.filter { signal[it] >= minPeakHeight }.toIntArray()
//
//
//        // Filter peaks based on minimum distance between peaks
//        val filteredPeaksByDistance = mutableListOf<Int>()
//        var lastPeakIndex = -minDistance  // Initialize with a large negative value
//
//        for (peak in filteredPeaks) {
//            if (peak - lastPeakIndex >= minDistance) {
//                filteredPeaksByDistance.add(peak)
//                lastPeakIndex = peak
//            }
//        }

        return filteredPeaks
    }

}