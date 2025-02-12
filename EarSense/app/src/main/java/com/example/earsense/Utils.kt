package com.example.earsense

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.media.AudioTrack
import android.util.Log
import smile.classification.KNN
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class Utils {
    companion object {
        @JvmStatic
        fun getCurrentProfile(context: Context): String {
            val prefs = context.getSharedPreferences("prefs", MODE_PRIVATE)
            val currentProfile = prefs.getString("currentProfile", profiles[0]) ?: ""
            return currentProfile
        }

        @JvmStatic
        fun shortArrayToByteArray(shortArray: ShortArray): ByteArray {
            val byteArray = ByteArray(shortArray.size * 2) // 2 bytes per short
            for (i in shortArray.indices) {
                byteArray[i * 2] = (shortArray[i].toInt() and 0xFF).toByte()
                byteArray[i * 2 + 1] = (shortArray[i].toInt() shr 8 and 0xFF).toByte()
            }
            return byteArray
        }

        @JvmStatic
        fun byteArrayToShortArray(byteArray: ByteArray): ShortArray {
            val shortArray = ShortArray(byteArray.size / 2) // 2 bytes per short
            for (i in shortArray.indices) {
                shortArray[i] =
                    ((byteArray[i * 2 + 1].toInt() shl 8) or (byteArray[i * 2].toInt() and 0xFF)).toShort()
            }
            return shortArray
        }

        @JvmStatic
        fun readAudioFromFile(audioFile: File, sampleRate: Int, channelConfig: Int, audioEncoding: Int): List<Short> {
            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
            val audioData = mutableListOf<Short>()
            val inputFile = FileInputStream(audioFile)
            try {
                val byteArray = ByteArray(minBufferSize)
                var bytesRead: Int
                while (inputFile.read(byteArray).also { bytesRead = it } != -1) {
                    // Convert byteArray to shortArray for 16-bit data
                    val shortArray = byteArrayToShortArray(byteArray)
                    // Add short array to audioData
                    audioData.addAll(shortArray.toList())
                }
            } catch (e: IOException) {
                Log.d("ERROR: Utils.readAudioFromFile", "$e.message")
            } finally {
                inputFile.close()

            }
            return audioData
        }

        @JvmStatic
        fun saveModelToFile(model: KNN<DoubleArray>, profileName: String, filesDir: File, filename: String): Boolean {
            return try {
                val modelFile = getModelFile(profileName, filesDir, filename)
                ObjectOutputStream(FileOutputStream(modelFile)).use { it.writeObject(model) }
                true
            } catch (e: IOException) {
                e.printStackTrace()
                false
            }
        }

        @JvmStatic
        fun loadModelFromFile(profileName: String, filesDir: File, filename: String): KNN<DoubleArray>? {
            return try {
                val modelFile = getModelFile(profileName, filesDir, filename)
                ObjectInputStream(FileInputStream(modelFile)).use { it.readObject() as KNN<DoubleArray> }
            } catch (e: IOException) {
                e.printStackTrace()
                null
            }
        }

        private fun getModelFile(profileName: String, filesDir: File, filename: String): File {
            val directory = File(filesDir, "$profileName/models")
            if (!directory.exists()) directory.mkdirs()

            return File(directory, filename)
        }
    }
}