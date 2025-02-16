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
import com.google.android.material.appbar.MaterialToolbar
import java.io.File

val activityTypes = arrayOf("Walking", "Running", "Speaking", "Still")

class ActivityTrainingActivity : AppCompatActivity() {

    val sampleRate = 16000
    val channelConfig = AudioFormat.CHANNEL_OUT_MONO
    val audioEncoding = AudioFormat.ENCODING_PCM_16BIT

    lateinit var viewPager: ViewPager2

    lateinit var currentProfile: String

    lateinit var plot: XYPlot

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
        plot.graph.marginLeft = 0f
        plot.graph.marginBottom = 0f
        plot.legend.isVisible = false

        currentProfile = Utils.getCurrentProfile(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    fun trainModel() {
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
            val windows = splitIntoWindows(doubleAudioData, sampleRate)

            var totalEnergy = 0.0

            for (window in windows) {
                // Calculate absolute sum of all values in the window
                val energy = window.sumByDouble { Math.abs(it) }
                totalEnergy += energy

//                runOnUiThread {
//                    plot.clear()
//                    plotAudioSignal(plot, window, activityName, Color.RED)
//                }

            }

            // Calculate average energy
            val averageEnergy = totalEnergy / windows.size

            trainingFeatures.add(doubleArrayOf(averageEnergy))
            trainingLabels.add(activityTypes.indexOf(activityName))

        }
        // Log training features
        Log.d("AAAAAAA", "Training Features: $trainingFeatures")

        // Save data to file
        val featuresArray = trainingFeatures.toTypedArray()
        val labelsArray = trainingLabels.toIntArray()
        Utils.saveDoubleArrayToFile(featuresArray, filesDir, currentProfile, "activity")
        Utils.saveIntArrayToFile(labelsArray, filesDir, currentProfile, "activity")
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
        val minValue = audioSignal.minOrNull() ?: 0.0  // Get the minimum value in the signal
        val maxValue = audioSignal.maxOrNull() ?: 0.0  // Get the maximum value in the signal

//        val minValue = -17000
//        val maxValue = 19000

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