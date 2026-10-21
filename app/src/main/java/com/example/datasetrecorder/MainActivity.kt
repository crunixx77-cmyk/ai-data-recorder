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
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

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

        btnRecord.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(this, "Izin mikrofon belum diberikan!", Toast.LENGTH_SHORT).show()
                        checkPermissions()
                        return@setOnTouchListener true
                    }

                    vibratePhone()
                    lifecycleScope.launch {
                        delay(50)
                        startAudioRecording()
                    }
                    if (isRecording) {
                        btnRecord.text = "Merekam... Lepas untuk Selesai"
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isRecording) {
                        vibratePhone()
                        stopAudioRecording()
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
            vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(40)
        }
    }

    private fun startAudioRecording() {
        tempCacheFile = File(cacheDir, "temp_rec.pcm")

        try {
            val audioSource = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                MediaRecorder.AudioSource.UNPROCESSED
            } else {
                MediaRecorder.AudioSource.MIC
            }

            audioRecord = AudioRecord(
                audioSource,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
            }

            audioRecord?.startRecording()
            isRecording = true

            lifecycleScope.launch(Dispatchers.IO) {
                val data = ByteArray(bufferSize)
                FileOutputStream(tempCacheFile).use { outputStream ->
                    while (isRecording) {
                        val read = audioRecord?.read(data, 0, data.size) ?: 0
                        if (read > 0) {
                            outputStream.write(data, 0, read)
                        }
                    }
                    outputStream.flush()
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
            delay(300)
            showPreviewDialog()
        }
    }

    private fun getNextDefaultFileName(): String {
        val files = filesDir.listFiles { _, name -> name.startsWith("suara_") && name.endsWith(".wav") }
        var maxId = 0
        files?.forEach { file ->
            val numStr = file.name.removePrefix("suara_").removeSuffix(".wav")
            val num = numStr.toIntOrNull()
            if (num != null && num > maxId) {
                maxId = num
            }
        }
        return String.format("suara_%03d", maxId + 1)
    }

    private fun showPreviewDialog() {
        if (!tempCacheFile.exists()) return

        var mediaPlayer: MediaPlayer? = null
        val defaultName = getNextDefaultFileName()
        val inputEditText = EditText(this).apply {
            hint = "Nama file (misal: suara_001)"
            setText(defaultName)
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle("Pratinjau Rekaman AI")
            .setMessage("Masukkan nama file WAV:")
            .setView(inputEditText)
            .setPositiveButton("Simpan") { _, _ ->
                mediaPlayer?.release()
                val customName = inputEditText.text.toString().trim()
                val finalName = if (customName.isBlank()) defaultName else customName
                saveFinalWavFile(finalName)
            }
            .setNeutralButton("Putar Ulang") { _, _ ->
                try {
                    val previewWav = File(cacheDir, "preview.wav")
                    tempCacheFile.toRawWavFile(previewWav, sampleRate, 1, 16)
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(previewWav.absolutePath)
                        prepare()
                        start()
                    }
                    Toast.makeText(this, "Memutar pratinjau...", Toast.LENGTH_SHORT).show()
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

    private fun saveFinalWavFile(fileName: String) {
        val safeName = if (fileName.isBlank()) getNextDefaultFileName() else fileName
        val finalFile = File(filesDir, "$safeName.wav")
        
        tempCacheFile.toRawWavFile(finalFile, sampleRate, 1, 16)
        tempCacheFile.delete()

        Toast.makeText(this, "Disimpan: $safeName.wav", Toast.LENGTH_LONG).show()
    }

    private fun File.toRawWavFile(targetWav: File, sampleRate: Int, channels: Int, bitRate: Int) {
        val pcmBytes = this.readBytes()
        if (pcmBytes.isEmpty()) return

        val pcmLength = pcmBytes.size.toLong()
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

        FileOutputStream(targetWav).use { output ->
            output.write(header)
            output.write(pcmBytes)
        }
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
            .setNeutralButton("Bagikan") { _, _ ->
                shareAudioFile(file)
            }
            .setNegativeButton("Hapus") { _, _ ->
                if (file.delete()) {
                    Toast.makeText(this, "File dihapus", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun shareAudioFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Bagikan Dataset Audio"))
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal membagikan file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
