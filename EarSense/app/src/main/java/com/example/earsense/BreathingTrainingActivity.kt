package com.example.earsense

import android.media.AudioFormat
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
import com.github.psambit9791.jdsp.filter.FIRWin1
import com.github.psambit9791.jdsp.signal.peaks.FindPeak
import com.google.android.material.appbar.MaterialToolbar
import smile.classification.KNN
import smile.math.distance.EuclideanDistance
import java.io.File

val breathingModes = arrayOf("nose", "mouth")

class BreathingTrainingActivity : AppCompatActivity() {

    lateinit var viewPager: ViewPager2

    val sampleRate = 16000
    val channelConfig = AudioFormat.CHANNEL_OUT_MONO
    val audioEncoding = AudioFormat.ENCODING_PCM_16BIT

    lateinit var currentProfile: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_breathing_training)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        //Tool bar
        val toolBar: MaterialToolbar = findViewById(R.id.materialToolbar)
        setSupportActionBar(toolBar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        toolBar.setTitleTextColor(ContextCompat.getColor(this, R.color.white))
        supportActionBar?.title = "Breathing Training"
        toolBar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // View Pager
        viewPager = findViewById(R.id.viewPager)
        val pageAdapter = BreathingScreenSlidePagerAdapter(this)
        viewPager.adapter = pageAdapter
        viewPager.isUserInputEnabled = false

        //Next button
        val buttonNext: Button = findViewById(R.id.buttonNext)
        buttonNext.setOnClickListener {
            viewPager.currentItem = viewPager.currentItem.plus(1)
        }
        //Disable next button on last page
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                buttonNext.isEnabled = position != breathingModes.size - 1
            }
        })

        //Back button
        val buttonBack: Button = findViewById(R.id.buttonBack)
        buttonBack.setOnClickListener {
            viewPager.currentItem = viewPager.currentItem.minus(1)
        }
        // Disable back button on first page
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                buttonBack.isEnabled = position != 0
            }
        })

        // Done button
        val buttonDone: Button = findViewById(R.id.buttonTrain)
        buttonDone.setOnClickListener {
            trainModel()
        }

        currentProfile = Utils.getCurrentProfile(this)
    }

    fun trainModel() {
        val trainingFeatures = mutableListOf<DoubleArray>()
        val trainingLabels = mutableListOf<Int>()

        for (breathingMode in breathingModes) {
            val filePath = filesDir.absolutePath + "/$currentProfile/breathing/$breathingMode.pcm"
            val audioFile = File(filePath)

            val audioData =
                Utils.readAudioFromFile(audioFile, sampleRate, channelConfig, audioEncoding)


            // Process Audio Data
            val doubleAudioArray = audioData.map { it.toDouble() }.toDoubleArray()
            // Apply High pass filter
//            val filteredAudioData = applyHighPassFilter(
//                doubleAudioArray, sampleRate.toDouble(), 500.0
//            )

            // Apply High Pass Filter
            val filter = PassFilter(500.toFloat(), sampleRate, PassFilter.PassType.Highpass, 1.toFloat())
            for(i in doubleAudioArray.indices){
                filter.Update(doubleAudioArray[i].toFloat())
                doubleAudioArray[i] = filter.getValue().toDouble()
            }


            // Divide the audio data into windows of 1 second
            val windows = splitIntoWindows(doubleAudioArray, sampleRate)

            var isInhale = true

            for (window in windows) {

                // Extract segment around peak
                val segment = extractSegmentAroundPeak(window)

                trainingFeatures.add(segment)
                if (isInhale) {
                    trainingLabels.add((breathingModes.indexOf(breathingMode) * 2 + 1))
                    isInhale = false
                } else {
                    trainingLabels.add(breathingModes.indexOf(breathingMode) * 2)
                    isInhale = true
                }

            }

        }

        //Log training features size
        Log.d("AAAAAAA", "Training Features: ${trainingFeatures.size}")
        //Log training labels size
        Log.d("AAAAAAA", "Training Labels: ${trainingLabels.size}")

        // Log all training features
        for (i in 0 until trainingFeatures.size) {
            // sort then log top 5 values of each segment
            val sortedSegment = trainingFeatures[i].sortedArrayDescending()
            Log.d("AAAAAAA", "Segment $i: ${sortedSegment.sliceArray(0 until 5).contentToString()}")
        }

        // Train the model
        val model = trainKNNModel(trainingFeatures.toTypedArray(), trainingLabels.toIntArray(), 1)

        // Test the model
//        var correctCount = 0
//        for (i in 0 until trainingFeatures.size) {
//            val testSegment = trainingFeatures[i]
//            val prediction = model.predict(testSegment)
//            //Log segment and prediction prediction
//            Log.d("AAAAAAA", "Actual: ${trainingLabels[i]}, Prediction: $prediction")
//            if (prediction == trainingLabels[i]) {
//                correctCount++
//            }
//        }

//        Log.d("Accuracy", "${correctCount.toDouble() / trainingFeatures.size}")

        // Save Model
        val modelSaved = Utils.saveModelToFile(model, currentProfile, filesDir, "breathingModel")

        val loadedModel = Utils.loadModelFromFile(currentProfile, filesDir, "breathingModel")


        // Test the loaded model accuracy
        var correctCountLoaded = 0
        for (i in 0 until trainingFeatures.size) {
            val testSegment = trainingFeatures[i]
            val prediction = loadedModel?.predict(testSegment)
            //Log segment and prediction prediction
            Log.d("BBBBBBBB", "Actual: ${trainingLabels[i]}, Prediction: $prediction")
            if (prediction == trainingLabels[i]) {
                correctCountLoaded++
            }
        }
        // Log the accuracy
        Log.d("Accuracy", "${correctCountLoaded.toDouble() / trainingFeatures.size}")


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

    fun findPeaks(audioData: DoubleArray, minPeakAmplitude: Double): IntArray {
        val fp = FindPeak(audioData)
        // Detect peaks
        val peaks = fp.detectPeaks().peaks
        //Filter peaks based on minimum amplitude
        val filteredPeaks = peaks.filter { audioData[it] >= minPeakAmplitude }.toIntArray()
        return filteredPeaks
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

    fun trainKNNModel(features: Array<DoubleArray>, labels: IntArray, k: Int): KNN<DoubleArray> {
        return KNN.fit(features, labels, k, EuclideanDistance())
    }
}


class BreathingScreenSlidePagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
    override fun getItemCount(): Int = breathingModes.size

    override fun createFragment(position: Int): Fragment {

        val step1 = BreathingTrainingFragment.newInstance(breathingModes[0], "bb")
        val step2 = BreathingTrainingFragment.newInstance(breathingModes[1], "bb")

        return when (position) {
            0 -> step1
            1 -> step2
            else -> BreathingTrainingFragment()
        }
    }
}