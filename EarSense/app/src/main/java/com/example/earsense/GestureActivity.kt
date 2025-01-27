package com.example.earsense

import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import java.io.File
import java.io.FileInputStream
import java.io.IOException

class GestureActivity : AppCompatActivity() {

    val sampleRate = 16000
    val channelConfig = AudioFormat.CHANNEL_OUT_MONO
    val audioEncoding = AudioFormat.ENCODING_PCM_16BIT

    private lateinit var audioTrack: AudioTrack
    private lateinit var inputFile: FileInputStream

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

        // Audio Playback
        val filePath = filesDir.absolutePath + "/recorded_audio.pcm"
        val buttonPlayAudio: Button = findViewById(R.id.buttonPlay)
        buttonPlayAudio.setOnClickListener {
            startPlayback(File(filePath))
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    fun startPlayback(audioFile: File) {
        inputFile = FileInputStream(audioFile)
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

        audioTrack.play()

        var shortArray = ShortArray(minBufferSize / 2)
        val byteArray = ByteArray(minBufferSize)

        Thread {
            var bytesRead: Int
            try {
                while (inputFile.read(byteArray).also { bytesRead = it } != -1
                ) {
                    // Convert byteArray to shortArray for 16-bit data
                    shortArray = ByteArrayToShortArray(byteArray)
                    audioTrack.write(shortArray, 0, bytesRead / 2)
                }
            } catch (e: IOException) {
                Log.d("GestureActivity:Playback", e.toString())
            } finally {
                stopPlayback()
            }
        }.start()
    }

    fun stopPlayback() {
        audioTrack.stop()
        audioTrack.release()
        inputFile.close()
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