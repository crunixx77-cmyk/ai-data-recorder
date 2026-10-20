package com.example.datasetrecorder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.MotionEvent
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var btnRecord: Button
    private lateinit var btnList: Button
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private lateinit var tempCacheFile: File
    
    private val sampleRate = 24000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var bufferSize = 0

    private var speechRecognizer: SpeechRecognizer? = null
    private var transcribedText: String = "rekaman_ai"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnRecord = findViewById(R.id.btnRecord)
        btnList = findViewById(R.id.btnList)

        checkPermissions()
        bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            bufferSize = sampleRate * 2
        }

        setupSpeechRecognizer()

        btnRecord.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(this, "Izin mikrofon belum diberikan!", Toast.LENGTH_SHORT).show()
                        checkPermissions()
                        return@setOnTouchListener true
                    }

                    vibratePhone()
                    startAudioRecording()
                    startSpeechToText()
                    if (isRecording) {
                        btnRecord.text = "Merekam... Lepas untuk Selesai"
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isRecording) {
                        vibratePhone()
                        stopAudioRecording()
                        stopSpeechToText()
                    }
                    btnRecord.text = "Tekan & Tahan untuk Merekam"
                    true
                }
                else -> false
            }
        }

        btnList.setOnClickListener {
            showRecordingsDialog()
        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }
    }

    private fun vibratePhone() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(50)
        }
    }

    private fun setupSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {}
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        transcribedText = matches[0]
                            .lowercase(Locale.getDefault())
                            .replace(Regex("[^a-z0-9 ]"), "")
                            .replace(Regex("\\s+"), "_")
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startSpeechToText() {
        transcribedText = "rekaman_${System.currentTimeMillis()}"
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun stopSpeechToText() {
        speechRecognizer?.stopListening()
    }

    private fun startAudioRecording() {
        tempCacheFile = File(cacheDir, "temp_rec.pcm")

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord = null
                return
            }

            audioRecord?.startRecording()
            isRecording = true

            lifecycleScope.launch(Dispatchers.IO) {
                val data = ByteArray(bufferSize)
                val outputStream = FileOutputStream(tempCacheFile)
                try {
                    while (isRecording) {
                        val read = audioRecord?.read(data, 0, data.size) ?: 0
                        if (read > 0) {
                            outputStream.write(data, 0, read)
                        }
                    }
                } finally {
                    outputStream.close()
                }
            }
        } catch (e: Exception) {
            isRecording = false
            audioRecord?.release()
            audioRecord = null
        }
    }

    private fun stopAudioRecording() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
        } finally {
            audioRecord = null
        }

        lifecycleScope.launch {
            delay(600)
            showPreviewDialog()
        }
    }

    private fun showPreviewDialog() {
        if (!tempCacheFile.exists()) return

        var mediaPlayer: MediaPlayer? = null
        
        AlertDialog.Builder(this)
            .setTitle("Pratinjau Dataset AI")
            .setMessage("Transkripsi Otomatis:\n\"$transcribedText\"\n\nSilakan dengarkan atau pilih tindakan:")
            .setPositiveButton("Simpan") { _, _ ->
                mediaPlayer?.release()
                saveFinalWavFile()
            }
            .setNeutralButton("Putar Ulang") { _, _ ->
                try {
                    val previewWav = File(cacheDir, "preview.wav")
                    tempCacheFile.toNormalizedWavFile(previewWav, sampleRate, 1, 16)
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(previewWav.absolutePath)
                        prepare()
                        start()
                    }
                    Toast.makeText(this, "Memutar audio studio...", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Gagal memutar audio", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Batal") { _, _ ->
                mediaPlayer?.release()
                tempCacheFile.delete()
                Toast.makeText(this, "Rekaman dibuang", Toast.LENGTH_SHORT).show()
            }
            .setOnDismissListener {
                mediaPlayer?.release()
            }
            .show()
    }

    private fun saveFinalWavFile() {
        val safeName = if (transcribedText.isBlank()) "dataset_${System.currentTimeMillis()}" else transcribedText
        val finalFile = File(filesDir, "$safeName.wav")
        
        tempCacheFile.toNormalizedWavFile(finalFile, sampleRate, 1, 16)
        tempCacheFile.delete()

        Toast.makeText(this, "Disimpan: $safeName.wav", Toast.LENGTH_LONG).show()
    }

    private fun File.toNormalizedWavFile(targetWav: File, sampleRate: Int, channels: Int, bitRate: Int) {
        val bytes = this.readBytes()
        if (bytes.isEmpty()) return

        var maxAmplitude = 0.toShort()
        val shortBuffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        while (shortBuffer.hasRemaining()) {
            val sample = kotlin.math.abs(shortBuffer.get().toInt())
            if (sample > maxAmplitude) {
                maxAmplitude = sample.toShort()
            }
        }

        val normalizedBytes = if (maxAmplitude > 0 && maxAmplitude < 32767) {
            val gain = 30000.0 / maxAmplitude.toDouble()
            val outBuffer = java.nio.ByteBuffer.allocate(bytes.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            shortBuffer.rewind()
            while (shortBuffer.hasRemaining()) {
                val scaled = (shortBuffer.get() * gain).toInt().coerceIn(-32768, 32767).toShort()
                outBuffer.putShort(scaled)
            }
            outBuffer.array()
        } else {
            bytes
        }

        val pcmLength = normalizedBytes.size.toLong()
        val totalDataLen = pcmLength + 36
        val byteRate = (sampleRate * channels * bitRate) / 8

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen ushr 8) and 0xff).toByte()
        header[6] = ((totalDataLen ushr 16) and 0xff).toByte()
        header[7] = ((totalDataLen ushr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate ushr 8) and 0xff).toByte()
        header[26] = ((sampleRate ushr 16) and 0xff).toByte()
        header[27] = ((sampleRate ushr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate ushr 8) and 0xff).toByte()
        header[30] = ((byteRate ushr 16) and 0xff).toByte()
        header[31] = ((byteRate ushr 24) and 0xff).toByte()
        header[32] = ((channels * bitRate) / 8).toByte(); header[33] = 0
        header[34] = bitRate.toByte(); header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (pcmLength and 0xff).toByte()
        header[41] = ((pcmLength ushr 8) and 0xff).toByte()
        header[42] = ((pcmLength ushr 16) and 0xff).toByte()
        header[43] = ((pcmLength ushr 24) and 0xff).toByte()

        val output = FileOutputStream(targetWav)
        output.write(header)
        output.write(normalizedBytes)
        output.close()
    }

    private fun showRecordingsDialog() {
        val files = filesDir.listFiles { _, name -> name.endsWith(".wav") }?.sortedByDescending { it.lastModified() }
        if (files == null || files.isEmpty()) {
            Toast.makeText(this, "Belum ada dataset tersimpan", Toast.LENGTH_SHORT).show()
            return
        }

        val fileNames = files.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Daftar Dataset WAV (${files.size})")
            .setItems(fileNames) { _, index ->
                val selectedFile = files[index]
                playOrManageFile(selectedFile)
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun playOrManageFile(file: File) {
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setMessage("Pilih tindakan:")
            .setPositiveButton("Putar") { _, _ ->
                try {
                    MediaPlayer().apply {
                        setDataSource(file.absolutePath)
                        prepare()
                        start()
                    }
                    Toast.makeText(this, "Memutar...", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Gagal memutar audio", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Hapus") { _, _ ->
                if (file.delete()) {
                    Toast.makeText(this, "File dihapus", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }
}
