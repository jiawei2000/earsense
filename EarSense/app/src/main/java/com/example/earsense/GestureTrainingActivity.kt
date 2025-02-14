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

    // Must match locations in GestureScreenSlidePagerAdapter
    val locations = arrayOf("forehead", "left cheek", "right cheek")

    val sampleRate = 16000
    val channelConfig = AudioFormat.CHANNEL_OUT_MONO
    val audioEncoding = AudioFormat.ENCODING_PCM_16BIT

    var currentProfile = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_gesture_training)

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

        //ViewPager
        viewPager = findViewById(R.id.viewPager)
        val pageAdapter = GestureScreenSlidePagerAdapter(this)
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
                buttonNext.isEnabled = position != 3
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
        val eculdianScores = mutableListOf<Double>()

        // Read data from all files and to create training data
        for (location in locations) {
            val filePath = filesDir.absolutePath + "/$currentProfile/$location/recorded_audio.pcm"
            val audioFile = File(filePath)
            val audioData =
                Utils.readAudioFromFile(audioFile, sampleRate, channelConfig, audioEncoding)

            //Process Audio Data
            val doubleAudioData = audioData.map { it.toDouble() }.toDoubleArray()
            val windows = splitIntoWindows(doubleAudioData, sampleRate)

            for (window in windows) {
                val segment = extractSegmentAroundPeak(window)

                // Find max amplitude
                val maximumAmplitude = segment.maxOrNull() ?: 0.0

                // if maximum amplitude is less than 700, skip this segment
                if (maximumAmplitude < 700) {
                    continue
                }

                // Process segment data
                // Apply low-pass filter
                val filter =
                    PassFilter(50.toFloat(), sampleRate, PassFilter.PassType.Lowpass, 1.toFloat())
                for (i in segment.indices) {
                    filter.Update(segment[i].toFloat())
                    segment[i] = filter.getValue().toDouble()
                }

                //Apply FTT to segment
                // FFT expects FloatArray
                val floatSegment = segment.map { it.toFloat() }.toFloatArray()
                val fft = FloatFFT_1D(floatSegment.size.toLong())
                fft.realForward(floatSegment)

                // Add to training features
                // Smile KNN Expects DoubleArray
                val doubleSegment = floatSegment.map { it.toDouble() }.toDoubleArray()
                trainingFeatures.add(doubleSegment)
                trainingLabels.add(locations.indexOf(location))
            }
        }// End of locations for loop


        val model = trainKNNModel(trainingFeatures.toTypedArray(), trainingLabels.toIntArray(), 1)

        val testSegment = trainingFeatures[14]
        // Calculate distance between testSegment and all training segments
        for (i in 0 until trainingFeatures.size) {
            val distance = cosineSimilarity(testSegment, trainingFeatures[i])
            eculdianScores.add(distance)
        }
        // Sort and log highest 5 distances dsecending nd their corresponding
        val sortedIndices = eculdianScores.indices.sortedByDescending { eculdianScores[it] }
