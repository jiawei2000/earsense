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
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import com.androidplot.xy.BoundaryMode
import com.androidplot.xy.LineAndPointFormatter
import com.androidplot.xy.XYPlot
import com.androidplot.xy.XYSeries
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class gestureTrainingFragment : Fragment() {

    val sampleRate = 16000
    val channelConfig = AudioFormat.CHANNEL_IN_MONO
    val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
    val audioSource = MediaRecorder.AudioSource.MIC
    val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate, channelConfig, audioEncoding
    )

    lateinit var audioRecord: AudioRecord
    lateinit var audioManager: AudioManager

    lateinit var plot: XYPlot

    var locationToTap = ""
    var verticalBias = "0.0"
    var horizontalBias = "0.0"
    var width = "100"
    var height = "100"
    var instruction = ""

    var timesToTap = 0

    lateinit var textTimer: TextView
    lateinit var textInstructions: TextView

    var countDownTimerBuffer = 0
    lateinit var currentProfile: String

    lateinit var outputFile: File
    lateinit var outputStream: FileOutputStream

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get current profile
        currentProfile = Utils.getCurrentProfile(requireContext())

        // Get arguments from bundle
        val args = arguments
        if (args != null) {
            locationToTap = args.getString("locationToTap").toString()
            verticalBias = args.getString("verticalBias").toString()
            horizontalBias = args.getString("horizontalBias").toString()
            width = args.getString("width").toString()
            height = args.getString("height").toString()
            instruction = args.getString("instruction").toString()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_gesture_training, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        timesToTap = requireContext().resources.getInteger(R.integer.timesToTap)

        // Start Button
        val buttonStartRecording: Button = view.findViewById(R.id.buttonStart) as Button
        buttonStartRecording.setOnClickListener {
            setRecordingDeviceToBluetooth(requireContext())
            startRecording()
        }

        // Stop Button
        val buttonStopRecording: Button = view.findViewById(R.id.buttonStop) as Button
        buttonStopRecording.setOnClickListener {
            stopRecording()
            textTimer.text = "Stopped"
        }

        // Image Circle position and size
        val imageCircle: ImageView = view.findViewById(R.id.imageCircle)
        val params = imageCircle.layoutParams as ConstraintLayout.LayoutParams
        params.verticalBias = verticalBias.toFloat()
        params.horizontalBias = horizontalBias.toFloat()
        params.width = width.toInt()
        params.height = height.toInt()
        imageCircle.layoutParams = params

        // Plot
        plot = view.findViewById(R.id.plot)
        plot.graph.marginLeft = 0f
        plot.graph.paddingLeft = 0f
        plot.graph.marginBottom = 0f
        plot.graph.paddingBottom = 0f
        plot.legend.isVisible = false

        textTimer = view.findViewById(R.id.textTimer)
        textInstructions = view.findViewById(R.id.textInstructions)

        // Instructions text
        textInstructions.text = instruction
        // Timer text
        textTimer.text = "Press Start to begin"
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
                val directory = File(requireContext().filesDir, "$currentProfile/$locationToTap")
                if (!directory.exists()) {
                    directory.mkdirs()
                }

                outputFile = File(
                    requireContext().filesDir, "$currentProfile/$locationToTap/recorded_audio.pcm"
                )
                // Create the file if it doesn't exist
                if (!outputFile.exists()) {
                    outputFile.createNewFile() // Create the file if it doesn't exist
                }

                outputStream = FileOutputStream(outputFile)

                var runningDoubleAudioData = mutableListOf<Double>()

                // 5 Seconds
                countDownTimerBuffer = (timesToTap + 1) * sampleRate

                while (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val audioData = ShortArray(bufferSize)

                    val readResult = audioRecord.read(audioData, 0, audioData.size)

                    countDownTimerBuffer -= bufferSize
                    Log.d ("AAAAAAAAA", countDownTimerBuffer.toString())

                    if (countDownTimerBuffer / sampleRate > 0){
                        textTimer.post(Runnable {
                            textTimer.text = (countDownTimerBuffer / sampleRate).toString()
                        })
                    }


                    if (countDownTimerBuffer <= 0) {
                        textTimer.post(Runnable {
                            textTimer.text = "Done"
                        })
                        stopRecording()
                        break
                    }

                    if (readResult > 0) {
                        //Process Audio Data
                        val doubleAudioArray = audioData.map { it.toDouble() }.toDoubleArray()

                        // Only store 3 seconds of audio data
                        runningDoubleAudioData.addAll(doubleAudioArray.toList())
                        if (runningDoubleAudioData.size > sampleRate * 3) {
                            // Remove oldest buffer size
                            runningDoubleAudioData = runningDoubleAudioData.drop(bufferSize).toMutableList()
                        }

                        val runningDoubleAudioArray = runningDoubleAudioData.toDoubleArray()

                        // Update plot
                        plot.clear()
                        plotAudioSignal(plot, runningDoubleAudioArray, "", Color.RED)

                        //Save to file
                        val byteArray = Utils.shortArrayToByteArray(audioData)
                        outputStream.write(byteArray, 0, readResult * 2) // 2 bytes per sample
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }.start()
    }

    // Stop recording audio
    private fun stopRecording() {
        if (this::audioRecord.isInitialized && audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            audioRecord.stop()
            audioRecord.release()
            outputStream.flush()
            outputStream.close()
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
            plot.clear()
        }
    }

    override fun onStop() {
        super.onStop()
        stopRecording()
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