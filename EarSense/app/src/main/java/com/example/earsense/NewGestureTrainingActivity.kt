package com.example.earsense

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.Image
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.androidplot.xy.BoundaryMode
import com.androidplot.xy.LineAndPointFormatter
import com.androidplot.xy.XYPlot
import com.androidplot.xy.XYSeries
import com.google.android.material.appbar.MaterialToolbar
import org.apache.commons.math3.stat.correlation.PearsonsCorrelation
import org.jtransforms.fft.FloatFFT_1D
import java.io.IOException

class NewGestureTrainingActivity : AppCompatActivity() {

    val gestures = arrayOf("Jaw", "Left Temple", "Right Temple")
    val circleLocations = arrayOf(
        arrayOf(0.99, 0.51, 120, 120),
        arrayOf(0.33, 0.40, 120, 120),
        arrayOf(0.33, 0.65, 120, 120)
    )

    lateinit var imageCircle: ImageView

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

        // Image circle
        imageCircle = findViewById(R.id.imageCircle)

        val noSamples = 5
        labels = Array(gestures.size * noSamples) { "" }

        loadAllSignals()
        if (signals.isEmpty() || lowpassSignals.isEmpty() || fttSignals.isEmpty()) {
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
//            testScoreEffectiveness()
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

    fun testScoreEffectiveness() {
        var intraSum = arrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        var intraCount = arrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0)
        var interSum = arrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        var interCount = arrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0)


        var intraMax = arrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        var intraMin = arrayOf(
            1000000.0,
            1000000.0,
            1000000.0,
            1000000.0,
            1000000.0,
            1000000.0,
            1000000.0,
            1000000.0,
            1000000.0
        )
        var interMax = arrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        var interMin = arrayOf(
            1000000.0,
            1000000.0,
            1000000.0,
            1000000.0,
            1000000.0,
            1000000.0,
            1000000.0,
            1000000.0,
            1000000.0
        )

        val scoringLabels = arrayOf(
            "fttEculidean", "fttCosine", "fttPearson",
            "originalEculidean", "originalCosine", "originalPearson",
            "lowpassEculidean", "lowpassCosine", "lowpassPearson"
        )

        for (i in fttSignals.indices) {
            for (j in fttSignals.indices) {
                if (i != j) {
                    val euclideanScore = Utils.euclideanDistance(fttSignals[i], fttSignals[j])
                    val cosineScore = Utils.cosineSimilarity(fttSignals[i], fttSignals[j])
                    val pearsonScore =
                        PearsonsCorrelation().correlation(fttSignals[i], fttSignals[j])
//                    If same gesture
                    if (labels[i][0] == labels[j][0]) {
                        intraSum[0] += euclideanScore
                        intraCount[0] += 1
                        intraSum[1] += cosineScore
                        intraCount[1] += 1
                        intraSum[2] += pearsonScore
                        intraCount[2] += 1

                        if (euclideanScore > intraMax[0]) {
                            intraMax[0] = euclideanScore
                        }
                        if (euclideanScore < intraMin[0]) {
                            intraMin[0] = euclideanScore
                        }
                        if (cosineScore > intraMax[1]) {
                            intraMax[1] = cosineScore
                        }
                        if (cosineScore < intraMin[1]) {
                            intraMin[1] = cosineScore
                        }
                        if (pearsonScore > intraMax[2]) {
                            intraMax[2] = pearsonScore
                        }
                        if (pearsonScore < intraMin[2]) {
                            intraMin[2] = pearsonScore
                        }
                    } else {
                        interSum[0] += euclideanScore
                        interCount[0] += 1
                        interSum[1] += cosineScore
                        interCount[1] += 1
                        interSum[2] += pearsonScore
                        interCount[2] += 1

                        if (euclideanScore > interMax[0]) {
                            interMax[0] = euclideanScore
                        }
                        if (euclideanScore < interMin[0]) {
                            interMin[0] = euclideanScore
                        }
                        if (cosineScore > interMax[1]) {
                            interMax[1] = cosineScore
                        }
                        if (cosineScore < interMin[1]) {
                            interMin[1] = cosineScore
                        }
                        if (pearsonScore > interMax[2]) {
                            interMax[2] = pearsonScore
                        }
                        if (pearsonScore < interMin[2]) {
                            interMin[2] = pearsonScore
                        }
                    }
                }
            }
        }

