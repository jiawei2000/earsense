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

class NewGestureTrainingActivity : AppCompatActivity() {

    val gestures = arrayOf("Jaw", "Left Temple", "Right Temple")

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

    lateinit var plot: XYPlot
    lateinit var textInstructions: TextView
    lateinit var textRecording: TextView

    lateinit var labels: Array<String>
    lateinit var signals: Array<DoubleArray>
    lateinit var lowpassSignals: Array<DoubleArray>
    lateinit var fttSignals: Array<DoubleArray>


    var currentIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_new_gesture_training)

        // Get current profile
        currentProfile = Utils.getCurrentProfile(this)

        //Tool bar
        val toolBar: MaterialToolbar = findViewById(R.id.materialToolbar)
        setSupportActionBar(toolBar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        toolBar.setTitleTextColor(ContextCompat.getColor(this, R.color.white))
        supportActionBar?.title = "Gesture Training"
        toolBar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // Plot
        plot = findViewById(R.id.plot)
        plot.graph.marginLeft = 0f
        plot.graph.paddingLeft = 70f
        plot.graph.marginBottom = 0f
        plot.graph.paddingBottom = 0f

        // Text Views
        textInstructions = findViewById(R.id.textInstructions)
        textRecording = findViewById(R.id.textRecording)

        val noSamples = 5
        labels = Array(gestures.size * noSamples) { "" }

        loadAllSignals()
        if(signals.isEmpty() || lowpassSignals.isEmpty() || fttSignals.isEmpty()) {
            signals = Array(gestures.size * noSamples) { DoubleArray(0) }
            lowpassSignals = Array(gestures.size * noSamples) { DoubleArray(0) }
            fttSignals = Array(gestures.size * noSamples) { DoubleArray(0) }
        }

        for (gesture in gestures) {
            for (i in 1..noSamples) {
                labels[(gestures.indexOf(gesture) * noSamples) + i - 1] = "$gesture $i"
            }
        }

        // Button Record
        val recordButton = findViewById<TextView>(R.id.buttonRecord)
        recordButton.setOnClickListener() {
            startRecording()
        }

        // Button Save
        val saveButton = findViewById<TextView>(R.id.buttonSave)
        saveButton.setOnClickListener() {
            saveAllSignals()
        }

        // Button Next
        val nextButton = findViewById<TextView>(R.id.buttonNext)
        nextButton.setOnClickListener() {
            if (currentIndex < labels.size - 1) {
                currentIndex += 1
                changePage()
            }
        }


        // Button Back
        val backButton = findViewById<TextView>(R.id.buttonBack)
        backButton.setOnClickListener() {
            if (currentIndex > 0) {
                currentIndex -= 1
                changePage()
            }
        }

        changePage()

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main))
        { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
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
                textRecording.post {
                    textRecording.text = "Recording"
                }


                var runningAudioData = mutableListOf<Double>()
                var runningLowpass = mutableListOf<Double>()

                while (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val audioData = ShortArray(bufferSize)

                    val readResult = audioRecord.read(audioData, 0, audioData.size)

                    if (readResult > 0) {

                        val doubleAudioData = audioData.map { it.toDouble() }.toDoubleArray()

                        runningAudioData.addAll(doubleAudioData.toList())

                        val lowpassAudioData = DoubleArray(doubleAudioData.size)
                        // Apply low-pass filter
                        val filter =
                            PassFilter(
                                50.toFloat(),
                                sampleRate,
                                PassFilter.PassType.Lowpass,
                                1.toFloat()
                            )
                        for (i in doubleAudioData.indices) {
                            filter.Update(doubleAudioData[i].toFloat())
                            lowpassAudioData[i] = filter.getValue().toDouble()
                        }

                        runningLowpass.addAll(lowpassAudioData.toList())

                        val runningAudioDataArray = runningAudioData.toDoubleArray()
                        val runningLowpassArray = runningLowpass.toDoubleArray()

                        // Process all Low Pass data
                        val lowpassMax = runningLowpass.maxOrNull() ?: 0.0
                        val minAmplitude = 800.0

                        if (lowpassMax < minAmplitude) {
                            continue
                        }

                        // Find peaks
                        val peaks = Utils.findPeaks(runningLowpassArray, minAmplitude)

                        // For each peak find distance from last peak
                        var lastPeak = 0
                        val listOfPeaks = mutableListOf<Int>()
                        for (peak in peaks) {
                            val distance = peak - lastPeak
                            lastPeak = peak
                            val bufferNo = 5
                            // Reject peak if too close to last peak or at the edge of audioData
                            if (distance > 5000 && peak > bufferSize * bufferNo && peak < runningLowpassArray.size - bufferSize * bufferNo) {
                                listOfPeaks.add(peak)
                            }
                        }

                        // Extract the latest peak from Original Array
                        val latestPeak = listOfPeaks.lastOrNull() ?: continue
                        val segment = Utils.extractSegmentAroundPeak(
                            runningAudioDataArray,
                            latestPeak,
                            sampleRate
                        )

                        val lowpassSegment = Utils.extractSegmentAroundPeak(
                            runningLowpassArray,
                            latestPeak,
                            sampleRate
                        )

                        //Apply FTT to original segment
                        val floatFTTSegment = segment.map { it.toFloat() }.toFloatArray()
                        val fft = FloatFFT_1D(floatFTTSegment.size.toLong())
                        fft.realForward(floatFTTSegment)
                        val fttSegment = floatFTTSegment.map { it.toDouble() }.toDoubleArray()

                        plot.clear()
                        plotAudioSignal(plot, segment, "original", Color.RED)
                        plotAudioSignal(plot, lowpassSegment, "lowpass", Color.BLUE)


                        // Add the segment to the signals array
                        signals[currentIndex] = segment
                        lowpassSignals[currentIndex] = lowpassSegment
                        fttSignals[currentIndex] = fttSegment

                        stopRecording()
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }.start()

    }

    override fun onStop() {
        super.onStop()
        stopRecording()
    }

    fun stopRecording() {
        textRecording.post {
            textRecording.text = ""
        }
        if (this::audioRecord.isInitialized && audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            audioRecord.stop()
            audioRecord.release()
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
        }
    }

    fun saveAllSignals() {
        Utils.saveDoubleArrayToFile(signals, filesDir, currentProfile, "gestures")
        Utils.saveDoubleArrayToFile(lowpassSignals, filesDir, currentProfile, "gesturesLowpass")
        Utils.saveDoubleArrayToFile(fttSignals, filesDir, currentProfile, "gesturesFTT")
    }

    fun loadAllSignals() {
        signals = Utils.readDoubleArrayFromFile(filesDir, currentProfile, "gestures")
        lowpassSignals = Utils.readDoubleArrayFromFile(filesDir, currentProfile, "gesturesLowpass")
        fttSignals = Utils.readDoubleArrayFromFile(filesDir, currentProfile, "gesturesFTT")
    }

    fun changePage() {
        stopRecording()
        runOnUiThread {
            textInstructions.text = "Signal for " + labels[currentIndex]
            plot.clear()
            if (signals[currentIndex].isNotEmpty() && lowpassSignals[currentIndex].isNotEmpty()) {
                plotAudioSignal(plot, signals[currentIndex], "original", Color.RED)
                plotAudioSignal(plot, lowpassSignals[currentIndex], "lowpass", Color.BLUE)
            }
            plot.redraw()
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
            null, // Point color (none in this case)
            null, // Fill color (none in this case)
            null  // Background color (none)
        )

        // Add the series to the plot
        plot.addSeries(series, seriesFormat)

        // Adjust the range and domain of the plot
        // Y - AXIS
        var minValue = audioSignal.minOrNull() ?: 0.0  // Get the minimum value in the signal
        var maxValue = audioSignal.maxOrNull() ?: 0.0  // Get the maximum value in the signal

        // Add a small buffer around the data to ensure it doesn't touch the axis edges
        val padding = 0.1 * (maxValue - minValue)  // 10% padding
        plot.setRangeBoundaries(
            minValue - padding,
            maxValue + padding,
            BoundaryMode.FIXED
        )

        // X - AXIS
        plot.setDomainBoundaries(
            0,
            audioSignal.size.toFloat(),
            BoundaryMode.FIXED
        )

        plot.redraw()
    }
}