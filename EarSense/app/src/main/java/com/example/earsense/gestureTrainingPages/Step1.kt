package com.example.earsense.gestureTrainingPages

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import com.example.earsense.R
import com.example.earsense.WaveFormView
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException


class Step1 : Fragment() {

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
    var waveFormView: WaveFormView? = null
    var audioManager: AudioManager? = null

    val locationToTap = "forehead"
    var timesToTap: Int? = null

    var textTimer: TextView? = null
    var textInstructions: TextView? = null

    var countDownTimer: CountDownTimer? = null

    private lateinit var outputFile: File
    private lateinit var outputStream: FileOutputStream

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var bluetoothDevice: AudioDeviceInfo? = null

        //Select bluetooth earbuds as audio source
        for (device in audioManager!!.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            //Log all devices
            Log.d("Device", device.productName.toString())
            if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                bluetoothDevice = device
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager!!.setCommunicationDevice(bluetoothDevice)
                } else {
                    audioManager!!.startBluetoothSco()
                }
                audioManager!!.setBluetoothScoOn(true)
                //log device
                Log.d("Device", bluetoothDevice.productName.toString())
                break
            }
        }

    }

    private fun startRecording() {
        // Start recording in a separate thread
        Thread {
            try {
                // Request permission to record audio
                if (ActivityCompat.checkSelfPermission(
                        requireContext(),
                        android.Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        requireActivity(),
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

                outputFile = File(requireContext().filesDir, "recorded_audio.pcm")
                outputStream = FileOutputStream(outputFile)

                while (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val audioData = ShortArray(bufferSize)

                    val readResult = audioRecord?.read(audioData, 0, audioData.size)

                    if (readResult != null && readResult > 0) {
                        //Calculate amplitude
                        val amplitude = audioData.maxOrNull()
                        waveFormView!!.addAmplitude(amplitude!!.toFloat())

                        //Save to file
                        val byteArray = ShortArrayToByteArray(audioData)
                        outputStream.write(byteArray, 0, readResult * 2) // 2 bytes per sample
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }.start()

        startCountDownTimer()
    }

    private fun ShortArrayToByteArray(shortArray: ShortArray): ByteArray {
        val byteArray = ByteArray(shortArray.size * 2) // 2 bytes per short
        for (i in shortArray.indices) {
            byteArray[i * 2] = (shortArray[i].toInt() and 0xFF).toByte()
            byteArray[i * 2 + 1] = (shortArray[i].toInt() shr 8 and 0xFF).toByte()
        }
        return byteArray
    }

    // Stop recording audio
    private fun stopRecording() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
            waveFormView!!.clear()
            outputStream.flush()
            outputStream.close()
            audioManager?.stopBluetoothSco()
            audioManager?.isBluetoothScoOn = false
        } catch (e: RuntimeException) {
            e.printStackTrace()
        }
    }

    override fun onStop() {
        super.onStop()
        stopRecording()
    }

    private fun startCountDownTimer() {
        countDownTimer = object : CountDownTimer(((timesToTap!! + 1) * 1000).toLong(), 1000) {
            override fun onTick(millisUntilFinished: Long) {
                textTimer?.text = "" + millisUntilFinished / 1000
            }

            override fun onFinish() {
                textTimer?.text = "Done"
                stopRecording()
            }
        }
        countDownTimer?.start()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_step1, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        timesToTap = requireContext().resources.getInteger(R.integer.timesToTap)

        // Start recording when the button is clicked
        val buttonStartRecording: Button = getView()?.findViewById(R.id.buttonStart) as Button
        buttonStartRecording.setOnClickListener {
            startRecording()
        }

        // Stop recording when the button is clicked
        val buttonStopRecording: Button = getView()?.findViewById(R.id.buttonStop) as Button
        buttonStopRecording.setOnClickListener {
            stopRecording()
            countDownTimer?.cancel()
            textTimer?.text = "Stopped"
        }

        // WaveFormView
        waveFormView = getView()?.findViewById(R.id.waveformView) as WaveFormView

        textTimer = getView()?.findViewById(R.id.textTimer) as TextView
        textInstructions = getView()?.findViewById(R.id.textInstructions) as TextView

        // Instructions text
        textInstructions!!.text =
            "Tap your $locationToTap $timesToTap times at 1 second intervals"
        // Timer text
        textTimer!!.text = "Press Start to begin"
    }

}