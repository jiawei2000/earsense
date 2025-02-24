package com.example.earsense

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.androidplot.xy.BoundaryMode
import com.androidplot.xy.LineAndPointFormatter
import com.androidplot.xy.XYPlot
import com.androidplot.xy.XYSeries
import com.google.android.material.appbar.MaterialToolbar

class GesturesDebugActivity : AppCompatActivity() {

    lateinit var plot1: XYPlot
    lateinit var plot2: XYPlot
    lateinit var plot3: XYPlot

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_gestures_debug)

        val toolBar: MaterialToolbar = findViewById(R.id.materialToolbar)
        setSupportActionBar(toolBar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        toolBar.setTitleTextColor(ContextCompat.getColor(this, R.color.white))
        supportActionBar?.title = "Debug Gestures"
        //Back button
        toolBar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // Plots
        plot1 = findViewById(R.id.plot1)
        plot1.graph.marginLeft = 0f
        plot1.graph.paddingLeft = 0f
        plot1.graph.marginBottom = 0f
        plot1.graph.paddingBottom = 0f
        plot1.legend.isVisible = false

        plot2 = findViewById(R.id.plot2)
        plot2.graph.marginLeft = 0f
        plot2.graph.paddingLeft = 0f
        plot2.graph.marginBottom = 0f
        plot2.graph.paddingBottom = 0f
        plot2.legend.isVisible = false

        plot3 = findViewById(R.id.plot3)
        plot3.graph.marginLeft = 0f
        plot3.graph.paddingLeft = 0f
        plot3.graph.marginBottom = 0f
        plot3.graph.paddingBottom = 0f
        plot3.legend.isVisible = false

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    override fun onStart() {
        super.onStart()

        val currentProfile = Utils.getCurrentProfile(this)

        val trainingFeaturesOriginal =
            Utils.readDoubleArrayFromFile(filesDir, currentProfile, "gestures")

        val trainingLabels = Utils.readIntArrayFromFile(filesDir, currentProfile, "gesture")


        Log.d("GesturesDebugActivity", "Training Labels: ${trainingLabels.toList()}")

        if (trainingFeaturesOriginal.size < 9) {
            Log.d(
                "GesturesDebugActivity",
                "Not enough training features: ${trainingFeaturesOriginal.size}"
            )

        } else {
            val colorOrder = arrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
            // Plot all training features
            for (i in 0 until 5) {
                val trainingFeature = trainingFeaturesOriginal[i]
                plotAudioSignal(plot1, trainingFeature, "", Color.RED)
            }

            for (i in 5 until 10) {
                val trainingFeature = trainingFeaturesOriginal[i]
                plotAudioSignal(plot2, trainingFeature, "", Color.RED)
            }

            for (i in 10 until 15) {
                val trainingFeature = trainingFeaturesOriginal[i]
                plotAudioSignal(plot3, trainingFeature, "", Color.RED)
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
            color, null, null, null
        )

        // Add the series to the plot
        plot.addSeries(series, seriesFormat)

        // Adjust the range and domain of the plot
        // Y-AXIS
        val minValue = audioSignal.minOrNull() ?: 0.0  // Get the minimum value in the signal
        val maxValue = audioSignal.maxOrNull() ?: 0.0  // Get the maximum value in the signal

        // Add a small buffer around the data to ensure it doesn't touch the axis edges
        val padding = 0.1 * (maxValue - minValue)  // 10% padding
        plot.setRangeBoundaries(
            minValue - padding, maxValue + padding, BoundaryMode.FIXED
        )

        // X-AXIS
        plot.setDomainBoundaries(
            0, audioSignal.size.toFloat(), BoundaryMode.FIXED
        )

        plot.redraw()
    }
}