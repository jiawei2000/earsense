package com.example.earsense.gestureTrainingPages

import android.content.Context
import android.content.pm.PackageManager
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
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import com.example.earsense.R
import com.example.earsense.WaveFormView
import java.io.IOException


class Step1 : Fragment() {

    val sampleRate = 44100
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

    private val outputFilePath = "recorded_audio.pcm"

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

                val audioData = ShortArray(bufferSize)
//                Log.d("bufferSize", bufferSize.toString());

//                val outputStream = FileOutputStream(outputFilePath)

                while (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val readResult = audioRecord?.read(audioData, 0, audioData.size)

                    if (readResult != null && readResult > 0) {
//                        outputStream.write(audioData, 0, readResult)
                        //Calculate amplitude
                        val amplitude = audioData.maxOrNull()

//                        Log.d("tester", amplitude.toString());
                        waveFormView!!.addAmplitude(amplitude!!.toFloat())
                    }
                }
//                outputStream.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }.start()
    }

    // Stop recording audio
    private fun stopRecording() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
            waveFormView!!.clear()
        } catch (e: RuntimeException) {
            e.printStackTrace()
        }
    }

    override fun onStop() {
        super.onStop()
        stopRecording() // Make sure to stop and release when the activity stops
        audioManager?.stopBluetoothSco()
        audioManager?.isBluetoothScoOn = false
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

        // Start recording when the button is clicked
        val buttonStartRecording: Button = getView()?.findViewById(R.id.buttonStart) as Button
        buttonStartRecording.setOnClickListener {
            startRecording()
        }

        // Stop recording when the button is clicked
        val buttonStopRecording: Button = getView()?.findViewById(R.id.buttonStop) as Button
        buttonStopRecording.setOnClickListener {
            stopRecording()
        }

        // WaveFormView
        waveFormView = getView()?.findViewById(R.id.waveformView) as WaveFormView
    }

}