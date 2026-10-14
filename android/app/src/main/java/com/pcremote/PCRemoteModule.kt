package com.pcremote

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableNativeMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.facebook.react.bridge.LifecycleEventListener
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import okhttp3.OkHttpClient
import okhttp3.Dns
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.security.MessageDigest
import java.net.InetAddress
import java.net.Inet4Address
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.min

class PCRemoteModule(private val context: ReactApplicationContext) : NativePCRemoteSpec(context), LifecycleEventListener {
  companion object {
    @Volatile private var current: PCRemoteModule? = null
    @Volatile private var pendingShare: String? = null
    @Volatile private var pendingFeedback: String? = null
    fun captureShare(intent: Intent) {
      if (intent.action != Intent.ACTION_SEND || intent.type != "text/plain") return
      val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: return
      if (text.toByteArray(Charsets.UTF_8).size > 65_536 || text.contains('\u0000')) {
        val message = "Texto compartilhado inválido ou maior que 64 KiB."
        pendingFeedback = message
        current?.emit("remote.feedback", message)
        return
      }
      val module = current
      if (module != null && module.shareListenerReady) module.emit("remote.share", text) else pendingShare = text
    }
  }

  private val main = Handler(Looper.getMainLooper())
  private val prefs = context.getSharedPreferences("pc_remote_identity", Context.MODE_PRIVATE)
  private val credentials = CredentialStore(prefs)
  private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
  private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
  private val networkCallback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) { main.post { if (foreground && !requestedDisconnect) { stopDiscovery(); startDiscovery(); reconnect() } } }
    override fun onLost(network: Network) { main.post { if (!requestedDisconnect) { socket?.cancel(); lost(connectionGeneration) } } }
  }
  private var discovery: NsdManager.DiscoveryListener? = null
  @Volatile private var socket: WebSocket? = null
  @Volatile private var client: OkHttpClient? = null
  @Volatile private var endpoint: PairedComputer? = null
  private var pairingCode: String? = null
  private var pairRequestId: String? = null
  private var helloRequestId: String? = null
  private var listenerCount = 0
  @Volatile private var shareListenerReady = false
  @Volatile private var messageListenerReady = false
  private var reconnectAttempt = 0
  private var reconnectRunnable: Runnable? = null
  @Volatile private var connectionGeneration = 0
  @Volatile private var requestedDisconnect = false
  @Volatile private var foreground = false
  @Volatile private var foregroundSync = false
  @Volatile private var supportsInput = false
  @Volatile private var inputResumeBlocked = false
  private var suppressClipText: String? = null
  @Volatile private var pendingRemoteMessage: String? = null
  private var lastClipText: String? = null
  private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener { onClipboardChanged() }


  init {
    current = this
    context.addLifecycleEventListener(this)
    clipboard.addPrimaryClipChangedListener(clipboardListener)
    createChannel()
    connectivity.registerDefaultNetworkCallback(networkCallback)
  }

  override fun getName() = "PCRemoteNative"
  private fun emit(name: String, value: Any?) {
    if (context.hasActiveReactInstance()) {
      main.post { context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit(name, value) }
    }
  }
  private fun status(value: String) = emit("remote.status", value)
  override fun createId(): String = UUID.randomUUID().toString()
  @Synchronized override fun addListener(eventName: String) {
    listenerCount++
    if (eventName == "remote.share") shareListenerReady = true
    if (eventName == "remote.message") messageListenerReady = true
  }
  @Synchronized override fun removeListeners(count: Double) {
    listenerCount = (listenerCount - count.toInt()).coerceAtLeast(0)
    if (listenerCount == 0) { shareListenerReady = false; messageListenerReady = false }
  }

  private fun persist(e: PairedComputer) = credentials.persist(e)
  private fun restore(): PairedComputer? = credentials.restore()
  override fun getSavedComputer(promise: Promise) {
    val e = restore()
    promise.resolve(if (e == null) "" else JSONObject().put("host", e.host).put("port", e.port).put("fingerprint", e.fingerprint).put("pcId", e.pcId).put("pcName", e.pcName).toString())
  }
  override fun getPreferences(promise: Promise) {
    promise.resolve(JSONObject().put("upload", prefs.getBoolean("upload", false))
      .put("download", prefs.getBoolean("download", false)).put("paused", prefs.getBoolean("paused", false))
      .put("autoApply", prefs.getBoolean("autoApply", false)).put("haptics", prefs.getBoolean("haptics", true)).toString())
  }
  override fun savePreferences(json: String) {
    try {
      val value = JSONObject(json)
      prefs.edit().putBoolean("upload", value.getBoolean("upload")).putBoolean("download", value.getBoolean("download"))
        .putBoolean("paused", value.getBoolean("paused")).putBoolean("autoApply", value.getBoolean("autoApply"))
        .putBoolean("haptics", value.getBoolean("haptics")).apply()
    } catch (_: Exception) {}
  }
  override fun forgetComputer() { disconnect(); prefs.edit().remove("credential").apply(); endpoint = null }
  override fun updateAddress(host: String, port: Double, promise: Promise) {
    val previous = endpoint ?: restore()
    if (previous == null || previous.token.isBlank()) { promise.reject("not_paired", "Pareie um PC primeiro."); return }
    if (!host.matches(Regex("[a-zA-Z0-9.-]{1,253}")) || port !in 1.0..65535.0 || port != port.toInt().toDouble()) {
      promise.reject("invalid_address", "Informe um endereço IPv4 ou nome de computador e porta válida."); return
    }
    disconnect()
    endpoint = previous.copy(host = host.lowercase(), port = port.toInt())
    requestedDisconnect = false
    pairingCode = null
    open(true)
    promise.resolve(null)
  }

  @Synchronized override fun connect(host: String, port: Double, fingerprint: String, code: String, deviceName: String) {
    val portInt = port.toInt()
    if (host.isBlank() || host.length > 255 || portInt !in 1..65535 || port != portInt.toDouble() || !fingerprint.matches(Regex("[a-fA-F0-9]{64}")) || !code.matches(Regex("[0-9]{8}"))) {
      status("error:Confira endereço, porta, código de 8 dígitos e fingerprint de 64 caracteres.")
      return
    }
    disconnect()
    requestedDisconnect = false
    endpoint = PairedComputer(host, portInt, fingerprint.lowercase(), UUID.randomUUID().toString(), deviceName.take(80))
    pairingCode = code
    pairRequestId = null
    open(false)
  }
  @Synchronized override fun reconnect() {
    // A cold native lifecycle resume can precede JS subscriptions. Do not lose
    // welcome/state by authenticating before the shared store starts listening.
    if (!messageListenerReady || socket != null || requestedDisconnect) return
    endpoint = endpoint ?: restore()
    val e = endpoint ?: return
    if (e.token.isBlank()) return
    pairingCode = null
    open(true)
  }
  @Synchronized override fun disconnect() {
    requestedDisconnect = true
    supportsInput = false
    reconnectRunnable?.let { main.removeCallbacks(it) }
    reconnectRunnable = null
    connectionGeneration++
    pendingRemoteMessage = null
    socket?.close(1000, "Desconectado")
    socket = null
    client?.dispatcher?.executorService?.shutdown()
    client = null
    status("disconnected")
  }

  @Synchronized private fun open(isReconnect: Boolean) {
    if (!messageListenerReady) return
    val e = endpoint ?: return
    supportsInput = false
    reconnectRunnable?.let { main.removeCallbacks(it) }
    connectionGeneration++
    val gen = connectionGeneration
    status(if (isReconnect) "reconnecting" else "connecting")
    try {
      val trust = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { throw CertificateException("Client trust unsupported") }
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
          if (chain.size != 1) throw CertificateException("Unexpected certificate chain")
          chain[0].checkValidity()
          val actual = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded).joinToString("") { "%02x".format(it) }
          if (actual != e.fingerprint) throw CertificateException("PC identity mismatch")
        }
      }
      val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), SecureRandom()) }
      client = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trust)
        .dns(object : Dns {
          override fun lookup(hostname: String): List<InetAddress> {
            if (hostname != "pc-remote.local") throw UnknownHostException("Unexpected hostname")
            return InetAddress.getAllByName(e.host).filterIsInstance<Inet4Address>()
          }
        })
        .connectTimeout(8, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()
      socket = client!!.newWebSocket(Request.Builder().url("wss://pc-remote.local:${e.port}/v1/ws").header("X-PC-Remote-Features", "input.v1").build(), object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
          if (gen != connectionGeneration) { webSocket.close(1000, null); return }
          val active = endpoint ?: return
          val id = UUID.randomUUID().toString()
          val pair = pairingCode
          if (pair != null) {
            pairRequestId = id
            status("pairing")
            webSocket.send(JSONObject().put("v", 1).put("type", "pair.request").put("id", id).put("deviceId", active.deviceId).put("deviceName", active.deviceName).put("code", pair).toString())
          } else {
            helloRequestId = id
            webSocket.send(JSONObject().put("v", 1).put("type", "hello").put("id", id).put("deviceId", active.deviceId).put("token", active.token).toString())
          }
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
          if (gen != connectionGeneration || text.toByteArray(Charsets.UTF_8).size > 524_288) { webSocket.close(1009, null); return }
          try {
            val msg = JSONObject(text)
            if (msg.optInt("v") != 1) { webSocket.close(1002, null); return }
            when (msg.optString("type")) {
              "pair.pending" -> if (msg.optString("id") == pairRequestId) status("pending") else webSocket.close(1002, null)
              "pair.result" -> {
                if (pairingCode == null || msg.optString("id") != pairRequestId || !msg.getString("pcId").matches(Regex("[a-fA-F0-9-]{36}"))) { webSocket.close(1002, null); return }
                val token = msg.getString("token")
                if (!token.matches(Regex("[a-fA-F0-9]{64}"))) { webSocket.close(1002, null); return }
                val old = endpoint ?: return
                val saved = old.copy(pcId = msg.getString("pcId"), pcName = msg.getString("pcName"), token = token)
                endpoint = saved
                persist(saved)
                pairingCode = null
                pairRequestId = null
                val helloId = UUID.randomUUID().toString()
                helloRequestId = helloId
                webSocket.send(JSONObject().put("v", 1).put("type", "hello").put("id", helloId).put("deviceId", saved.deviceId).put("token", saved.token).toString())
              }
              "welcome" -> {
                if (endpoint?.token.isNullOrBlank() || msg.optString("pcId") != endpoint?.pcId || msg.optString("id") != helloRequestId) { webSocket.close(1002, null); return }
                helloRequestId = null
                val capabilities = msg.optJSONArray("capabilities")
                supportsInput = capabilities != null && (0 until capabilities.length()).any { index -> capabilities.optString(index) == "input.control" }
                endpoint?.let { persist(it) }
                reconnectAttempt = 0; status("connected"); emit("remote.message", text)
              }
              "error" -> {
                val id = msg.optString("id")
                if (id == pairRequestId || id == helloRequestId) {
                  val code = msg.optString("code")
                  requestedDisconnect = true
                  pairingCode = null
                  pairRequestId = null
                  helloRequestId = null
                  if (code == "unauthorized") { prefs.edit().remove("credential").apply(); endpoint = null }
                  val message = when (code) {
                    "pairing_expired" -> "Código expirou. Gere outro no PC."
                    "pairing_denied" -> "Pareamento recusado no PC."
                    "rate_limited" -> "Muitas tentativas. Aguarde e tente novamente."
                    "unauthorized" -> "Acesso revogado ou credencial inválida. Pareie novamente."
                    "unavailable" -> "Outro celular já controla este PC. Desconecte-o antes de tentar novamente."
                    else -> "Pareamento ou autenticação falhou. Confira os dados."
                  }
                  status("error:$message")
                  webSocket.close(1008, null)
                } else emit("remote.message", text)
              }
              "clipboard.event" -> {
                if (!foreground) { pendingRemoteMessage = text; notifyClipboard() } else emit("remote.message", text)
              }
              else -> emit("remote.message", text)
            }
          } catch (_: Exception) { webSocket.close(1002, null) }
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { webSocket.close(1003, null) }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { lost(gen) }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
          if (gen == connectionGeneration) status("error:" + if (t is javax.net.ssl.SSLException) "Identidade TLS do PC não confere ou certificado expirou." else "Não foi possível conectar ao PC na rede local.")
          lost(gen)
        }
      })
    } catch (_: Exception) { status("error:Endereço ou certificado inválido."); lost(gen) }
  }
  @Synchronized private fun lost(gen: Int) {
    if (gen != connectionGeneration) return
    supportsInput = false
    pendingRemoteMessage = null
    socket = null
    if (requestedDisconnect) return
    status("disconnected")
    if (requestedDisconnect || endpoint?.token.isNullOrBlank() || !foreground) return
    val delays = intArrayOf(1, 2, 4, 8, 16, 30)
    val delay = delays[min(reconnectAttempt++, delays.lastIndex)] * 1000L + SecureRandom().nextInt(500)
    reconnectRunnable = Runnable { reconnectRunnable = null; if (foreground && !requestedDisconnect) open(true) }
    main.postDelayed(reconnectRunnable!!, delay)
  }
  @Synchronized override fun sendRequest(id: String, actionJson: String) {
    if (actionJson.toByteArray(Charsets.UTF_8).size > 524_000 || !id.matches(Regex("[a-fA-F0-9-]{36}"))) return
    try {
      val action = JSONObject(actionJson)
      val kind = action.optString("kind")
      val isInput = kind == "input.enable" || kind.startsWith("pointer.") || kind.startsWith("keyboard.")
      val disabling = kind == "input.enable" && action.optBoolean("enabled", true) == false
      if (isInput && (!supportsInput || ((!foreground || inputResumeBlocked) && !disabling))) {
        emit("remote.message", JSONObject().put("v", 1).put("type", "error").put("id", id)
          .put("code", "unavailable").put("message", "Abra o aplicativo e ative novamente o controle de entrada.")
          .put("retryable", false).toString())
        return
      }
      socket?.send(JSONObject().put("v", 1).put("type", "request").put("id", id).put("action", action).toString())
    }
    catch (_: Exception) { status("error:Comando inválido.") }
  }

  override fun readClipboard(promise: Promise) {
    main.post {
      try {
        val description = clipboard.primaryClipDescription
        if (isSensitive(description)) { promise.reject("sensitive", "Texto marcado como sensível pelo Android."); return@post }
        if (description?.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_PLAIN) != true) { promise.reject("unsupported", "A área de transferência não contém texto simples."); return@post }
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        if (text.contains('\u0000') || text.toByteArray(Charsets.UTF_8).size > 65_536) promise.reject("too_large", "Texto inválido ou maior que 64 KiB.") else promise.resolve(text)
      } catch (_: Exception) { promise.reject("clipboard_busy", "Não foi possível ler a área de transferência.") }
    }
  }
  private fun isSensitive(description: android.content.ClipDescription?): Boolean =
    description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true
  override fun writeClipboard(text: String, promise: Promise) {
    if (text.contains('\u0000') || text.toByteArray(Charsets.UTF_8).size > 65_536) { promise.reject("too_large", "Texto inválido ou maior que 64 KiB."); return }
    main.post {
      if (!foreground || getCurrentActivity()?.hasWindowFocus() != true) { promise.reject("unavailable", "Abra o aplicativo para copiar o texto."); return@post }
      try {
        suppressClipText = text
        clipboard.setPrimaryClip(ClipData.newPlainText("PC Remote", text))
        promise.resolve(null)
      } catch (_: Exception) { suppressClipText = null; promise.reject("unavailable", "O Android não permitiu atualizar a área de transferência.") }
    }
  }
  override fun setForegroundSync(enabled: Boolean) {
    main.post {
      val wasEnabled = foregroundSync
      foregroundSync = enabled && foreground
      if (foregroundSync && !wasEnabled) {
        lastClipText = try { clipboard.primaryClip?.getItemAt(0)?.text?.toString() } catch (_: Exception) { null }
      }
    }
  }
  @Synchronized override fun acknowledgeInputResume() {
    // JS invokes this only after discarding input from the previous lifecycle.
    // Native resume can precede window focus; input has its own lifecycle gate.
    if (foreground) inputResumeBlocked = false
  }
  private fun onClipboardChanged() {
    if (!foreground || !foregroundSync || getCurrentActivity()?.hasWindowFocus() != true) return
    main.post {
      if (!foreground || !foregroundSync || getCurrentActivity()?.hasWindowFocus() != true) return@post
      try {
        val description = clipboard.primaryClipDescription
        val sensitive = isSensitive(description)
        if (description?.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_PLAIN) != true) return@post
        val text = if (sensitive) "" else clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        if (text == suppressClipText) { suppressClipText = null; lastClipText = text; return@post }
        if (text == lastClipText) return@post
        lastClipText = text
        if (text.isEmpty()) return@post
        if (text.toByteArray(Charsets.UTF_8).size > 65_536 || text.contains('\u0000')) { emit("remote.feedback", "Texto copiado inválido ou maior que 64 KiB."); return@post }
        val payload = WritableNativeMap().apply { putString("text", text); putBoolean("sensitive", sensitive) }
        emit("remote.clipboard", payload)
      } catch (_: Exception) {}
    }
  }
  override fun getSharedText(promise: Promise) { val text = pendingShare ?: ""; pendingShare = null; promise.resolve(text) }
  override fun getPendingRemoteMessage(promise: Promise) { val text = pendingRemoteMessage ?: ""; pendingRemoteMessage = null; promise.resolve(text) }
  override fun getPendingFeedback(promise: Promise) { val text = pendingFeedback ?: ""; pendingFeedback = null; promise.resolve(text) }
  override fun scanQr(promise: Promise) {
    val activity = getCurrentActivity() ?: run { promise.reject("no_activity", "Abra o aplicativo para ler o QR."); return }
    GmsBarcodeScanning.getClient(activity).startScan()
      .addOnSuccessListener { barcode -> val raw = barcode.rawValue ?: ""; if (raw.length <= 2048) promise.resolve(raw) else promise.reject("qr_too_large", "QR inválido.") }
      .addOnFailureListener { promise.reject("scan_failed", "Não foi possível ler o QR.") }
      .addOnCanceledListener { promise.reject("scan_canceled", "Leitura cancelada.") }
  }
  override fun startDiscovery() {
    if (discovery != null) return
    val listener = object : NsdManager.DiscoveryListener {
      override fun onDiscoveryStarted(serviceType: String) {}
      override fun onDiscoveryStopped(serviceType: String) {}
      override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { discovery = null; emit("remote.feedback", "Descoberta indisponível. Use o endereço manual.") }
      override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
      override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
      override fun onServiceFound(serviceInfo: NsdServiceInfo) {
        nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
          override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
          override fun onServiceResolved(info: NsdServiceInfo) {
            val address = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses.firstOrNull { it is Inet4Address } else info.host
            val host = (address as? Inet4Address)?.hostAddress ?: return
            emit("remote.discovery", JSONObject().put("name", info.serviceName).put("host", host).put("port", info.port).toString())
            val selected = endpoint ?: restore()
            val advertisedId = info.attributes["id"]?.toString(Charsets.UTF_8)
            if (foreground && !requestedDisconnect && socket == null && selected != null &&
                selected.token.isNotBlank() && selected.pcId == advertisedId && !host.contains(':')) {
              main.post {
                if (socket == null && foreground && !requestedDisconnect) {
                  endpoint = selected.copy(host = host, port = info.port)
                  pairingCode = null
                  open(true)
                }
              }
            }
          }
        })
      }
    }
    discovery = listener
    try { nsd.discoverServices("_pcremote._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) } catch (_: Exception) { discovery = null }
  }
  override fun stopDiscovery() { discovery?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }; discovery = null }
  private fun createChannel() {
    if (Build.VERSION.SDK_INT >= 26) {
      (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        .createNotificationChannel(NotificationChannel("clipboard", "Texto recebido", NotificationManager.IMPORTANCE_DEFAULT))
    }
  }
  private fun notifyClipboard() {
    if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val pending = PendingIntent.getActivity(context, 7, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val notification = NotificationCompat.Builder(context, "clipboard")
      .setSmallIcon(com.pcremote.R.mipmap.ic_launcher).setContentTitle("Texto recebido do PC")
      .setContentText("Abra o PC Remote para copiar.").setContentIntent(pending).setAutoCancel(true).build()
    (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(7, notification)
  }
  @Synchronized override fun onHostResume() {
    foreground = true
    lastClipText = try { if (clipboard.primaryClipDescription?.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_PLAIN) == true) clipboard.primaryClip?.getItemAt(0)?.text?.toString() else null } catch (_: Exception) { null }
    pendingRemoteMessage?.let { emit("remote.backgroundClipboard", it); pendingRemoteMessage = null }
    reconnect()
    if (!requestedDisconnect && (endpoint ?: restore())?.token?.isNotBlank() == true) startDiscovery()
  }
  private fun disableNativeInput() {
    if (supportsInput) sendRequest(UUID.randomUUID().toString(), JSONObject().put("kind", "input.enable").put("enabled", false).toString())
  }
  @Synchronized override fun onHostPause() { foreground = false; foregroundSync = false; inputResumeBlocked = true; disableNativeInput() }
  @Synchronized override fun onHostDestroy() { foreground = false; foregroundSync = false; inputResumeBlocked = true; disableNativeInput(); stopDiscovery() }
  override fun invalidate() { disconnect(); stopDiscovery(); connectivity.unregisterNetworkCallback(networkCallback); clipboard.removePrimaryClipChangedListener(clipboardListener); current = null; super.invalidate() }
}