        for (i in signals.indices) {
            for (j in signals.indices) {
                if (i != j) {
                    val euclideanScore = Utils.euclideanDistance(signals[i], signals[j])
                    val cosineScore = Utils.cosineSimilarity(signals[i], signals[j])
                    val pearsonScore =
                        PearsonsCorrelation().correlation(signals[i], signals[j])

                    if (labels[i][0] == labels[j][0]) {
                        intraSum[3] += euclideanScore
                        intraCount[3] += 1
                        intraSum[4] += cosineScore
                        intraCount[4] += 1
                        intraSum[5] += pearsonScore
                        intraCount[5] += 1

                        if (euclideanScore > intraMax[3]) {
                            intraMax[3] = euclideanScore
                        }
                        if (euclideanScore < intraMin[3]) {
                            intraMin[3] = euclideanScore
                        }
                        if (cosineScore > intraMax[4]) {
                            intraMax[4] = cosineScore
                        }
                        if (cosineScore < intraMin[4]) {
                            intraMin[4] = cosineScore
                        }
                        if (pearsonScore > intraMax[5]) {
                            intraMax[5] = pearsonScore
                        }
                        if (pearsonScore < intraMin[5]) {
                            intraMin[5] = pearsonScore
                        }
                    } else {
                        interSum[3] += euclideanScore
                        interCount[3] += 1
                        interSum[4] += cosineScore
                        interCount[4] += 1
                        interSum[5] += pearsonScore
                        interCount[5] += 1

                        if (euclideanScore > interMax[3]) {
                            interMax[3] = euclideanScore
                        }
                        if (euclideanScore < interMin[3]) {
                            interMin[3] = euclideanScore
                        }
                        if (cosineScore > interMax[4]) {
                            interMax[4] = cosineScore
                        }
                        if (cosineScore < interMin[4]) {
                            interMin[4] = cosineScore
                        }
                        if (pearsonScore > interMax[5]) {
                            interMax[5] = pearsonScore
                        }
                        if (pearsonScore < interMin[5]) {
                            interMin[5] = pearsonScore
                        }
                    }
                }
            }
        }

        for (i in lowpassSignals.indices) {
            for (j in lowpassSignals.indices) {
                if (i != j) {
                    val euclideanScore =
                        Utils.euclideanDistance(lowpassSignals[i], lowpassSignals[j])
                    val cosineScore =
                        Utils.cosineSimilarity(lowpassSignals[i], lowpassSignals[j])
                    val pearsonScore =
                        PearsonsCorrelation().correlation(lowpassSignals[i], lowpassSignals[j])

                    if (labels[i][0] == labels[j][0]) {
                        intraSum[6] += euclideanScore
                        intraCount[6] += 1
                        intraSum[7] += cosineScore
                        intraCount[7] += 1
                        intraSum[8] += pearsonScore
                        intraCount[8] += 1

                        if (euclideanScore > intraMax[6]) {
                            intraMax[6] = euclideanScore
                        }
                        if (euclideanScore < intraMin[6]) {
                            intraMin[6] = euclideanScore
                        }
                        if (cosineScore > intraMax[7]) {
                            intraMax[7] = cosineScore
                        }
                        if (cosineScore < intraMin[7]) {
                            intraMin[7] = cosineScore
                        }
                        if (pearsonScore > intraMax[8]) {
                            intraMax[8] = pearsonScore
                        }
                        if (pearsonScore < intraMin[8]) {
                            intraMin[8] = pearsonScore
                        }
                    } else {
                        interSum[6] += euclideanScore
                        interCount[6] += 1
                        interSum[7] += cosineScore
                        interCount[7] += 1
                        interSum[8] += pearsonScore
                        interCount[8] += 1

                        if (euclideanScore > interMax[6]) {
                            interMax[6] = euclideanScore
                        }
                        if (euclideanScore < interMin[6]) {
                            interMin[6] = euclideanScore
                        }
                        if (cosineScore > interMax[7]) {
                            interMax[7] = cosineScore
                        }
                        if (cosineScore < interMin[7]) {
                            interMin[7] = cosineScore
                        }
                        if (pearsonScore > interMax[8]) {
                            interMax[8] = pearsonScore
                        }
                        if (pearsonScore < interMin[8]) {
                            interMin[8] = pearsonScore
                        }
                    }
                }
            }
        }


        for (i in 0..8) {
            var intraAverage = intraSum[i] / intraCount[i]
            var interAverage = interSum[i] / interCount[i]

//            println("${scoringLabels[i]} Intra Average: $intraAverage")
//            println("${scoringLabels[i]} Inter Average: $interAverage")
            println("${scoringLabels[i]} Max Intra: ${intraMax[i]}")
            println("${scoringLabels[i]} Min Intra: ${intraMin[i]}")
            println("${scoringLabels[i]} Max Inter: ${interMax[i]}")
            println("${scoringLabels[i]} Min Inter: ${interMin[i]}")
            println("${scoringLabels[i]} Score: ${intraAverage - interAverage}")
            println("=====================================")
        }

    }


    fun changePage() {
        stopRecording()
        runOnUiThread {
        // Hardcode circle index to use
            var circleIndex = 0
            if (currentIndex == 5 || currentIndex == 6 || currentIndex == 7 || currentIndex == 8 || currentIndex == 9) {
                circleIndex = 1
            } else if (currentIndex == 10 || currentIndex == 11 || currentIndex == 12 || currentIndex == 13 || currentIndex == 14) {
                circleIndex = 2
            }

            // Display circle
            val params =
                imageCircle.layoutParams as ConstraintLayout.LayoutParams
            val vertical = circleLocations[circleIndex][0] as Double
            val horizontal = circleLocations[circleIndex][1] as Double

            params.verticalBias = vertical.toFloat()
            params.horizontalBias = horizontal.toFloat()

            params.width = circleLocations[circleIndex][2] as Int
            params.height = circleLocations[circleIndex][3] as Int
            imageCircle.layoutParams = params

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

    private fun plotAudioSignal(
        plot: XYPlot,
        audioSignal: DoubleArray,
        title: String,
        color: Int
    ) {
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
        var minValue =
            audioSignal.minOrNull() ?: 0.0  // Get the minimum value in the signal
        var maxValue =
            audioSignal.maxOrNull() ?: 0.0  // Get the maximum value in the signal

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