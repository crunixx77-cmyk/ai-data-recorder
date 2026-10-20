package com.example.datasetrecorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var btnRecord: Button
    private lateinit var btnList: Button
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private lateinit var outputFile: File

    private val sampleRate = 16000
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
                    startAudioRecording()
                    if (isRecording) {
                        btnRecord.text = "Merekam... Lepas untuk Selesai"
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isRecording) {
                        vibratePhone()
                        stopAudioRecording()
                        Toast.makeText(this, "Rekaman disimpan!", Toast.LENGTH_SHORT).show()
                    }
                    btnRecord.text = "Tekan & Tahan untuk Merekam"
                    true
                }
                else -> false
            }
        }

        btnList.setOnClickListener {
            Toast.makeText(this, "Daftar rekaman dibuka", Toast.LENGTH_SHORT).show()
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

    private fun startAudioRecording() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        
        outputFile = File(filesDir, "rec_${System.currentTimeMillis()}.pcm")

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
                val outputStream = FileOutputStream(outputFile)
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
    }
}
