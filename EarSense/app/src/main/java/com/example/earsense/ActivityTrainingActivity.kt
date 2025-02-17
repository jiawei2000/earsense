package com.example.earsense

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
import org.jtransforms.fft.FloatFFT_1D
import java.io.File

val activityTypes = arrayOf("Walking", "Running", "Speaking", "Still")

class ActivityTrainingActivity : AppCompatActivity() {

    val sampleRate = 16000
    val channelConfig = AudioFormat.CHANNEL_OUT_MONO
    val audioEncoding = AudioFormat.ENCODING_PCM_16BIT

    lateinit var viewPager: ViewPager2

    lateinit var currentProfile: String

    lateinit var plot: XYPlot

    var maxY = 0.0
    var minY = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_activity_training)

        //Tool bar
        val toolBar: MaterialToolbar = findViewById(R.id.materialToolbar)
        setSupportActionBar(toolBar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        toolBar.setTitleTextColor(ContextCompat.getColor(this, R.color.white))
        supportActionBar?.title = "Activity Recognition Training"
        toolBar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // View Pager
        viewPager = findViewById(R.id.viewPager)
        val pageAdapter = ActivityScreenSlidePagerAdapter(this)
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
                buttonNext.isEnabled = position != activityTypes.size - 1
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

        // Plot
        plot = findViewById(R.id.plot)
        plot.graph.marginLeft = 20f
        plot.graph.paddingLeft = 100f

//        plot.graph.marginBottom = 0f
//        plot.legend.isVisible = false

        currentProfile = Utils.getCurrentProfile(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    fun trainModel() {
        val audioDatas = mutableListOf<DoubleArray>()
        val trainingFeatures = mutableListOf<DoubleArray>()
        val trainingLabels = mutableListOf<Int>()

        // Read data from all files and to create training data
        for (activityName in activityTypes) {
            val filePath = filesDir.absolutePath + "/$currentProfile/activity/$activityName.pcm"
            val audioFile = File(filePath)
            val audioData =
                Utils.readAudioFromFile(audioFile, sampleRate, channelConfig, audioEncoding)

            //Process Audio Data
            val doubleAudioData = audioData.map { it.toDouble() }.toDoubleArray()

//            val lowpassAudioData = DoubleArray(doubleAudioData.size)
            // Apply low-pass filter
//            val filter =
//                PassFilter(50.toFloat(), sampleRate, PassFilter.PassType.Lowpass, 1.toFloat())
//            for (i in doubleAudioData.indices) {
//                filter.Update(doubleAudioData[i].toFloat())
//                lowpassAudioData[i] = filter.getValue().toDouble()
//            }

            audioDatas.add(doubleAudioData)

            var totalEnergy = 0.0
            var totalMax = 0.0
            var totalVariance = 0.0
            var totalFFT = 0.0

            val windows = splitIntoWindows(doubleAudioData, sampleRate)

            for (window in windows) {
                // Calculate absolute sum of all values in the window
                val energy = window.sumOf { Math.abs(it) }
                totalEnergy += energy

                // Calculate max amplitude
                val maxAmplitude = window.max()
                totalMax += maxAmplitude

                // Calculate variance
                val mean = window.average()
                val variance = window.map { it - mean }.sumOf { it * it }
                totalVariance += variance


                // Apply ftt
                val floatFFTSegment = window.map { it.toFloat() }.toFloatArray()
                val fft = FloatFFT_1D(floatFFTSegment.size.toLong())
                fft.realForward(floatFFTSegment)
                val doubleFFTAudioData = floatFFTSegment.map { it.toDouble() }.toDoubleArray()

                // find index of maximum value
                val maxIndex = doubleFFTAudioData.withIndex().maxByOrNull { it.value }?.index
                totalFFT += maxIndex ?: 0
            }

            // Calculate average energy
            val averageEnergy = totalEnergy / windows.size

            // Calulate average max amplitude
            val averageMax = totalMax / windows.size

            // Calculate average variance
            val averageVariance = totalVariance / windows.size

            // Calculate average FFT
            val averageFFT = totalFFT / windows.size

            // Log averageFFT
            Log.d("AAAAAAAA", "Average FFT: $averageFFT")

            trainingFeatures.add(doubleArrayOf(averageEnergy))
            trainingLabels.add(activityTypes.indexOf(activityName))
        }

        // Save data to file
        val featuresArray = trainingFeatures.toTypedArray()
        val labelsArray = trainingLabels.toIntArray()
        Utils.saveDoubleArrayToFile(featuresArray, filesDir, currentProfile, "activity")
        Utils.saveIntArrayToFile(labelsArray, filesDir, currentProfile, "activity")

        // Test data with index
        var totalCorrect = 0
        var totalCount = 0


        val walkingData = audioDatas[0]
        // Dive walking Data into
        val walkingWindows = splitIntoWindows(walkingData, 1280)
        //Log no winodws
        Log.d("AAAAAAA", "Windows size: ${walkingWindows.size}")
        val usedPeakAmplitudes = mutableListOf<Double>()
        var stepCount = 0

        for (window in walkingWindows) {
            val lowpassSegment = DoubleArray(window.size)
            // Apply low-pass filter
            val filter =
                PassFilter(
                    50.toFloat(),
                    sampleRate,
                    PassFilter.PassType.Lowpass,
                    1.toFloat()
                )
            for (i in window.indices) {
                filter.Update(window[i].toFloat())
                lowpassSegment[i] = filter.getValue().toDouble()
            }

            val peaks = findPeaks(lowpassSegment, 1500.0)

            Log.d("AAAAAAAA", "Peaks: ${peaks.toList()}")


            var lastPeak = 0
            var lastValidPeak = 0

            for (peak in peaks) {
                val distance = peak - lastPeak
                lastPeak = peak
                // Only allow peak if not too close to last peak and at the edge of audioData or peak is repeated
                if (distance > 2000 && peak > 10000 && peak < 37360) {
                    lastValidPeak = peak
                }
            }

            if (lastValidPeak != 0) {
                // Check if peak is repeated
                if (usedPeakAmplitudes.contains(window[lastValidPeak])) {
                    continue
                } else {
                    stepCount++
                    usedPeakAmplitudes.add(window[lastValidPeak])
                }
            }

        }
        Log.d("AAAAAAA", "Steps: $stepCount")

        plotAudioSignal(plot, walkingData, "Walking", Color.RED)



        for (i in audioDatas.indices) {
            // Split into windows
            val windows = splitIntoWindows(audioDatas[i], sampleRate)
            for (window in windows) {
                totalCount++
                val prediction = makePrediction(window)
                if (prediction == i) {
                    totalCorrect++
                }
//                Log.d(
//                    "AAAAAAAAA",
//                    "Actual: ${activityTypes[i]}, Prediction: ${activityTypes[prediction]}"
//                )
            }
        }

        val accuracy = totalCorrect.toDouble() / totalCount
        Log.d("AAAAAAAAA", "Accuracy: $accuracy")

    }

    fun makePrediction(audioData: DoubleArray): Int {
        // Calculate energy
        val energy = audioData.sumOf { Math.abs(it) }
        val stillEnergyCutOff = 3000000.0

        // Apply ftt
        val speakingFFTCutoff = 120

        val floatFFTSegment = audioData.map { it.toFloat() }.toFloatArray()
        val fft = FloatFFT_1D(floatFFTSegment.size.toLong())
        fft.realForward(floatFFTSegment)
        val doubleFFTAudioData = floatFFTSegment.map { it.toDouble() }.toDoubleArray()
        // find index of maximum value
        val maxIndex = doubleFFTAudioData.withIndex().maxByOrNull { it.value }?.index

        if (energy < stillEnergyCutOff) {
            return 3 // Still
        }

        if (maxIndex != null && maxIndex > speakingFFTCutoff) {
            return 2 // Speaking
        }

        if (energy < 10000000) {
            return 0 // Walking
        } else {
            return 1 // Running
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

//        val minValue = -17000
//        val maxValue = 19000

        if (minValue < minY) {
            minY = minValue
        }
        if (maxValue > maxY) {
            maxY = maxValue
        }

        minValue = minY
        maxValue = maxY

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

class ActivityScreenSlidePagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
    override fun getItemCount(): Int = activityTypes.size

    override fun createFragment(position: Int): Fragment {

        val step1 = ActivityTrainingFragment.newInstance(activityTypes[0])
        val step2 = ActivityTrainingFragment.newInstance(activityTypes[1])
        val step3 = ActivityTrainingFragment.newInstance(activityTypes[2])
        val step4 = ActivityTrainingFragment.newInstance(activityTypes[3])

        return when (position) {
            0 -> step1
            1 -> step2
            2 -> step3
            3 -> step4
            else -> BreathingTrainingFragment()
        }
    }
}