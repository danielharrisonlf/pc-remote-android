package com.pcremote

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * One utterance at a time through the system speech recognizer, in pt-BR. The
 * microphone opens on [start] and closes when the speech ends, on [cancel] or on an
 * error; it is never left listening. Callbacks arrive on the main thread.
 */
internal class VoiceInput(private val context: Context) {
  interface Listener {
    fun ready()
    /** Input level from 0 to 1, for a simple meter. */
    fun level(value: Float)
    fun partial(text: String)
    fun result(text: String)
    fun failed(message: String)
  }

  private var recognizer: SpeechRecognizer? = null
  private val main = Handler(Looper.getMainLooper())
  /** Some recognizer services stop recording without ever calling back; this ends the wait. */
  private var deadline: Runnable? = null
  private var hardStop: Runnable? = null

  val listening: Boolean get() = recognizer != null

  fun available(): Boolean = try { SpeechRecognizer.isRecognitionAvailable(context) } catch (_: RuntimeException) { false }

  fun start(listener: Listener) {
    cancel()
    if (!available()) { listener.failed("Reconhecimento de voz indisponível neste celular."); return }
    val speech = try { SpeechRecognizer.createSpeechRecognizer(context) } catch (_: RuntimeException) { null }
    if (speech == null) { listener.failed("Reconhecimento de voz indisponível neste celular."); return }
    recognizer = speech
    val giveUp = { if (recognizer === speech) { cancel(); listener.failed(message(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) } }
    hardStop = Runnable { giveUp() }.also { main.postDelayed(it, MAX_LISTEN_MS) }
    fun quietFor(ms: Long) { deadline?.let(main::removeCallbacks); deadline = Runnable { giveUp() }.also { main.postDelayed(it, ms) } }
    quietFor(SILENCE_MS)
    speech.setRecognitionListener(object : RecognitionListener {
      override fun onReadyForSpeech(params: Bundle?) { if (recognizer === speech) listener.ready() }
      override fun onBeginningOfSpeech() { if (recognizer === speech) quietFor(SILENCE_MS) }
      // RMS arrives roughly from -2 to 10 dB.
      override fun onRmsChanged(rmsdB: Float) { if (recognizer === speech) listener.level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
      override fun onBufferReceived(buffer: ByteArray?) {}
      // The final result may take a moment over the network.
      override fun onEndOfSpeech() { if (recognizer === speech) quietFor(RESULT_MS) }
      override fun onError(error: Int) {
        if (recognizer !== speech) return
        release()
        listener.failed(message(error))
      }
      override fun onResults(results: Bundle?) {
        if (recognizer !== speech) return
        release()
        val text = best(results)
        if (text.isNullOrBlank()) listener.failed(message(SpeechRecognizer.ERROR_NO_MATCH)) else listener.result(text)
      }
      override fun onPartialResults(partialResults: Bundle?) {
        if (recognizer !== speech) return
        val text = best(partialResults)?.takeIf { it.isNotBlank() } ?: return
        quietFor(SILENCE_MS)
        listener.partial(text)
      }
      override fun onEvent(eventType: Int, params: Bundle?) {}
    })
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
      .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
      .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
      .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
      .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    try { speech.startListening(intent) } catch (_: RuntimeException) { release(); listener.failed("Não foi possível usar o microfone.") }
  }

  /** Closes the microphone without a result; nothing is reported. */
  fun cancel() {
    stopTimers()
    val speech = recognizer ?: return
    recognizer = null
    try { speech.cancel() } catch (_: RuntimeException) {}
    try { speech.destroy() } catch (_: RuntimeException) {}
  }

  private fun release() {
    stopTimers()
    val speech = recognizer ?: return
    recognizer = null
    try { speech.destroy() } catch (_: RuntimeException) {}
  }

  private fun stopTimers() {
    deadline?.let(main::removeCallbacks); deadline = null
    hardStop?.let(main::removeCallbacks); hardStop = null
  }

  private fun best(results: Bundle?): String? = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

  private fun message(error: Int): String = when (error) {
    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Não entendi. Toque no microfone e tente de novo."
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Sem internet para reconhecer a fala. Digite a pesquisa."
    SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "O reconhecimento de voz não respondeu. Tente de novo."
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "O reconhecimento de voz está ocupado. Tente em instantes."
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permita o microfone para usar a voz."
    SpeechRecognizer.ERROR_AUDIO -> "Não foi possível usar o microfone."
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "O reconhecimento de voz deste celular não tem português."
    else -> "Não foi possível ouvir. Tente de novo."
  }

  private companion object {
    /** Without speech (or new words) for this long, the attempt ends. */
    const val SILENCE_MS = 8_000L
    /** After the end of speech, the wait for the final result. */
    const val RESULT_MS = 6_000L
    /** Never listen longer than this in one attempt. */
    const val MAX_LISTEN_MS = 20_000L
  }
}
