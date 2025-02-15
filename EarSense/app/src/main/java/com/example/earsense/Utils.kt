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
        fun readAudioFromFile(
            audioFile: File,
            sampleRate: Int,
            channelConfig: Int,
            audioEncoding: Int
        ): List<Short> {
            val minBufferSize =
                AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
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
        fun saveModelToFile(
            model: KNN<DoubleArray>,
            profileName: String,
            filesDir: File,
            filename: String
        ): Boolean {
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
        fun loadModelFromFile(
            profileName: String,
            filesDir: File,
            filename: String
        ): KNN<DoubleArray>? {
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

        @JvmStatic
        fun saveDoubleArrayToFile(
            doubleArrays: Array<DoubleArray>,
            fileDir: File,
            profileName: String,
            fileName: String
        ) {
            return try {
                val directory = File(fileDir, "$profileName/trainingFeatures")

                if (!directory.exists()) directory.mkdirs()

                val file = File(directory, fileName)

                val fileOutputStream = FileOutputStream(file)
                val objectOutputStream = ObjectOutputStream(fileOutputStream)

                objectOutputStream.writeObject(doubleArrays)

                // Log location saved to
                Log.d("Utils.saveDoubleArrayToFile", "Saved to: ${file.absolutePath}")

                // Close the streams
                objectOutputStream.close()
                fileOutputStream.close()

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JvmStatic
        fun saveIntArrayToFile(
            intArrays: IntArray,
            fileDir: File,
            profileName: String,
            fileName: String
        ) {
            return try {
                val directory = File(fileDir, "$profileName/trainingLabels")
                // Create directory if doesn't exist
                if (!directory.exists()) directory.mkdirs()

                val file = File(directory, fileName)

                val fileOutputStream = FileOutputStream(file)
                val objectOutputStream = ObjectOutputStream(fileOutputStream)

                objectOutputStream.writeObject(intArrays)

                // Close the streams
                objectOutputStream.close()
                fileOutputStream.close()

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JvmStatic
        fun readDoubleArrayFromFile(
            fileDir: File,
            profileName: String,
            fileName: String
        ): Array<DoubleArray> {
            return try {
                val file = File(fileDir, "$profileName/trainingFeatures/$fileName")

                val fileInputStream = FileInputStream(file)
                val objectInputStream = ObjectInputStream(fileInputStream)

                val array = objectInputStream.readObject() as Array<DoubleArray>

                // Close the streams
                objectInputStream.close()
                fileInputStream.close()

                array
            } catch (e: Exception) {
                e.printStackTrace()
                emptyArray()

            }
        }

        @JvmStatic
        fun readIntArrayFromFile(fileDir: File, profileName: String, fileName: String): IntArray {
            return try {
                val file = File(fileDir, "$profileName/trainingLabels/$fileName")

                val fileInputStream = FileInputStream(file)
                val objectInputStream = ObjectInputStream(fileInputStream)

                val array = objectInputStream.readObject() as IntArray

                // Close the streams
                objectInputStream.close()
                fileInputStream.close()

                array
            } catch (e: Exception) {
                e.printStackTrace()
                intArrayOf()
            }
        }

        @JvmStatic
        fun euclideanDistance(signal1: DoubleArray, signal2: DoubleArray): Double {
            var sum = 0.0
            for (i in signal1.indices) {
                sum += Math.pow(signal1[i] - signal2[i], 2.0)
            }
            return Math.sqrt(sum)
        }

        @JvmStatic
        fun cosineSimilarity(signal1: DoubleArray, signal2: DoubleArray): Double {
            var dotProduct = 0.0
            var normSignal1 = 0.0
            var normSignal2 = 0.0

            for (i in signal1.indices) {
                dotProduct += signal1[i] * signal2[i]
                normSignal1 += signal1[i] * signal1[i]
                normSignal2 += signal2[i] * signal2[i]
            }

            return dotProduct / (Math.sqrt(normSignal1) * Math.sqrt(normSignal2))
        }

    }
}