package com.example.earsense

import android.content.Intent
import android.graphics.Color
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
import java.io.FileOutputStream
import java.io.ObjectOutputStream

class GestureTrainingActivity : AppCompatActivity() {
    lateinit var viewPager: ViewPager2

    // Must match locations in GestureScreenSlidePagerAdapter
    val locations = arrayOf("forehead", "left cheek", "right cheek")

    val sampleRate = 16000
    val channelConfig = AudioFormat.CHANNEL_OUT_MONO
    val audioEncoding = AudioFormat.ENCODING_PCM_16BIT

    var currentProfile = ""

    lateinit var plot: XYPlot

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
        viewPager.adapter = pageAdapter
        viewPager.isUserInputEnabled = false

        //Next button
        val buttonNext: Button = findViewById(R.id.buttonNext)
        buttonNext.setOnClickListener {
            viewPager.currentItem = viewPager.currentItem.plus(1) ?: 0
        }

        //Back button
        val buttonBack: Button = findViewById(R.id.buttonBack)
        buttonBack.setOnClickListener {
            viewPager.currentItem = viewPager.currentItem.minus(1) ?: 0
        }

        // Done button
        val buttonDone: Button = findViewById(R.id.buttonDone)
        buttonDone.setOnClickListener {
            buttonDebug()
        }

        // Disable back button on first page
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                buttonBack.isEnabled = position != 0
            }
        })
        //Disable next button on last page
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                buttonNext.isEnabled = position != 3
            }
        })

        // Plot for Debugging
        plot = findViewById(R.id.plot)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    fun buttonDebug() {
        val trainingFeatures = mutableListOf<DoubleArray>()
        val trainingFeaturesOriginal =  mutableListOf<DoubleArray>()
        val trainingLabels = mutableListOf<Int>()
        val euclideanScores = mutableListOf<Double>()
        val cosineScores = mutableListOf<Double>()
        val pearsonCorrelationScores = mutableListOf<Double>()

        var isDebug = 0

        // Read data from all files and to create training data
        for (location in locations) {
            val filePath = filesDir.absolutePath + "/$currentProfile/$location/recorded_audio.pcm"
            val audioFile = File(filePath)
            val audioData =
                Utils.readAudioFromFile(audioFile, sampleRate, channelConfig, audioEncoding)


            //Process Audio Data
            val doubleAudioData = audioData.map { it.toDouble() }.toDoubleArray()

            if (isDebug == 0) {
                plotAudioSignal(plot, doubleAudioData, "Audio Signal", Color.RED)
            }

            if (isDebug == 1) {
                plotAudioSignal(plot, doubleAudioData, "Audio Signal", Color.YELLOW)
            }

            if (isDebug == 2) {
                plotAudioSignal(plot, doubleAudioData, "Audio Signal", Color.GREEN)
            }

            isDebug++

            var windows = splitIntoWindows(doubleAudioData, sampleRate)

            // Drop first and last windows
            windows = windows.drop(1)
            windows = windows.dropLast(1)

            Log.d("AAAAAA", "Number of windows: ${windows.size}")

            for (window in windows) {

                // Make new array
                val lowpassSegment = DoubleArray(window.size)
                // Process segment data
                // Apply low-pass filter
                val filter =
                    PassFilter(50.toFloat(), sampleRate, PassFilter.PassType.Lowpass, 1.toFloat())
                for (i in window.indices) {
                    filter.Update(window[i].toFloat())
                    lowpassSegment[i] = filter.getValue().toDouble()
                }

                val extractedSegment = extractSegmentAroundPeak(lowpassSegment)
                trainingFeaturesOriginal.add(extractedSegment)

                //Apply FTT to segment
                // FFT expects FloatArray
                val floatSegment = extractedSegment.map { it.toFloat() }.toFloatArray()
                val fft = FloatFFT_1D(floatSegment.size.toLong())
                fft.realForward(floatSegment)

                // Add to training features
                // Smile KNN Expects DoubleArray
                val doubleSegment = floatSegment.map { it.toDouble() }.toDoubleArray()

                trainingFeatures.add(doubleSegment)
                trainingLabels.add(locations.indexOf(location))
            }
        }// End of locations for loop


        // TESTING RESULTS
        val testSegment = trainingFeatures[7]
        // Calculate distance between testSegment and all training segments
        for (i in 0 until trainingFeatures.size) {
            val eculideanScore = Utils.euclideanDistance(testSegment, trainingFeatures[i])
            euclideanScores.add(eculideanScore)

            val cosineScore = Utils.cosineSimilarity(testSegment, trainingFeatures[i])
            cosineScores.add(cosineScore)

            val pearsonCorrelationScore =
                PearsonsCorrelation().correlation(testSegment, trainingFeatures[i])
            pearsonCorrelationScores.add(pearsonCorrelationScore)
        }
        // Sort and log lowest 5 euclidean score
        val sortedIndices = euclideanScores.indices.sortedBy { euclideanScores[it] }
        for (i in 0 until 5) {
            Log.d(
                "AAAAAA",
                "Eculedian: ${euclideanScores[sortedIndices[i]]}, Label: ${trainingLabels[sortedIndices[i]]}"
            )
        }

        // Sort and log highest 5 cosine score
        val sortedIndicesCosine = cosineScores.indices.sortedByDescending { cosineScores[it] }
        for (i in 0 until 5) {
            Log.d(
                "AAAAAA",
                "Cosine: ${cosineScores[sortedIndicesCosine[i]]}, Label: ${trainingLabels[sortedIndicesCosine[i]]}"
            )
        }

        // Sort and log highest 5 pearson correlation score
        val sortedIndicesPearson =
            pearsonCorrelationScores.indices.sortedByDescending { pearsonCorrelationScores[it] }
        for (i in 0 until 5) {
            Log.d(
                "AAAAAA",
                "Pearson: ${pearsonCorrelationScores[sortedIndicesPearson[i]]}, Label: ${trainingLabels[sortedIndicesPearson[i]]}"
            )
        }

        // Save the trainingFeatures and trainingLabels to a file
        val featuresArray = trainingFeatures.toTypedArray()
        val featuresArrayOriginal = trainingFeaturesOriginal.toTypedArray()
        val labelsArray = trainingLabels.toIntArray()

        Utils.saveDoubleArrayToFile(featuresArray, filesDir, currentProfile, "gesture")
        Utils.saveDoubleArrayToFile(featuresArrayOriginal, filesDir, currentProfile, "gestureOriginal")
        Utils.saveIntArrayToFile(labelsArray, filesDir, currentProfile, "gesture")

        // Go to GestureActivity
        val intent = Intent(this, GestureActivity::class.java)
        startActivity(intent)


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


