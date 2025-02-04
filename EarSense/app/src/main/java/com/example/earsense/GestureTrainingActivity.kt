package com.example.earsense

import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import org.jtransforms.fft.FloatFFT_1D
import smile.classification.KNN
import smile.math.distance.EuclideanDistance
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.ObjectOutputStream

class GestureTrainingActivity : AppCompatActivity() {
    var viewPager: ViewPager2? = null

    // Must match locations in ScreenSlidePagerAdapter
    val locations = arrayOf("forehead", "left cheek", "right cheek")

    val sampleRate = 16000
    val channelConfig = AudioFormat.CHANNEL_OUT_MONO
    val audioEncoding = AudioFormat.ENCODING_PCM_16BIT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_gesture_training)

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

        //ViewPager
        viewPager = findViewById(R.id.viewPager)
        val pageAdapter = ScreenSlidePagerAdapter(this)
        viewPager?.adapter = pageAdapter
        viewPager?.isUserInputEnabled = false

        //Next button
        val buttonNext: Button = findViewById(R.id.buttonNext)
        buttonNext.setOnClickListener {
            viewPager?.currentItem = viewPager?.currentItem?.plus(1) ?: 0
        }

        //Back button
        val buttonBack: Button = findViewById(R.id.buttonBack)
        buttonBack.setOnClickListener {
            viewPager?.currentItem = viewPager?.currentItem?.minus(1) ?: 0
        }

        // Done button
        val buttonDone: Button = findViewById(R.id.buttonDone)
        buttonDone.setOnClickListener {
            buttonDebug()
        }

        // Disable back button on first page
        viewPager?.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                buttonBack.isEnabled = position != 0
            }
        })
        //Disable next button on last page
        viewPager?.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                buttonNext.isEnabled = position != 2
            }
        })

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    fun buttonDebug() {
        val trainingFeatures = mutableListOf<DoubleArray>()
        val trainingLabels = mutableListOf<Int>()

        // Read data from all files and to create training data
        for (location in locations) {
            val filePath = filesDir.absolutePath + "/$location/recorded_audio.pcm"
            //Log File path
            Log.d("AAAAAAA", "File path: $filePath")
            val audioFile = File(filePath)
            val audioData = readFile(audioFile)

            //Process Audio Data
            val windowSize = sampleRate // 1 second window
            val stepSize = windowSize / 2 // 50% overlap

            val windows = slidingWindow(audioData.toShortArray(), windowSize, stepSize)
            val segments = mutableListOf<ShortArray>()

            for (window in windows) {
                val segment = extractSegmentAroundPeak(window)
                segments.add(segment)
                // Apply low-pass filter
                val filteredSegment = lowPassFilter(segment, sampleRate, 50.toFloat())

                //Apply FTT to segment
                // FFT expects FloatArray
                val floatSegment = filteredSegment.map { it.toFloat() }.toFloatArray()
//                val floatSegment = segment.map { it.toFloat() }.toFloatArray()
                val fft = FloatFFT_1D(floatSegment.size.toLong())
                fft.realForward(floatSegment)

                // Add to training features
                // Smile KNN Expects DoubleArray
                val doubleSegment = floatSegment.map { it.toDouble() }.toDoubleArray()
                trainingFeatures.add(doubleSegment)
                trainingLabels.add(locations.indexOf(location))
            }
        }// End of locations for loop

        // Train the model
        val model = trainKNNModel(trainingFeatures.toTypedArray(), trainingLabels.toIntArray(), 1)

        // Save the model to a file
        saveKNNModel(model!!)

        // Test the model using all segments
        var correctCount = 0
        for (i in 0 until trainingFeatures.size) {
            val testSegment = trainingFeatures[i]
            //Log test segment
            val prediction = model?.predict(testSegment)
            if (prediction == trainingLabels[i]) {
                correctCount++
            }
            Log.d("AAAAAAA", "Segment: $i, Prediction: $prediction, Actual: ${trainingLabels[i]}")
        }
        Log.d("BBBBBBB", "Correct Count: $correctCount")
        Log.d("BBBBBBB", "Accuracy: ${correctCount.toDouble() / trainingFeatures.size}")


        // Log number of training features
        Log.d("BBBBBBB", "Number of training features: ${trainingFeatures.size}")

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
            Log.d("ERROR: GestureTrainingActivity", "Error reading audio file: ${e.message}")
        } finally {
            inputFile.close()

        }
        return audioData
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

    fun saveKNNModel(model: KNN<DoubleArray>) {
        // Create a directory to store models
        val directory = File(filesDir, "models")
        if (!directory.exists()) {
            directory.mkdirs()
        }

        val file = File(filesDir, "models/knn_model")
        // Create the file if it doesn't exist
        if (!file.exists()) {
            file.createNewFile() // Create the file if it doesn't exist
        }
        ObjectOutputStream(FileOutputStream(file)).use { it.writeObject(model) }
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

class ScreenSlidePagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment {

        val step1 = gestureTrainingFragment()
        val bundle1 = Bundle()
        bundle1.putString("locationToTap", "forehead")
        bundle1.putString("verticalBias", "0.28")
        bundle1.putString("horizontalBias", "0.5")
        bundle1.putString("width", "300")
        bundle1.putString("height", "200")
        step1.arguments = bundle1

        val step2 = gestureTrainingFragment()
        val bundle2 = Bundle()
        bundle2.putString("locationToTap", "left cheek")
        bundle2.putString("verticalBias", "0.83")
        bundle2.putString("horizontalBias", "0.40")
        bundle2.putString("width", "150")
        bundle2.putString("height", "200")
        step2.arguments = bundle2

        val step3 = gestureTrainingFragment()
        val bundle3 = Bundle()
        bundle3.putString("locationToTap", "right cheek")
        bundle3.putString("verticalBias", "0.83")
        bundle3.putString("horizontalBias", "0.65")
        bundle3.putString("width", "150")
        bundle3.putString("height", "200")
        step3.arguments = bundle3

        return when (position) {
            0 -> step1
            1 -> step2
            2 -> step3
            else -> gestureTrainingFragment()
        }
    }
}


