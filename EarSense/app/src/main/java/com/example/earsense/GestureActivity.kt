package com.example.earsense

import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
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
import java.io.ObjectInputStream

class GestureActivity : AppCompatActivity() {

    val sampleRate = 16000
    val channelConfig = AudioFormat.CHANNEL_IN_MONO
    val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
    val audioSource = MediaRecorder.AudioSource.MIC
    val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        channelConfig,
        audioEncoding
    )

    var audioRecord: AudioRecord? = null
    var audioManager: AudioManager? = null

    var waveFormView: WaveFormView? = null
    var debugTextView: TextView? = null

    private lateinit var knnModel: KNN<DoubleArray>

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

        // Debug Button
        val filePath = filesDir.absolutePath + "/recorded_audio.pcm"
        val buttonPlayAudio: Button = findViewById(R.id.buttonDebug)
        buttonPlayAudio.setOnClickListener {
            buttonDebug()
        }

        // Start Button
        val buttonStart: Button = findViewById(R.id.buttonStart)
        buttonStart.setOnClickListener {
            buttonStart()
        }

        // Stop Button
        val buttonStop: Button = findViewById(R.id.buttonStop)
        buttonStop.setOnClickListener {
            buttonStop()
        }

        // Waveform View
        waveFormView = findViewById(R.id.waveFormView)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Debug Text View
        debugTextView = findViewById(R.id.textDebug)

    }

    fun buttonDebug() {

    }

    fun buttonStart() {
        //Load KNN Model
        knnModel = loadKnnModel() ?: return
        startRecording()
    }

    fun buttonStop() {
        stopRecording()
        // Reset debug text
        debugTextView!!.text = "Prediction: X"
    }

    override fun onStop() {
        super.onStop()
        stopRecording()
    }

    private fun startRecording() {
        // Start recording in a separate thread
        Thread {
            try {
                // Request permission to record audio
                if (ActivityCompat.checkSelfPermission(
                        this,
                        android.Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(android.Manifest.permission.RECORD_AUDIO),
                        1
                    )
                }

                audioRecord = AudioRecord(
                    audioSource,
                    sampleRate,
                    channelConfig,
                    audioEncoding,
                    bufferSize
                )

                audioRecord?.startRecording()

                //Log bufferSize
                Log.d("AAAAAAAA", "Buffer Size: $bufferSize")

                var runningAudioData = mutableListOf<Short>()
                var predictions = mutableListOf<Int>()

                while (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val audioData = ShortArray(bufferSize)

                    val readResult = audioRecord?.read(audioData, 0, audioData.size)

                    if (readResult != null && readResult > 0) {
                        //Calculate amplitude
                        val amplitude = audioData.maxOrNull()
                        waveFormView!!.addAmplitude(amplitude!!.toFloat())
                        runningAudioData.addAll(audioData.toList())

                        // Only process Audio every 1 second
                        if (runningAudioData.size < 32000) {
                            continue
                        } else {
                            //Process Audio
                            //Extract segment around peak
                            val segment = extractSegmentAroundPeak(runningAudioData.toShortArray())
                            //Apply low-pass filter
                            val filteredSegment = lowPassFilter(segment, sampleRate, 50.toFloat())

                            //Apply FTT to segment
                            // FFT expects FloatArray
                            val floatSegment = filteredSegment.map { it.toFloat() }.toFloatArray()
                            val fft = FloatFFT_1D(floatSegment.size.toLong())
                            fft.realForward(floatSegment)

                            // Smile KNN Expects DoubleArray
                            val doubleSegment = floatSegment.map { it.toDouble() }.toDoubleArray()

                            //Predict using AudioData
                            val prediction = knnModel.predict(doubleSegment)
                            predictions.add(prediction)
                            // If predictions more than 5 remove oldest one
                            if (predictions.size > 5) {
                                predictions.removeAt(0)
                            }

                            //Update Debug textView
                            runOnUiThread {
                                debugTextView!!.text = "Prediction: ${predictions}"
                            }

                            //Log Prediction
                            Log.d("BBBBBBB", "Prediction: ${prediction}")
                            runningAudioData = mutableListOf<Short>()
                        }


                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }.start()

    }

    fun stopRecording() {
        audioRecord?.stop()
        audioRecord?.release()
        waveFormView!!.clear()
        audioManager?.stopBluetoothSco()
        audioManager?.isBluetoothScoOn = false
    }

    fun loadKnnModel(): KNN<DoubleArray>? {
        val file = File(filesDir, "models/knn_model")
        if (!file.exists()) {
            Log.d("ERROR: GestureActivity", "KNN Model file not found")
            return null
        }
        return ObjectInputStream(FileInputStream(file)).use { it.readObject() as KNN<DoubleArray> }
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