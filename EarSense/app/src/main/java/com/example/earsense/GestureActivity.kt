package com.example.earsense

import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import org.jtransforms.fft.FloatFFT_1D
import smile.classification.KNN
import smile.math.distance.EuclideanDistance
import java.io.File
import java.io.FileInputStream
import java.io.IOException

class GestureActivity : AppCompatActivity() {

    val sampleRate = 16000
    val channelConfig = AudioFormat.CHANNEL_OUT_MONO
    val audioEncoding = AudioFormat.ENCODING_PCM_16BIT

    private lateinit var audioTrack: AudioTrack

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_gestures)

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
            val intent = Intent(this, GestureTrainingActivity::class.java)
            startActivity(intent)
        }

        // Audio Playback
        val filePath = filesDir.absolutePath + "/recorded_audio.pcm"
        val buttonPlayAudio: Button = findViewById(R.id.buttonPlay)
        buttonPlayAudio.setOnClickListener {
            buttonDebug()
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    fun buttonDebug() {
        val filePath = filesDir.absolutePath + "/recorded_audio.pcm"
        //Read file
        val audioFile = File(filePath)
        val audioData = readFile(audioFile)

        // Process audioData
        val windowSize = sampleRate // 1 second window
        val stepSize = windowSize / 2 // 50% overlap

        val windows = slidingWindow(audioData.toShortArray(), windowSize, stepSize)
        val segments = mutableListOf<ShortArray>()

        val trainingFeatures = mutableListOf<DoubleArray>()
        val trainingLabels = mutableListOf<Int>()

        // log number of windows
        Log.d("AAAAAAA", "Number of windows: ${windows.size}")
        for (window in windows) {
            val segment = extractSegmentAroundPeak(window)
            segments.add(segment)
            // Apply low-pass filter
            val filteredSegment = lowPassFilter(segment, sampleRate, 50.toFloat())

            //Apply FTT to segment
            // FFT expects FloatArray
            val floatSegment = filteredSegment.map { it.toFloat() }.toFloatArray()

            // Perform FFT
            val fft = FloatFFT_1D(floatSegment.size.toLong())
            fft.realForward(floatSegment)

            // Add to training features
            // Smile KNN Expects DoubleArray
            val doubleSegment = floatSegment.map { it.toDouble() }.toDoubleArray()
            trainingFeatures.add(doubleSegment)
            //Randomly assign label of 1 or 2
            trainingLabels.add((1..2).random())

        }

        // Train the model
        val model = trainKNNModel(trainingFeatures.toTypedArray(), trainingLabels.toIntArray(), 3)

        // Test the model
        val testSegment = trainingFeatures[0]
        val prediction = model?.predict(testSegment)
        Log.d("AAAAAAA", "Prediction: $prediction")

        //Play a certain segment
//        playAudio(segments[9].toList())
        //Play the whole audio
//        playAudio(audioData)
    }

    fun readFile(audioFile: File): List<Short> {
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            channelConfig,
            audioEncoding
        )
        val audioData = mutableListOf<Short>()
        val inputFile = FileInputStream(audioFile)
        try {
            val byteArray = ByteArray(minBufferSize)

            var bytesRead: Int
            while (inputFile.read(byteArray).also { bytesRead = it } != -1) {
                // Convert byteArray to shortArray for 16-bit data
                val shortArray = ByteArrayToShortArray(byteArray)
                // Add short array to audioData
                audioData.addAll(shortArray.toList())
            }
        } catch (e: IOException) {
            Log.d("GestureActivity", "Error reading audio file: ${e.message}")
        } finally {
            inputFile.close()

        }
        return audioData
    }

    fun playAudio(audioData: List<Short>) {
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            channelConfig,
            audioEncoding
        )

        audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            channelConfig,
            audioEncoding,
            minBufferSize,
            AudioTrack.MODE_STREAM
        )

        val shortArray = ShortArray(minBufferSize / 2)
        val audioStream = audioData.toShortArray()
        audioTrack.play()

        Thread {
            try {
                var offset = 0
                while (offset < audioStream.size) {
                    val length = minOf(shortArray.size, audioStream.size - offset)
                    System.arraycopy(audioStream, offset, shortArray, 0, length)
                    audioTrack.write(shortArray, 0, length)
                    offset += length
                }
            } catch (e: IOException) {
                Log.d("GestureActivity:Playback", e.toString())
            } finally {
                stopAudio()
            }
        }.start()
    }

    fun stopAudio() {
        audioTrack.stop()
        audioTrack.release()
    }

    fun slidingWindow(audioData: ShortArray, windowSize: Int, stepSize: Int): List<ShortArray> {
        val windows = mutableListOf<ShortArray>()

        var start = 0
        while (start + windowSize <= audioData.size) {
            // Extract the window
            val window = audioData.copyOfRange(start, start + windowSize)
            windows.add(window)

            // Move the window forward by stepSize
            start += stepSize
        }

        return windows
    }

    fun lowPassFilter(input: ShortArray, sampleRate: Int, cutoffFreq: Float): ShortArray {
        val output = ShortArray(input.size)

        // Calculate alpha (filter coefficient)
        val dt = 1.0f / sampleRate
        val rc = 1.0f / (2 * Math.PI.toFloat() * cutoffFreq)
        val alpha = dt / (rc + dt)

        var previousOutput = 0f

        for (i in input.indices) {
            val currentInput = input[i].toFloat()
            val filteredValue = alpha * currentInput + (1 - alpha) * previousOutput
            output[i] = filteredValue.toInt().toShort()
            previousOutput = filteredValue
        }

        return output
    }

    fun trainKNNModel(features: Array<DoubleArray>, labels: IntArray, k: Int): KNN<DoubleArray>? {
        return KNN.fit(features, labels, k, EuclideanDistance())
    }

    fun extractSegmentAroundPeak(window: ShortArray): ShortArray {
        val segmentDuration = 0.4
        val segmentSize = (segmentDuration * sampleRate).toInt() // Number of samples in 0.4 seconds
        val peakIndex = window.indices.maxByOrNull { window[it].toInt() } ?: 0 // Find peak index

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

    private fun ByteArrayToShortArray(byteArray: ByteArray): ShortArray {
        val shortArray = ShortArray(byteArray.size / 2) // 2 bytes per short
        for (i in shortArray.indices) {
            shortArray[i] =
                ((byteArray[i * 2 + 1].toInt() shl 8) or (byteArray[i * 2].toInt() and 0xFF)).toShort()
        }
        return shortArray
    }
}