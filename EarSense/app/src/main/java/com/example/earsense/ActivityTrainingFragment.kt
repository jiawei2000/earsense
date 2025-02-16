package com.example.earsense

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.view.marginLeft
import com.androidplot.xy.BoundaryMode
import com.androidplot.xy.LineAndPointFormatter
import com.androidplot.xy.XYPlot
import com.androidplot.xy.XYSeries
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

// TODO: Rename parameter arguments, choose names that match
private const val ARG_PARAM1 = "ACTIVITY_TYPE"

class ActivityTrainingFragment : Fragment() {
    // TODO: Rename and change types of parameters
    private var activityName: String? = null

    val sampleRate = 16000
    val channelConfig = AudioFormat.CHANNEL_IN_MONO
    val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
    val audioSource = MediaRecorder.AudioSource.MIC
    val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate, channelConfig, audioEncoding
    )

    lateinit var currentProfile: String

    lateinit var audioRecord: AudioRecord
    lateinit var audioManager: AudioManager

    lateinit var plot1: XYPlot

    lateinit var countDownTimer: CountDownTimer
    lateinit var textTimer: TextView
    lateinit var textInstruction: TextView

    lateinit var outputFile: File
    lateinit var outputStream: FileOutputStream

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            activityName = it.getString(ARG_PARAM1)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_activity_training, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Start button
        val startButton: Button = view.findViewById(R.id.buttonStart)
        startButton.setOnClickListener {
            startCountDownTimer(20)
            setRecordingDeviceToBluetooth(requireContext())
            startRecording()
        }

        // Stop button
        val stopButton: Button = view.findViewById(R.id.buttonStop)
        stopButton.setOnClickListener {
            stopCountDownTimer()
            stopRecording()
        }


        // Set text title
        val textTitle: TextView = view.findViewById(R.id.textTitle)
        textTitle.text = activityName

        // Set text instructions
        textInstruction = view.findViewById(R.id.textInstructions) as TextView
        textInstruction.text = "Do this activity for 20 seconds"

        // Timer
        textTimer = view.findViewById(R.id.textTimer)

        // Plot
        plot1 = view.findViewById(R.id.plot1)
        plot1.graph.marginLeft = 0f
        plot1.graph.marginBottom = 0f
        plot1.legend.isVisible = false

        currentProfile = Utils.getCurrentProfile(requireContext())
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

    fun startRecording() {
        // Start recording in a separate thread
        Thread {
            try {
                // Request permission to record audio
                if (ActivityCompat.checkSelfPermission(
                        requireContext(), android.Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        requireActivity(), arrayOf(android.Manifest.permission.RECORD_AUDIO), 1
                    )
                }
                audioRecord = AudioRecord(
                    audioSource, sampleRate, channelConfig, audioEncoding, bufferSize
                )
                audioRecord.startRecording()

                // Create a directory to store the recorded audio
                val directory = File(requireContext().filesDir, "$currentProfile/activity")
                if (!directory.exists()) {
                    directory.mkdirs()
                }

                outputFile =
                    File(requireContext().filesDir, "$currentProfile/activity/$activityName.pcm")
                // Create the file if it doesn't exist
                if (!outputFile.exists()) {
                    outputFile.createNewFile() // Create the file if it doesn't exist
                }

                outputStream = FileOutputStream(outputFile)

                var runningDoubleAudioData = mutableListOf<Double>()

                while (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val audioData = ShortArray(bufferSize)

                    val readResult = audioRecord.read(audioData, 0, audioData.size)

                    if (readResult > 0) {
                        //Process Audio Data
                        val doubleAudioArray = audioData.map { it.toDouble() }.toDoubleArray()

                        // Add audio data to running list
                        runningDoubleAudioData.addAll(doubleAudioArray.toList())
                        if (runningDoubleAudioData.size > sampleRate * 3) {
                            // Remove oldest buffer size
                            runningDoubleAudioData = runningDoubleAudioData.drop(bufferSize).toMutableList()
                        }

                        // Update plot
                        plot1.clear()
                        plotAudioSignal(plot1, runningDoubleAudioData.toDoubleArray(), "", Color.RED)

                        //Save to file
                        val byteArray = Utils.shortArrayToByteArray(audioData)
                        outputStream.write(byteArray, 0, readResult * 2) // 2 bytes per sample
                    }
                }
            } catch (e: IOException) {
                Log.d("ERROR in ActivityTrainingFragment", e.toString())
            }
        }.start()
    }

    fun stopRecording() {
        if (this::audioRecord.isInitialized) {
            audioRecord.stop()
            audioRecord.release()
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
            plot1.clear()
        }
    }

    private fun startCountDownTimer(seconds: Int) {
        countDownTimer = object : CountDownTimer((seconds * 1000).toLong(), 1000) {
            override fun onTick(millisUntilFinished: Long) {
                textTimer.text = "" + millisUntilFinished / 1000
            }

            override fun onFinish() {
                textTimer.text = "Done"
                stopRecording()
            }
        }
        countDownTimer.start()
    }

    fun stopCountDownTimer() {
        countDownTimer.cancel()
        textTimer.text = "Stopped"
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

    companion object {
        // TODO: Rename and change types and number of parameters
        @JvmStatic
        fun newInstance(activityName: String) =
            ActivityTrainingFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PARAM1, activityName)
                }
            }
    }
}