package com.example.earsense

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.setPadding
import com.androidplot.xy.BoundaryMode
import com.androidplot.xy.LineAndPointFormatter
import com.androidplot.xy.XYPlot
import com.androidplot.xy.XYSeries
import com.github.psambit9791.jdsp.signal.peaks.FindPeak
import com.google.android.material.appbar.MaterialToolbar
import org.apache.commons.math3.stat.correlation.PearsonsCorrelation
import org.jtransforms.fft.FloatFFT_1D
import smile.classification.KNN
import smile.math.distance.EuclideanDistance
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.ObjectInputStream

class GestureActivity : AppCompatActivity() {

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

    var currentProfile = ""

    lateinit var plot: XYPlot
    var maxGraph = 0.0
    var minGraph = 0.0

    lateinit var debugTextView: TextView

    lateinit var imageCircle: ImageView

    val gestures = arrayOf("jaw", "left temple", "right temple")
    val circleLocations = arrayOf(
        arrayOf(0.99, 0.51, 120, 120),
        arrayOf(0.33, 0.40, 120, 120),
        arrayOf(0.33, 0.65, 120, 120)
    )

    lateinit var signals: Array<DoubleArray>
    lateinit var fttSignals: Array<DoubleArray>
    lateinit var lowpassSignals: Array<DoubleArray>
    val trainingLabels = arrayOf(0,0,0,0,0,1,1,1,1,1,2,2,2,2,2)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_gestures)

        currentProfile = Utils.getCurrentProfile(this)

        val toolBar: MaterialToolbar = findViewById(R.id.materialToolbar)
        setSupportActionBar(toolBar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        toolBar.setTitleTextColor(ContextCompat.getColor(this, R.color.white))
        supportActionBar?.title = "Gestures"
        //Back button
        toolBar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // Train Model Button
        val buttonTrainModel: Button = findViewById(R.id.buttonTrainModel)
        buttonTrainModel.setOnClickListener {
            val intent = Intent(this, NewGestureTrainingActivity::class.java)
            startActivity(intent)
        }

        // Debug Button
        val buttonDebug: Button = findViewById(R.id.buttonDebug)
        buttonDebug.setOnClickListener {
            val intent = Intent(this, GesturesDebugActivity::class.java)
            startActivity(intent)
        }

        // Start Button
        val buttonStart: Button = findViewById(R.id.buttonStart)
        buttonStart.setOnClickListener {
            buttonStart()
        }

        // Stop Button
        val buttonStop: Button = findViewById(R.id.buttonStop)
        buttonStop.setOnClickListener {
            stopRecording()
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Debug Text View
        debugTextView = findViewById(R.id.textDebug)

        // Circle Image
        imageCircle = findViewById(R.id.imageCircle)

        // Plot
        plot = findViewById(R.id.plot)
        plot.graph.marginLeft = 0f
        plot.graph.paddingLeft = 0f
        plot.graph.marginBottom = 0f
        plot.graph.paddingBottom = 0f
        plot.legend.isVisible = false
    }

    fun buttonStart() {
        // Load trainingFeatures
        signals = Utils.readDoubleArrayFromFile(filesDir, currentProfile, "gestures")
        lowpassSignals = Utils.readDoubleArrayFromFile(filesDir, currentProfile, "gesturesLowpass")
        fttSignals = Utils.readDoubleArrayFromFile(filesDir, currentProfile, "gesturesFTT")

        setRecordingDeviceToBluetooth(this)
        startRecording()
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

                audioRecord.startRecording()

                var runningAudioData = mutableListOf<Double>()
                var runningLowPass = mutableListOf<Double>()

                var bufferCount = 0

                while (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val audioData = ShortArray(bufferSize)

                    val readResult = audioRecord.read(audioData, 0, audioData.size)

                    if (readResult > 0) {
                        bufferCount++
                        // Clear after no readings for a while
                        if (bufferCount == 25) {
                            runOnUiThread {
                                // Display circle
                                val params =
                                    imageCircle.layoutParams as ConstraintLayout.LayoutParams

                                params.verticalBias = 0f
                                params.horizontalBias = 0f
                                params.width = 1
                                params.height = 1

                                plot.clear()
                                plot.redraw()
                                debugTextView.text = "Prediction: X"
                            }
                        }

                        val doubleAudioData = audioData.map { it.toDouble() }.toDoubleArray()

                        runningAudioData.addAll(doubleAudioData.toList())

                        val lowpassSegment = DoubleArray(doubleAudioData.size)
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
                            lowpassSegment[i] = filter.getValue().toDouble()
                        }

                        runningLowPass.addAll(lowpassSegment.toList())

                        // If runningAudioData exceeds 3 seconds, remove the oldest bufferSize
                        if (runningAudioData.size > 3 * sampleRate) {
                            runningAudioData = runningAudioData.drop(bufferSize).toMutableList()
                        }

                        // If runningLowPass exceeds 3 seconds, remove oldest bufferSize
                        if (runningLowPass.size > 3 * sampleRate) {
                            runningLowPass = runningLowPass.drop(bufferSize).toMutableList()
                        }

                        val runningAudioArray = runningAudioData.toDoubleArray()
                        val runningLowPassArray = runningLowPass.toDoubleArray()


                        // Only process if got running audio data is full (3 seconds)
                        // 47360 is the current buffer size
                        if (runningLowPassArray.size < 47360) {
                            continue
                        } else {
                            // Check if low pass amplitude is above threshold
                            val lowpassMax = runningLowPass.maxOrNull() ?: 0.0
                            val minAmplitude = 800.0

                            if (lowpassMax < minAmplitude) {
                                continue
                            }

                            // Find peaks
                            val peaks = findPeaks(runningLowPassArray, minAmplitude)

                            // For each peak find distance from last peak
                            var lastPeak = 0
                            var activePeaks = 0
                            val listOfPeaks = mutableListOf<Int>()
                            for (peak in peaks) {
                                val distance = peak - lastPeak
                                lastPeak = peak
                                // Reject peak if too close to last peak or at the edge of audioData
                                if (distance > 5000 && peak > 10000 && peak < 37360) {
                                    activePeaks++
                                    listOfPeaks.add(peak)
                                }
                            }

                            // Extract the latest peak
                            val latestPeak = listOfPeaks.lastOrNull() ?: continue
                            val lowpassSignal = extractSegmentAroundPeak(runningLowPassArray, latestPeak)
                            val signal = extractSegmentAroundPeak(runningAudioArray, latestPeak)

                            //Apply FTT to signal
                            val floatFTTSegment = signal.map { it.toFloat() }.toFloatArray()
                            val fft = FloatFFT_1D(floatFTTSegment.size.toLong())
                            fft.realForward(floatFTTSegment)
                            val fttSignal = floatFTTSegment.map { it.toDouble() }.toDoubleArray()

                            // Set buffer count to 0 so wont clear segment
                            bufferCount = 0
//                          Plot segment
                            runOnUiThread {
                                plot.clear()
                                plotAudioSignal(lowpassSignal, "", Color.RED)
                            }


                            // Predict using features
                            val euclideanScores = mutableListOf<Double>()
                            val cosineScores = mutableListOf<Double>()
                            val pearsonCorrelationScores = mutableListOf<Double>()

                            // Count which label has the most votes
                            val votes = IntArray(3)

                            // Calculate distance between extracted FTT signal and all training signals
                            for (i in 0 until fttSignals.size) {
                                val eculideanScore =
                                    Utils.euclideanDistance(fttSignal, fttSignals[i])
                                euclideanScores.add(eculideanScore)

                                val cosineScore = Utils.cosineSimilarity(fttSignal, fttSignals[i])
                                cosineScores.add(cosineScore)

                                val pearsonCorrelationScore = PearsonsCorrelation().correlation(fttSignal, fttSignals[i])
                                pearsonCorrelationScores.add(pearsonCorrelationScore)
                            }

//                            // Sort and log 3 lowest euclidean scores with labels
                            val sortedIndices = euclideanScores.indices.sortedBy { euclideanScores[it] }
                            for (i in 0 until 3) {
                                votes[trainingLabels[sortedIndices[i]]]++
                                val originalIndex = euclideanScores.indexOf(
                                    euclideanScores[sortedIndices[i]]
                                )
                                Log.d(
                                    "AAAAAA",
                                    "Eculedian: ${euclideanScores[sortedIndices[i]]}, Label: ${trainingLabels[sortedIndices[i]]}, Original Index: $originalIndex"
                                )
                            }

                            // Sort and log highest 1 cosine score
                            val sortedIndicesCosine =
                                cosineScores.indices.sortedByDescending { cosineScores[it] }
                            for (i in 0 until 1) {
                                votes[trainingLabels[sortedIndicesCosine[i]]]++
                                val originalIndex = cosineScores.indexOf(
                                    cosineScores[sortedIndicesCosine[i]]
                                )
                                Log.d(
                                    "AAAAAA",
                                    "Cosine: ${cosineScores[sortedIndicesCosine[i]]}, Label: ${trainingLabels[sortedIndicesCosine[i]]} , Original Index: $originalIndex}"
                                )
                            }
//
//                            // Sort and log highest 1 pearson correlation score
                            val sortedIndicesPearson =
                                pearsonCorrelationScores.indices.sortedByDescending { pearsonCorrelationScores[it] }
                            for (i in 0 until 1) {
                                votes[trainingLabels[sortedIndicesPearson[i]]]++
                                val originalIndex = pearsonCorrelationScores.indexOf(
                                    pearsonCorrelationScores[sortedIndicesPearson[i]]
                                )
                                Log.d(
                                    "AAAAAA",
                                    "Pearson: ${pearsonCorrelationScores[sortedIndicesPearson[i]]}, Label: ${trainingLabels[sortedIndicesPearson[i]]} , Original Index: $originalIndex}"
                                )
                            }


                            // Find the label with the most votes
                            val prediction = votes.indexOf(votes.max())
                            Log.d("AAAAAA", "Votes: ${votes.toList()}")

                            //Update Debug textView
                            runOnUiThread {
                                // Display circle
                                val params =
                                    imageCircle.layoutParams as ConstraintLayout.LayoutParams
                                val vertical = circleLocations[prediction][0] as Double
                                val horizontal = circleLocations[prediction][1] as Double

                                params.verticalBias = vertical.toFloat()
                                params.horizontalBias = horizontal.toFloat()

                                params.width = circleLocations[prediction][2] as Int
                                params.height = circleLocations[prediction][3] as Int
                                imageCircle.layoutParams = params
                                debugTextView.text = "Prediction: ${gestures[prediction]}"
                            }
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

    fun extractSegmentAroundPeak(segment: DoubleArray, peakIndex: Int): DoubleArray {
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
        if (end > segment.size) {
            start -= (end - segment.size) // Extend start
            end = segment.size
        }

        // Extract the segment
        return segment.copyOfRange(start.coerceAtLeast(0), end.coerceAtMost(segment.size))
    }

    fun findPeaks(audioData: DoubleArray, minPeakAmplitude: Double): IntArray {
        val fp = FindPeak(audioData)

        // Detect peaks
        val peaks = fp.detectPeaks().peaks

        //Filter peaks based on minimum amplitude
        val filteredPeaks = peaks.filter { audioData[it] >= minPeakAmplitude }.toIntArray()
        return filteredPeaks
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
            null, // Point color (none in this case)
            null, // Fill color (none in this case)
            null  // Background color (none)
        )

        // Step 3: Add the series to the plot
        plot.addSeries(series, seriesFormat)

        // Step 5: Adjust the range and domain of the plot
        // Y - AXIS
        var minValue = audioSignal.minOrNull() ?: 0.0  // Get the minimum value in the signal
        var maxValue = audioSignal.maxOrNull() ?: 0.0  // Get the maximum value in the signal

        if (minValue < minGraph) {
            minGraph = minValue
        }

        if (maxValue > maxGraph) {
            maxGraph = maxValue
        }

//        val minValue = -17000
//        val maxValue = 19000

//      Add a small buffer around the data to ensure it doesn't touch the axis edges
        val padding = 0.1 * (maxValue - minValue)  // 10% padding
        plot.setRangeBoundaries(
            minValue - padding,
            maxValue + padding,
            BoundaryMode.FIXED
        ) // Y-axis range


        // X - AXIS
        plot.setDomainBoundaries(
            0,
            audioSignal.size.toFloat(),
//            500.0,
            BoundaryMode.FIXED
        ) // Set X-axis range based on the signal size

        // Step 6: Redraw the plot to reflect changes
        plot.redraw()
    }
}