//        val sortedIndices = eculdianScores.indices.sortedBy { eculdianScores[it] }
        for (i in 0 until 5) {
            Log.d("AAAAAA", "Distance: ${eculdianScores[sortedIndices[i]]}, Label: ${trainingLabels[sortedIndices[i]]}")
        }

        // Save the model to a file
        Utils.saveModelToFile(model, currentProfile, filesDir, "gestureModel")

        val loadedModel = Utils.loadModelFromFile(currentProfile, filesDir, "gestureModel")


        // Test the model using all segments
        var correctCount = 0
        for (i in 0 until trainingFeatures.size) {
            val testSegment = trainingFeatures[i]
            //Log test segment
            val prediction = loadedModel?.predict(testSegment)
//            Log.d("AAAAAA", "Prediction: $prediction, Actual: ${trainingLabels[i]}")
            if (prediction == trainingLabels[i]) {
                correctCount++
            }
        }
        Log.d("AAAAAA", "Accuracy: ${correctCount.toDouble() / trainingFeatures.size}")
        //Log trianing features size
        Log.d("BBBBBB", "Training Features Size: ${trainingFeatures.size}")
    }

    fun splitIntoWindows(audioData: DoubleArray, samplingRate: Int): List<DoubleArray> {
        val windowSize = samplingRate // 1-second window
        val numWindows = (audioData.size + windowSize - 1) / windowSize
        val windows = mutableListOf<DoubleArray>()

        for (i in 0 until numWindows) {
            val startIdx = i * windowSize
            val endIdx = minOf(startIdx + windowSize, audioData.size) // Avoid overflow
            // if the window size is less than 1 second, pad with zeros
            if (endIdx - startIdx < windowSize) {
                val paddedWindow = DoubleArray(windowSize)
                audioData.copyInto(paddedWindow, 0, startIdx, endIdx)
                windows.add(paddedWindow)
                continue
            }
            windows.add(audioData.sliceArray(startIdx until endIdx))
        }
        return windows
    }


    fun trainKNNModel(features: Array<DoubleArray>, labels: IntArray, k: Int): KNN<DoubleArray> {
        return KNN.fit(features, labels, k, EuclideanDistance())
    }

    fun saveKNNModel(model: KNN<DoubleArray>) {
        // Create a directory to store models
        val directory = File(filesDir, "$currentProfile/models")
        if (!directory.exists()) {
            directory.mkdirs()
        }

        val file = File(filesDir, "$currentProfile/models/knn_model")
        // Create the file if it doesn't exist
        if (!file.exists()) {
            file.createNewFile() // Create the file if it doesn't exist
        }
        ObjectOutputStream(FileOutputStream(file)).use { it.writeObject(model) }
    }

    fun extractSegmentAroundPeak(window: DoubleArray): DoubleArray {
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

    fun euclideanDistance(signal1: DoubleArray, signal2: DoubleArray): Double {
        var sum = 0.0
        for (i in signal1.indices) {
            sum += Math.pow(signal1[i] - signal2[i], 2.0)
        }
        return Math.sqrt(sum)
    }

    fun cosineSimilarity(signal1: DoubleArray, signal2: DoubleArray): Double {
        var dotProduct = 0.0
        var normSignal1 = 0.0
        var normSignal2 = 0.0

        for (i in signal1.indices) {
            dotProduct += signal1[i] * signal2[i]
            normSignal1 += signal1[i] * signal1[i]
            normSignal2 += signal2[i] * signal2[i]
        }

        return dotProduct / (Math.sqrt(normSignal1) * Math.sqrt(normSignal2))
    }

}

class GestureScreenSlidePagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
    override fun getItemCount(): Int = 4

    override fun createFragment(position: Int): Fragment {

        val step1 = gestureTrainingFragment()
        val bundle1 = Bundle()
        bundle1.putString("locationToTap", "forehead")
        bundle1.putString("verticalBias", "0.28")
        bundle1.putString("horizontalBias", "0.5")
        bundle1.putString("width", "300")
        bundle1.putString("height", "200")
        bundle1.putString("instruction", "Tap your forehead 20 times at 1 second intervals")
        step1.arguments = bundle1

        val step2 = gestureTrainingFragment()
        val bundle2 = Bundle()
        bundle2.putString("locationToTap", "left cheek")
        bundle2.putString("verticalBias", "0.83")
        bundle2.putString("horizontalBias", "0.40")
        bundle2.putString("width", "150")
        bundle2.putString("height", "200")
        bundle2.putString("instruction", "Tap your left cheek 20 times at 1 second intervals")
        step2.arguments = bundle2

        val step3 = gestureTrainingFragment()
        val bundle3 = Bundle()
        bundle3.putString("locationToTap", "right cheek")
        bundle3.putString("verticalBias", "0.83")
        bundle3.putString("horizontalBias", "0.65")
        bundle3.putString("width", "150")
        bundle3.putString("height", "200")
        bundle3.putString("instruction", "Tap your right cheek 20 times at 1 second intervals")
        step3.arguments = bundle3

        val step4 = gestureTrainingFragment()
        val bundle4 = Bundle()
        bundle4.putString("locationToTap", "nothing")
        bundle4.putString("verticalBias", "0")
        bundle4.putString("horizontalBias", "0")
        bundle4.putString("width", "1")
        bundle4.putString("height", "1")
        bundle4.putString("instruction", "Do not tap anything for 20 seconds")
        step4.arguments = bundle4

        return when (position) {
            0 -> step1
            1 -> step2
            2 -> step3
            3 -> step4
            else -> gestureTrainingFragment()
        }
    }
}


