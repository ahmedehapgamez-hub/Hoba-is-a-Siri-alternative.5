package com.hoba.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tvStatus: TextView
    private lateinit var tvHeard: TextView
    private lateinit var tvResponse: TextView
    private lateinit var pbAudioLevel: ProgressBar
    private lateinit var btnListen: MaterialButton

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private lateinit var processor: CommandProcessor
    private val handler = Handler(Looper.getMainLooper())
    private var isContinuousMode = true

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { startListeningSafe() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.rootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left + 40, bars.top + 32, bars.right + 40, bars.bottom + 32)
            insets
        }

        tvStatus = findViewById(R.id.tvStatus)
        tvHeard = findViewById(R.id.tvHeard)
        tvResponse = findViewById(R.id.tvResponse)
        pbAudioLevel = findViewById(R.id.pbAudioLevel)
        btnListen = findViewById(R.id.btnListen)

        processor = CommandProcessor(this)
        tts = TextToSpeech(this, this)

        btnListen.setOnClickListener {
            isContinuousMode = !isContinuousMode
            if (isContinuousMode) {
                startListeningSafe()
            } else {
                speechRecognizer?.cancel()
                tvStatus.text = "Paused (Tap MIC to resume)"
            }
        }

        requestNeededPermissions()
    }

    private fun requestNeededPermissions() {
        val needed = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            startListeningSafe()
        }
    }

    private fun setupRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    tvStatus.text = "Listening for 'Hoba'..."
                }

                override fun onBeginningOfSpeech() {
                    tvStatus.text = "Hearing voice..."
                }

                override fun onRmsChanged(rmsdB: Float) {
                    val level = ((rmsdB + 2f) * 8f).toInt().coerceIn(5, 100)
                    pbAudioLevel.progress = level
                }

                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    tvStatus.text = "Processing..."
                }

                override fun onError(error: Int) {
                    pbAudioLevel.progress = 5
                    if (isContinuousMode) {
                        handler.postDelayed({ startListeningSafe() }, 500)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    if (matches.isNotEmpty()) {
                        tvHeard.text = "Heard: ${matches[0]}"
                        val cmd = processor.extractCommand(matches)
                        if (cmd != null) {
                            val reply = processor.execute(cmd)
                            tvResponse.text = "Hoba: $reply"
                            speak(reply)
                        } else {
                            tvStatus.text = "Say 'Hoba' first before command"
                        }
                    }
                    if (isContinuousMode) {
                        handler.postDelayed({ startListeningSafe() }, 400)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!partial.isNullOrBlank()) {
                        tvHeard.text = "Hearing: $partial"
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startListeningSafe() {
        if (!isContinuousMode) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        setupRecognizer()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 10)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            handler.postDelayed({ startListeningSafe() }, 800)
        }
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hoba_reply")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
        }
    }

    override fun onDestroy() {
        isContinuousMode = false
        handler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}
