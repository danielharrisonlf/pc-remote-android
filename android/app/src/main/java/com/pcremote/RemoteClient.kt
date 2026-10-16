package com.pcremote

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipDescription
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
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class RemoteClient(context: Context) : RemoteController {
  private val app = context.applicationContext
  private val main = Handler(Looper.getMainLooper())
  private val prefs = app.getSharedPreferences("pc_remote_identity",Context.MODE_PRIVATE)
  private val store = CredentialStore(prefs)
  private val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  private val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
  private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
  private var activity = WeakReference<Activity>(null)
  private val listeners = LinkedHashSet<(UiState)->Unit>()
  // Preferences before 0.6 (upload, download, paused, autoApply, haptics) are ignored: sync starts on.
  override var view = UiState(preferences = Preferences(prefs.getBoolean("realtime",true)),pcName=store.restore()?.pcName ?: "",pcOs=prefs.getString("pcOs","").orEmpty())
    private set
  private var endpoint: PairedComputer? = store.restore()
  private var socket: WebSocket? = null
  private var client: OkHttpClient? = null
  private var generation = 0
  private var inputGeneration = 0
  private var foreground = false
  private var requestedDisconnect = false
  private var authenticated = false
  private var pairCode: String? = null
  private var previousPairEndpoint: PairedComputer? = null
  private var pairingHandshake = false
  private var pairId: String? = null
  private var helloId: String? = null
  private var attempt = 0
  private var reconnectTask: Runnable? = null
  private val pending = LinkedHashMap<String, Pending>()
  private data class Pending(val completion:(String?,String?)->Unit,val timeout:Runnable,val generation:Int)
  private val clipboardSession = ClipboardSession()
  private val clipboardTracker = ClipboardTracker()
  /** A copy made in another app while disconnected; it goes after the next welcome if the clipboard still holds it. */
  private var pendingUpload: String? = null
  private var uploadAfterState: String? = null
  /** The user tapped a session chip; otherwise the selection follows the PC's current session. */
  private var userPickedSession = false
  private var volumeAt = 0L
  private var volumeTask: Runnable? = null
  private var dragGeneration = 0
  private var finalVolume: FinalVolume? = null
  private var finalVolumeTimeout:Runnable?=null
  private data class FinalVolume(val value:Int,val connection:Int,val drag:Int,val revision:Long,var acked:Boolean=false)
  private var discretePending = false
  private var inputDisarming=false
  private var inputNeedsDisarm=false
  private var disarmAck=false
  private var disarmStateSeen=false
  private var disarmTimer:Runnable?=null
  private data class Activation(val generation:Int,val revision:Long,val automatic:Boolean,val completion:(String?)->Unit,var acked:Boolean=false,var fresh:Boolean=false,var timeout:Runnable?=null)
  private var activation:Activation?=null
  private val inputIntent = InputIntent()
  private var sendView = FilesView()
  private var incomingFile: FileProgress? = null
  private var receiverLast: FileResult? = null
  private var fileResult: FileResult? = null
  private val fileHistory = ArrayDeque<FileRecord>()
  private var serviceRunning = false
  private var serviceStop: Runnable? = null
  private var serviceShown = -2
  private val files = FileTransfer(app.contentResolver,main,{ fileLink() },{ action,done -> send(action,done) },{ v -> if(v.last!=null && v.last!==sendView.last) fileResult=v.last; sendView=v; publishFiles() },{ fail(it) },{ record(it) })
  private val receiver = FileReceiver(app,main,{ fileLink() },{ action,done -> send(action,done) },{ progress,last -> incomingFile=progress; if(last!=null && last!==receiverLast) fileResult=last; receiverLast=last; publishFiles() },{ fail(it) },{ record(it) },{ foreground })
  private val motion = InputMotion(main,{ canInput() },{ dx,dy,done -> request(RemoteAction.PointerMove(dx,dy),done) },{ fail(it) })
  private val scrollMotion = ScrollMotion({ canInput() },{ steps,done -> request(RemoteAction.PointerScroll(steps),done) },{ fail(it) })
  private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener { main.post { localClipboardChanged() } }
  private var discovery: NsdManager.DiscoveryListener? = null
  private var currentNetwork: Network? = null
  private val networkCallback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) { main.post {
      val previous=currentNetwork
      currentNetwork=network
      if(foreground && !requestedDisconnect) {
        if(previous!=null && previous!=network && socket!=null) lost(generation)
        startDiscovery(); autoReconnect()
      }
    } }
    override fun onLost(network: Network) { main.post {
      if(currentNetwork!=network) return@post
      currentNetwork=null
      if(!requestedDisconnect) lost(generation)
    } }
  }
  init {
    clipboard.addPrimaryClipChangedListener(clipboardListener)
    try { connectivity.registerDefaultNetworkCallback(networkCallback) } catch (_: Exception) {}
    if(Build.VERSION.SDK_INT>=26) try {
      (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel("clipboard","Texto recebido",NotificationManager.IMPORTANCE_DEFAULT))
    } catch (_: RuntimeException) { /* Clipboard preview remains available in the app. */ }
  }
  private fun run(block:()->Unit) { if(Looper.myLooper()==Looper.getMainLooper()) block() else main.post(block) }
  private fun patch(f:(UiState)->UiState) { view=f(view).let { it.copy(inputResuming=inputIntent.wanted && !it.inputArmed) }; listeners.toList().forEach { it(view) } }
  private fun fail(text:String) = patch { it.copy(error=text) }
  override fun observe(listener:(UiState)->Unit):()->Unit { run { listeners.add(listener); listener(view) }; return { run { listeners.remove(listener) } } }
  override fun foreground(activity:Activity) = run {
    this.activity=WeakReference(activity); foreground=true
    if(hasFocus()) clipboardResumed() else clipboardTracker.suspend()
    clipboardSession.takePending()?.let { pending -> patch { it.copy(receivedText=pending.text) } }
    autoReconnect(); if(!requestedDisconnect) startDiscovery()
    resumeInput()
    transferNotice(view.files)
  }
  override fun background() = run {
    foreground=false; activity.clear(); clipboardTracker.suspend()
    pauseInput(); stopDiscovery()
  }
  private fun hasFocus() = foreground && activity.get()?.hasWindowFocus()==true
  override fun focusChanged(hasFocus:Boolean) = run {
    if(!hasFocus) {
      clipboardTracker.suspend()
      pauseInput()
    } else if(foreground && hasFocus()) { clipboardResumed(); resumeInput() }
  }
  /** Focus came back: a copy made in another app goes to the PC once (see ClipboardTracker). */
  private fun clipboardResumed() {
    val text=clipboardTracker.resume(readLocalClipboardOrNull()) ?: return
    if(!view.preferences.realtime) return
    if(authenticated) sendText(text,true) {} else pendingUpload=text
  }
  override fun pair(host:String,port:Int,fingerprint:String,code:String) = run {
    val h=host.trim(); val fp=fingerprint.trim().replace(":","").lowercase()
    if(!addressValid(h,port) || !fp.matches(Regex("[a-f0-9]{64}")) || !code.trim().matches(Regex("[0-9]{8}"))) { fail("QR Code inválido."); return@run }
    startPairing(h,port,fp,code.trim())
  }
  override fun pairWithCode(pc:DiscoveredComputer,code:String) = run {
    val digits=code.filter(Char::isDigit)
    if(!addressValid(pc.host,pc.port) || !digits.matches(Regex("[0-9]{8}"))) { fail("Digite os 8 dígitos do código."); return@run }
    // No QR means no known identity yet: trust the certificate of this first
    // connection, pin it, and show its colors so the user can compare with the PC.
    startPairing(pc.host,pc.port,"",digits)
  }
  private fun startPairing(host:String,port:Int,fingerprint:String,code:String) {
    if(pairingHandshake) {
      close(false)
      restoreFailedPair()
    }
    previousPairEndpoint=endpoint?.takeIf { it.token.isNotBlank() } ?: store.restore()
    close(false); forgetFiles()
    requestedDisconnect=false; endpoint=PairedComputer(host,port,fingerprint,UUID.randomUUID().toString(),deviceName())
    pairingHandshake=true
    pairCode=code; patch { it.copy(status="Pareando",error="",snapshot=null,receivedText="",pairingFingerprint=fingerprint) }; open(false)
  }
  /** Shown in the PC approval prompt, e.g. "Samsung SM-S918B"; the PC accepts up to 80 bytes. */
  private fun deviceName():String {
    val brand=Build.BRAND.orEmpty().trim().replaceFirstChar { it.uppercase() }
    val model=Build.MODEL.orEmpty().trim()
    val name=(if(brand.isNotEmpty() && model.lowercase().startsWith(brand.lowercase())) model else "$brand $model").filter { it.code>=0x20 && it.code!=0x7f }.trim()
    var result=name.ifEmpty { "Celular Android" }
    while(result.toByteArray(Charsets.UTF_8).size>80) result=result.dropLast(1).trim()
    return result
  }
  private fun addressValid(host:String,port:Int)=port in 1..65535 && host.matches(Regex("[a-zA-Z0-9.-]{1,253}")) && !host.contains("..")
  override fun reconnect() = run { requestedDisconnect=false; autoReconnect() }
  private fun autoReconnect() {
    if(socket != null || requestedDisconnect || pairingHandshake) return
    endpoint=endpoint ?: store.restore()
    if(endpoint?.token.isNullOrBlank()) return
    pairCode=null; open(true)
  }
  override fun disconnect() = run {
    close(true)
    if(pairingHandshake) restoreFailedPair()
    patch { it.copy(status="Desconectado",snapshot=null,receivedText="",draggingVolume=null) }
  }
  override fun forget() = run {
    try { store.clear() } catch (_:Exception) { fail("Não foi possível remover a credencial protegida. Tente novamente."); return@run }
    close(true); forgetFiles(); previousPairEndpoint=null; pairingHandshake=false; endpoint=null
    prefs.edit().remove("pcOs").apply()
    patch { it.copy(status="Desconectado",pcName="",pcOs="",snapshot=null,receivedText="") }
  }
  /** Nothing queued, in flight or listed for one PC may reach another. */
  private fun forgetFiles() {
    files.forget(); receiver.forget()
    fileResult=null; receiverLast=null; fileHistory.clear(); publishFiles()
  }
  override fun savedAddress():Pair<String,Int>? = (endpoint ?: store.restore())?.let { it.host to it.port }
  private fun close(user:Boolean) {
    requestedDisconnect=user; reconnectTask?.let(main::removeCallbacks); reconnectTask=null
    generation++; retireClipboard(); authenticated=false; clearDisarm(); pairId=null; helloId=null; pairCode=null
    inputIntent.clear(); dropInput(); rejectPending(); files.connectionEnded(); receiver.connectionEnded(); volumeTask?.let(main::removeCallbacks); volumeTask=null; finalVolumeTimeout?.let(main::removeCallbacks); finalVolumeTimeout=null; finalVolume=null
    socket?.close(1000,"Desconectado"); socket=null; client?.dispatcher?.executorService?.shutdown(); client=null
  }
  private fun restoreFailedPair() {
    endpoint=previousPairEndpoint
    pairingHandshake=false; previousPairEndpoint=null
  }
  private fun open(reconnecting:Boolean) {
    val e=endpoint ?: return
    reconnectTask?.let(main::removeCallbacks); reconnectTask=null
    generation++; val own=generation; retireClipboard(); authenticated=false; clearDisarm(); inputIntent.clear(); dropInput(); rejectPending(); files.connectionEnded(); receiver.connectionEnded()
    patch { it.copy(status=if(reconnecting) "Reconectando" else "Conectando",snapshot=null,inputSupported=false,filesSupported=false,filesTwoWay=false,searchSupported=false) }
    // Only a code pairing starts without a pinned identity; every other connection pins.
    val firstUse=e.fingerprint.isEmpty() && pairCode!=null
    if(e.fingerprint.isEmpty() && !firstUse) { fail("Pareie este PC de novo."); lost(own); return }
    val observed=java.util.concurrent.atomic.AtomicReference<String?>(null)
    try {
      val trust=object:X509TrustManager {
        override fun getAcceptedIssuers():Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain:Array<X509Certificate>,authType:String) { throw CertificateException("client") }
        override fun checkServerTrusted(chain:Array<X509Certificate>,authType:String) {
          if(chain.size!=1) throw CertificateException("chain")
          chain[0].checkValidity()
          val digest=MessageDigest.getInstance("SHA-256").digest(chain[0].encoded).joinToString("") { "%02x".format(it) }
          // First use pins whatever the first handshake saw; later sockets of this client, such as /v1/files, must match it.
          if(firstUse) { if(!observed.compareAndSet(null,digest) && observed.get()!=digest) throw CertificateException("fingerprint") }
          else if(digest!=e.fingerprint) throw CertificateException("fingerprint")
        }
      }
      val ssl=SSLContext.getInstance("TLS").apply { init(null,arrayOf<TrustManager>(trust),SecureRandom()) }
      client=OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory,trust).dns(object:Dns {
        override fun lookup(hostname:String):List<InetAddress> {
          if(hostname!="pc-remote.local") throw UnknownHostException(hostname)
          return InetAddress.getAllByName(e.host).filterIsInstance<Inet4Address>().ifEmpty { throw UnknownHostException(e.host) }
        }
      }).connectTimeout(8,TimeUnit.SECONDS).pingInterval(20,TimeUnit.SECONDS).build()
      // Separate header lines: PCs up to 0.3 read only the first value and keep input.v1 working; older PCs ignore the tokens they do not know.
      val request=Request.Builder().url("wss://pc-remote.local:${e.port}/v1/ws")
      for(feature in FEATURES) request.addHeader("X-PC-Remote-Features",feature)
      socket=client!!.newWebSocket(request.build(),object:WebSocketListener() {
        override fun onOpen(ws:WebSocket,response:Response) { main.post {
          if(own!=generation) { ws.close(1000,null); return@post }
          if(firstUse) {
            val fingerprint=observed.get()
            if(fingerprint==null) { ws.close(1000,null); fail("Não foi possível verificar o PC."); lost(own); return@post }
            endpoint=endpoint?.copy(fingerprint=fingerprint)
            patch { it.copy(pairingFingerprint=fingerprint) }
          }
          val e=endpoint ?: return@post
          val id=UUID.randomUUID().toString(); val code=pairCode
          if(code!=null) { pairId=id; patch { it.copy(status="Pareando") }; ws.send(JSONObject().put("v",1).put("type","pair.request").put("id",id).put("deviceId",e.deviceId).put("deviceName",e.deviceName).put("code",code).toString()) }
          else { helloId=id; ws.send(JSONObject().put("v",1).put("type","hello").put("id",id).put("deviceId",e.deviceId).put("token",e.token).toString()) }
        } }
        override fun onMessage(ws:WebSocket,text:String) { main.post { if(own!=generation) return@post; val msg=Protocol.parse(text); if(msg==null) { ws.close(1002,null); return@post }; onMessage(ws,msg) } }
        override fun onMessage(ws:WebSocket,bytes:ByteString) { ws.close(1003,null) }
        override fun onClosing(ws:WebSocket,code:Int,reason:String) { ws.close(code,null) }
        override fun onClosed(ws:WebSocket,code:Int,reason:String) { main.post { lost(own) } }
        override fun onFailure(ws:WebSocket,t:Throwable,response:Response?) { main.post { if(own==generation) { fail(if(t is javax.net.ssl.SSLException) "A identidade do PC não confere." else "PC não encontrado na rede."); lost(own) } } }
      })
    } catch (_:Exception) { fail("Não foi possível conectar ao PC."); lost(own) }
  }
  private fun onMessage(ws:WebSocket,msg:Incoming) {
    when(msg) {
      is Incoming.PairPending -> if(pairCode!=null && msg.id==pairId) patch { it.copy(status="Aguardando aprovação") } else ws.close(1002,null)
      is Incoming.PairResult -> {
        if(pairCode==null || msg.id!=pairId) { ws.close(1002,null); return }
        val e=endpoint ?: return
        endpoint=e.copy(pcId=msg.pcId,pcName=msg.pcName,token=msg.token)
        pairCode=null; pairId=null; val id=UUID.randomUUID().toString(); helloId=id
        ws.send(JSONObject().put("v",1).put("type","hello").put("id",id).put("deviceId",e.deviceId).put("token",msg.token).toString())
      }
      is Incoming.Welcome -> {
        if(helloId!=msg.id || endpoint?.pcId!=msg.pcId || endpoint?.token.isNullOrBlank()) { ws.close(1002,null); return }
        if(pairingHandshake) try { store.persist(endpoint!!) } catch (_:Exception) {
          close(true)
          restoreFailedPair()
          patch { it.copy(status="Desconectado",error="Não foi possível proteger e salvar o pareamento. Verifique o armazenamento do celular e tente novamente.",snapshot=null) }
          return
        }
        val wasPairing=pairingHandshake
        helloId=null; pairingHandshake=false; previousPairEndpoint=null; authenticated=true; attempt=0; clipboardSession.retire()
        // Only a 0.6 PC (info.v1 or files.v2 in the welcome) keeps an emergency stop as a block;
        // an older PC ends it by closing the socket, so arming by itself there would undo it.
        if(msg.os.isNotEmpty() || msg.fileReceive) inputIntent.connected() else inputIntent.clear()
        dropInput()
        // An address found by discovery is kept for the next start; failing to save it changes nothing now.
        if(!wasPairing) endpoint?.let { e -> store.restore()?.takeIf { it.token==e.token && (it.host!=e.host || it.port!=e.port) }?.let { try { store.persist(e) } catch (_:Exception) {} } }
        if(msg.os!=view.pcOs) prefs.edit().putString("pcOs",msg.os).apply()
        // The copy made while disconnected waits for the first snapshot, whose revision it must build on.
        uploadAfterState=pendingUpload; pendingUpload=null
        if(hasFocus()) clipboardTracker.baseline(readLocalClipboardOrNull()) else clipboardTracker.suspend()
        patch { it.copy(status="Conectado",error="",pcName=msg.pcName,pcOs=msg.os,snapshot=null,receivedText="",inputSupported=msg.input,filesSupported=msg.files,filesTwoWay=msg.files && msg.fileReceive,searchSupported=msg.search,draggingVolume=null) }
        request(RemoteAction.StateGet) {}; sendSync(); files.connected()
      }
      is Incoming.FileTicket -> if(authenticated && view.filesSupported) files.ticket(msg) else ws.close(1002,null)
      is Incoming.FilePush -> if(authenticated && view.filesTwoWay) receiver.push(msg) else ws.close(1002,null)
      is Incoming.Error -> if(msg.id==pairId || msg.id==helloId) {
        val failedPairing=pairingHandshake
        if(msg.code=="unauthorized" && !failedPairing) {
          try { store.clear() } catch (_:Exception) { fail("Não foi possível remover a credencial local. Tente novamente.") }
          endpoint=null
        }
        val message=when(msg.code) { "pairing_expired"->"Código incorreto ou expirado."; "pairing_denied"->"Pareamento recusado no PC."; "rate_limited"->"Muitas tentativas. Aguarde um pouco."; "unauthorized"->"Acesso revogado. Pareie de novo."; "unavailable"->"Outro celular já está conectado a este PC."; else->"Não foi possível conectar." }
        close(true)
        if(failedPairing) restoreFailedPair()
        patch { it.copy(status="Desconectado",error=message) }
      } else if(authenticated) complete(msg.id,msg.message,msg.code)
      is Incoming.Ack -> if(authenticated) complete(msg.id,null,null) else ws.close(1002,null)
      is Incoming.State -> if(authenticated) onState(msg.snapshot) else ws.close(1002,null)
      is Incoming.Clipboard -> if(authenticated) onClipboard(msg) else ws.close(1002,null)
    }
  }
  private fun lost(own:Int) {
    if(own!=generation) return
    // Fence all callbacks from the retired socket before they can schedule another retry.
    generation++
    retireClipboard()
    reconnectTask?.let(main::removeCallbacks); reconnectTask=null
    if(pairingHandshake) restoreFailedPair()
    pairCode=null; pairId=null
    helloId=null; authenticated=false; clearDisarm(); socket?.cancel(); socket=null; client?.dispatcher?.executorService?.shutdown(); client=null; inputIntent.clear(); dropInput(); rejectPending(); files.connectionEnded(); receiver.connectionEnded(); volumeTask?.let(main::removeCallbacks); volumeTask=null; finalVolumeTimeout?.let(main::removeCallbacks); finalVolumeTimeout=null; finalVolume=null
    patch { it.copy(status="Desconectado",snapshot=null,inputSupported=false,filesSupported=false,filesTwoWay=false,searchSupported=false,draggingVolume=null) }
    if(requestedDisconnect || !foreground || endpoint?.token.isNullOrBlank()) return
    val delay=intArrayOf(1,2,4,8,16,30)[attempt.coerceAtMost(5)]*1000L+SecureRandom().nextInt(500)
    val retired=generation
    attempt++; val task=Runnable { if(retired==generation && foreground && !requestedDisconnect) { reconnectTask=null; open(true) } }; reconnectTask=task; main.postDelayed(task,delay)
  }
  private fun complete(id:String,error:String?,code:String?) { val p=pending.remove(id) ?: return; main.removeCallbacks(p.timeout); if(p.generation==generation) p.completion(error,code) }
  private fun rejectPending() { val all=pending.values.toList(); pending.clear(); all.forEach { main.removeCallbacks(it.timeout); it.completion("Conexão interrompida.",null) } }
  private fun request(action:RemoteAction,completion:(String?)->Unit) { send(action) { error,_ -> completion(error) } }
  /** Completes with the PC message and error code; returns the request id once the frame is queued. */
  private fun send(action:RemoteAction,completion:(String?,String?)->Unit):String? {
    val body=Protocol.action(action)
    if(body==null) { completion("Comando inválido.",null); return null }
    if(!authenticated || socket==null) { completion("Computador desconectado.",null); return null }
    val input=action is RemoteAction.InputEnable || action is RemoteAction.PointerMove || action is RemoteAction.PointerClick || action is RemoteAction.PointerScroll || action is RemoteAction.KeyboardText || action is RemoteAction.KeyboardKey
    val disable=action is RemoteAction.InputEnable && !action.enabled
    if(input && (!view.inputSupported || (!disable && !foreground))) { completion("Controle de entrada indisponível.",null); return null }
    if(action is RemoteAction.InputEnable && action.enabled && (!hasFocus() || view.snapshot?.input?.allowed!=true)) { completion("Controle de entrada não autorizado pelo PC.",null); return null }
    if(input && !disable && action !is RemoteAction.InputEnable && !canInput()) { completion("Ative o controle de entrada no PC e no celular.",null); return null }
    if(action is RemoteAction.ClipboardSet && action.automatic && (!hasFocus() || !view.preferences.realtime)) { completion("Envio automático está desativado.",null); return null }
    if((action is RemoteAction.FileOffer || action is RemoteAction.FileCancel) && !view.filesSupported) { completion("Atualize o PC Remote no Windows para enviar arquivos.",null); return null }
    if(action is RemoteAction.FileDecline && !view.filesTwoWay) { completion("Atualize o PC Remote no Windows para receber arquivos.",null); return null }
    if(action is RemoteAction.SearchOpen && !view.searchSupported) { completion("Atualize o PC Remote no Windows para pesquisar.",null); return null }
    val id=UUID.randomUUID().toString(); val frame=JSONObject().put("v",1).put("type","request").put("id",id).put("action",body).toString()
    if(frame.toByteArray(Charsets.UTF_8).size>Protocol.MAX_FRAME) { completion("Comando muito grande.",null); return null }
    val own=generation; val timeout=Runnable { pending.remove(id)?.completion?.invoke("O PC não confirmou a ação a tempo.",null) }
    pending[id]=Pending(completion,timeout,own); main.postDelayed(timeout,8000)
    if(socket?.send(frame)!=true) { complete(id,"Conexão interrompida.",null); return null }
    return id
  }
  override fun execute(action:RemoteAction,completion:(String?)->Unit) = run {
    if(action is RemoteAction.MediaControl) {
      val session=view.snapshot?.sessions?.firstOrNull { it.id==action.sessionId }
      val allowed=session?.capabilities?.let { when(action.command) { "play"->it.play; "pause"->it.pause; "previous"->it.previous; "next"->it.next; "seek"->it.seek; else->false } }==true
      if(!allowed) { completion("Este player não oferece esse controle."); return@run }
      // Refusals by the player all arrive as unavailable.
      send(action) { error,code -> completion(if(code=="unavailable") "O player não aceitou o comando." else error) }
      return@run
    }
    if(action is RemoteAction.PointerClick || action is RemoteAction.PointerScroll || action is RemoteAction.KeyboardText || action is RemoteAction.KeyboardKey) {
      if(discretePending) { completion("Aguarde a confirmação do controle anterior."); return@run }
      discretePending=true; val own=inputGeneration
      request(action) { e -> if(own==inputGeneration) discretePending=false; completion(if(own!=inputGeneration) "Controle interrompido." else e) }
    } else request(action,completion)
  }
  private fun onState(s:Snapshot) {
    val old=view.snapshot
    if(old!=null && s.revision<old.revision) return
    if(old!=null && s.revision==old.revision && finalVolume?.acked!=true) return
    clipboardSession.advanceRevision(s.clipboardRevision)
    // Blocked on the PC, or switched off there while armed: the PC took control back.
    if(inputIntent.pcState(s.input?.allowed,s.input?.enabled,view.inputArmed) && (view.inputArmed || view.inputActivating || activation!=null)) dropInput()
    var settled=false
    if(inputDisarming && s.input?.enabled==false) {
      disarmStateSeen=true
      if(disarmAck) { clearDisarm(); settled=true }
    }
    val activating=activation
    if(activating!=null && s.revision>activating.revision && s.input?.allowed==true && s.input.enabled) {
      activating.fresh=true
    }
    // The PC lists its current session first; a chip the user tapped wins while it exists.
    if(userPickedSession && s.sessions.none { it.id==view.selectedSessionId }) userPickedSession=false
    val selected=if(userPickedSession) view.selectedSessionId else s.sessions.firstOrNull()?.id ?: ""
    val f=finalVolume
    val done=f!=null && f.acked && f.connection==generation && f.drag==dragGeneration && s.audio.percent==f.value && s.revision>f.revision
    if(done) { finalVolume=null; finalVolumeTimeout?.let(main::removeCallbacks); finalVolumeTimeout=null }
    patch { it.copy(snapshot=s,selectedSessionId=selected,draggingVolume=if(done) null else it.draggingVolume) }
    if(activating?.fresh==true && activating.acked) finishActivation(activating,null)
    // Also arms by itself when the PC lifts a block (resumeInput checks the rest).
    if(settled || inputIntent.wanted) resumeInput()
    uploadAfterState?.let { pending ->
      uploadAfterState=null
      if(hasFocus() && view.preferences.realtime && readLocalClipboardOrNull()==pending) sendText(pending,true) {}
    }
    files.pump()
  }
  /**
   * Automatic copies follow "Sincronização em tempo real" and reach the phone clipboard
   * even in the background: Android limits reading there, not writing. "Buscar do PC"
   * always copies. The applied text becomes the tracker's reference, so it never echoes.
   */
  private fun onClipboard(e:Incoming.Clipboard) {
    if(!clipboardSession.accept(e,view.snapshot?.clipboardRevision ?: 0)) return
    val apply=!e.automatic || view.preferences.realtime
    if(!foreground) {
      clipboardSession.defer(e)
      if(!apply || writeClipboard(e.text)!=null) notifyClipboard()
      return
    }
    patch { it.copy(receivedText=e.text) }
    if(apply) writeClipboard(e.text)?.let { problem -> if(!e.automatic) fail(problem) }
  }
  private fun retireClipboard() {
    clipboardSession.retire()
    if(view.receivedText.isNotEmpty()) patch { it.copy(receivedText="") }
  }
  /** Both directions follow the single preference; manual sends never depend on it. */
  private fun sendSync() { if(authenticated) { val on=view.preferences.realtime; request(RemoteAction.SyncSet(on,on,false)) {} } }
  override fun setPreferences(preferences:Preferences) = run {
    prefs.edit().putBoolean("realtime",preferences.realtime).apply()
    patch { it.copy(preferences=preferences) }
    // Switching on never sends what was already copied.
    if(hasFocus()) clipboardTracker.baseline(readLocalClipboardOrNull()) else clipboardTracker.suspend()
    pendingUpload=null; uploadAfterState=null
    sendSync()
  }
  private sealed class LocalClipboardRead {
    data class Text(val value:String):LocalClipboardRead()
    data object Sensitive:LocalClipboardRead()
    data object TooLarge:LocalClipboardRead()
    data object Unavailable:LocalClipboardRead()
  }
  private fun readLocalClipboard():LocalClipboardRead { return try {
    val description=clipboard.primaryClipDescription
    when {
      description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE",false)==true -> LocalClipboardRead.Sensitive
      description?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)!=true -> LocalClipboardRead.Unavailable
      else -> {
        val clip=clipboard.primaryClip
        // Recheck the returned clip itself: another process may replace it after
        // primaryClipDescription was read, including with a sensitive sample.
        if(clip?.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE",false)==true)
          return LocalClipboardRead.Sensitive
        if(clip?.description?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)!=true)
          return LocalClipboardRead.Unavailable
        val text=if(clip.itemCount>0) clip.getItemAt(0).text?.toString() else null
        when {
          text==null -> LocalClipboardRead.Unavailable
          Protocol.validText(text) -> LocalClipboardRead.Text(text)
          text.length>65536 || text.toByteArray(Charsets.UTF_8).size>65536 -> LocalClipboardRead.TooLarge
          else -> LocalClipboardRead.Unavailable
        }
      }
    }
  } catch (_:Exception) { LocalClipboardRead.Unavailable } }
  private fun readLocalClipboardOrNull():String? = (readLocalClipboard() as? LocalClipboardRead.Text)?.value
  private fun localClipboardChanged() {
    if(!hasFocus()) return
    val text=clipboardTracker.observe(readLocalClipboardOrNull()) ?: return
    if(view.preferences.realtime && authenticated) sendText(text,true) {}
  }
  private fun sendText(text:String,automatic:Boolean,completion:(String?)->Unit) {
    if(!Protocol.validText(text)) { completion("Texto acima de 64 KiB ou inválido."); return }
    request(RemoteAction.ClipboardSet(UUID.randomUUID().toString(),text,automatic,clipboardSession.revision),completion)
  }
  override fun receiveShare(intent:Intent) = run {
    val streams=sharedStreams(intent)
    if(streams.isNotEmpty()) { files.add(streams); return@run }
    if(intent.action!=Intent.ACTION_SEND || intent.type!="text/plain") return@run
    val text=intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: return@run
    if(!Protocol.validText(text)) fail("Texto compartilhado inválido ou maior que 64 KiB.") else patch { it.copy(sharedText=text) }
  }
  @Suppress("DEPRECATION") // The typed overloads need API 33; these work on every supported version.
  private fun sharedStreams(intent:Intent):List<android.net.Uri> = try { when(intent.action) {
    Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
    Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM).orEmpty().filterNotNull()
    else -> emptyList()
  } } catch (_:RuntimeException) { emptyList() }
  override fun sendFiles(uris:List<android.net.Uri>) = run { files.add(uris) }
  override fun cancelFile() = run { files.cancel() }
  override fun clearFileQueue() = run { files.clearQueue() }
  override fun resumeFiles() = run { files.resume() }
  override fun cancelIncoming() = run { receiver.cancel() }
  /** The control connection's pinned client, while it is authenticated and the PC accepts files. */
  private fun fileLink():FileLink? {
    val c=client; val e=endpoint; val f=view.snapshot?.files
    return if(authenticated && socket!=null && c!=null && e!=null && view.filesSupported && f?.allowed==true) FileLink(c,e.port,generation,minOf(f.maxBytes,FileRules.MAX_BYTES),view.filesTwoWay) else null
  }
  private fun record(r:FileRecord) {
    fileHistory.addFirst(r)
    while(fileHistory.size>12) fileHistory.removeLast()
    publishFiles()
  }
  private fun publishFiles() {
    val v=sendView.copy(last=fileResult,incoming=incomingFile,history=fileHistory.toList())
    if(view.files!=v) patch { it.copy(files=v) }
    transferNotice(v)
  }
  /**
   * A foreground service keeps a large transfer going when the user switches apps.
   * It starts only from the foreground and stops a moment after both directions are
   * idle, so a quick file does not start and stop it at once.
   */
  private fun transferNotice(v:FilesView) {
    val moving=v.incoming ?: v.active
    if(moving==null) {
      if(serviceRunning && serviceStop==null) {
        val task=Runnable { serviceStop=null; serviceRunning=false; serviceShown=-2; TransferService.stop(app) }
        serviceStop=task; main.postDelayed(task,1500)
      }
      return
    }
    serviceStop?.let(main::removeCallbacks); serviceStop=null
    val permille=if(moving.streaming && moving.size>0) (moving.received*1000/moving.size).toInt() else -1
    val text=(if(v.incoming!=null) "Recebendo " else "Enviando ")+moving.name+(if(permille>=0) " · ${permille/10}%" else "")
    if(!serviceRunning) {
      if(foreground && moving.size>=SERVICE_MIN_BYTES) { serviceRunning=TransferService.start(app,text,permille); serviceShown=permille/10 }
    } else if(permille/10!=serviceShown) { serviceShown=permille/10; TransferService.update(app,text,permille) }
  }
  private fun clipboardFailure(message:String,completion:(String?)->Unit) {
    fail(message)
    completion(message)
  }
  private fun clipboardRequestError(error:String,sending:Boolean):String = when(error) {
    "Área de transferência ocupada" -> "Área de transferência do PC ocupada. Tente novamente."
    "Texto excede 64 KiB", "Texto acima de 64 KiB ou inválido." -> "Texto excede o limite de 64 KiB."
    "Conteúdo marcado como sensível" -> "Conteúdo marcado como sensível pelo PC."
    "Computador desconectado.", "Conexão interrompida." -> error
    else -> if(sending) "Não foi possível enviar o texto para o PC." else "Não foi possível copiar o texto do PC."
  }
  override fun sendClipboard(text:String?,completion:(String?)->Unit) = run {
    val value=if(text!=null) text else {
      if(!hasFocus()) { clipboardFailure("Abra o aplicativo para ler o texto copiado.",completion); return@run }
      when(val copied=readLocalClipboard()) {
        is LocalClipboardRead.Text -> copied.value
        LocalClipboardRead.Sensitive -> { clipboardFailure("Texto marcado como sensível pelo Android.",completion); return@run }
        LocalClipboardRead.TooLarge -> { clipboardFailure("Texto copiado ultrapassa o limite de 64 KiB.",completion); return@run }
        LocalClipboardRead.Unavailable -> { clipboardFailure("A área de transferência não contém texto simples válido.",completion); return@run }
      }
    }
    sendText(value,false) { e ->
      if(e==null) { patch { it.copy(sharedText="",error="") }; completion(null) }
      else clipboardFailure(clipboardRequestError(e,true),completion)
    }
  }
  override fun search(query:String,completion:(String?)->Unit) = run {
    val q=query.trim()
    if(!view.searchSupported) { completion("Atualize o PC Remote no Windows para pesquisar."); return@run }
    if(!Protocol.queryValid(q)) { completion(if(q.isEmpty()) "Digite ou fale o que pesquisar." else "A pesquisa passa de 200 caracteres ou tem caracteres inválidos."); return@run }
    send(RemoteAction.SearchOpen(q)) { error,code -> completion(when { error==null -> null; code=="rate_limited" -> "Muitas pesquisas seguidas. Aguarde um pouco."; code=="unavailable" -> "O PC não conseguiu abrir o navegador."; else -> error }) }
  }
  override fun getClipboard(completion:(String?)->Unit) = execute(RemoteAction.ClipboardGet) { e ->
    if(e==null) { patch { it.copy(error="") }; completion(null) }
    else clipboardFailure(clipboardRequestError(e,false),completion)
  }
  override fun copyReceived(completion:(String?)->Unit) = run {
    val text=view.receivedText
    if(text.isEmpty()) { completion("Não há texto recebido."); return@run }
    completion(writeClipboard(text))
  }
  /** Works in the background too; only reading the clipboard needs focus. */
  private fun writeClipboard(text:String):String? {
    if(!Protocol.validText(text)) return "Texto inválido."
    if(hasFocus() && readLocalClipboardOrNull()==text) { clipboardTracker.applied(text); return null }
    return try {
      clipboard.setPrimaryClip(ClipData.newPlainText("PC Remote",text))
      clipboardTracker.applied(text)
      null
    } catch (_:Exception) { "O Android não permitiu copiar o texto." }
  }
  override fun startDiscovery() = run {
    if(discovery!=null) return@run
    val listener=object:NsdManager.DiscoveryListener {
      override fun onDiscoveryStarted(serviceType:String) {}
      override fun onDiscoveryStopped(serviceType:String) {}
      override fun onStartDiscoveryFailed(serviceType:String,errorCode:Int) { main.post { discovery=null; fail("Não foi possível procurar PCs na rede.") } }
      override fun onStopDiscoveryFailed(serviceType:String,errorCode:Int) {}
      override fun onServiceLost(serviceInfo:NsdServiceInfo) { main.post { patch { v -> v.copy(discovered=v.discovered.filterNot { it.name==serviceInfo.serviceName }) } } }
      override fun onServiceFound(serviceInfo:NsdServiceInfo) { try { nsd.resolveService(serviceInfo,object:NsdManager.ResolveListener {
        override fun onResolveFailed(info:NsdServiceInfo,errorCode:Int) {}
        override fun onServiceResolved(info:NsdServiceInfo) { main.post {
          val host=(info.host as? Inet4Address)?.hostAddress ?: return@post
          if(!addressValid(host,info.port)) return@post
          val advertised=info.attributes["id"]?.toString(Charsets.UTF_8)
          val item=DiscoveredComputer(info.serviceName,host,info.port,advertised.orEmpty())
          patch { it.copy(discovered=(it.discovered.filterNot { d -> d.name==item.name }+item).takeLast(50)) }
          val e=endpoint ?: return@post
          if(foreground && !requestedDisconnect && socket==null && e.token.isNotBlank() && e.pcId==advertised) {
            endpoint=e.copy(host=host,port=info.port); open(true)
          }
        } }
      }) } catch (_:Exception) {} }
    }
    discovery=listener
    try { nsd.discoverServices("_pcremote._tcp.",NsdManager.PROTOCOL_DNS_SD,listener) } catch (_:Exception) { discovery=null }
  }
  override fun stopDiscovery() = run { discovery?.let { try { nsd.stopServiceDiscovery(it) } catch (_:Exception) {} }; discovery=null; patch { it.copy(discovered=emptyList()) } }
  private fun notifyClipboard() {
    try {
      if(Build.VERSION.SDK_INT>=33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return
      val intent=Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
      val pending=PendingIntent.getActivity(app,7,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
      val notification=NotificationCompat.Builder(app,"clipboard").setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Texto recebido do PC").setContentText("Toque para abrir.").setContentIntent(pending).setAutoCancel(true).build()
      (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(7,notification)
    } catch (_:RuntimeException) { /* Keep the received text in memory for the next foreground visit. */ }
  }
  override fun beginVolume(value:Int) = run { dragGeneration++; finalVolume=null; finalVolumeTimeout?.let(main::removeCallbacks); finalVolumeTimeout=null; patch { it.copy(draggingVolume=value.coerceIn(0,100)) }; throttleVolume(value) }
  override fun updateVolume(value:Int) = run { patch { it.copy(draggingVolume=value.coerceIn(0,100)) }; throttleVolume(value) }
  override fun endVolume(value:Int) = run {
    volumeTask?.let(main::removeCallbacks); volumeTask=null
    val v=value.coerceIn(0,100); patch { it.copy(draggingVolume=v) }
    val f=FinalVolume(v,generation,dragGeneration,view.snapshot?.revision ?: -1); finalVolume=f
    finalVolumeTimeout?.let(main::removeCallbacks)
    val timeout=Runnable { if(finalVolume===f) { finalVolume=null; finalVolumeTimeout=null; patch { it.copy(draggingVolume=null,error="O PC não confirmou o volume atual.") } } }
    finalVolumeTimeout=timeout; main.postDelayed(timeout,8000)
    request(RemoteAction.VolumeSet(v)) { e ->
      if(finalVolume!==f || f.connection!=generation || f.drag!=dragGeneration) return@request
      if(e!=null) { finalVolume=null; finalVolumeTimeout?.let(main::removeCallbacks); finalVolumeTimeout=null; patch { it.copy(draggingVolume=null,error=e) }; return@request }
      f.acked=true
      val current=view.snapshot
      if(current!=null && current.audio.percent==v && current.revision>f.revision) { finalVolume=null; finalVolumeTimeout?.let(main::removeCallbacks); finalVolumeTimeout=null; patch { it.copy(draggingVolume=null) } }
      else request(RemoteAction.StateGet) { error -> if(finalVolume===f) { if(error!=null) { finalVolume=null; finalVolumeTimeout?.let(main::removeCallbacks); finalVolumeTimeout=null; patch { it.copy(draggingVolume=null,error=error) } } } }
    }
  }
  private fun throttleVolume(value:Int) {
    val now=android.os.SystemClock.uptimeMillis()
    if(now-volumeAt>=100) { volumeAt=now; request(RemoteAction.VolumeSet(value.coerceIn(0,100))) {} }
    else if(volumeTask==null) { val task=Runnable { volumeTask=null; volumeAt=android.os.SystemClock.uptimeMillis(); view.draggingVolume?.let { request(RemoteAction.VolumeSet(it)) {} } }; volumeTask=task; main.postDelayed(task,100-(now-volumeAt)) }
  }
  private fun canInput()=authenticated && foreground && hasFocus() && view.inputSupported && view.inputArmed && view.snapshot?.input?.allowed==true && view.snapshot?.input?.enabled==true
  private fun finishActivation(a:Activation,error:String?) {
    if(activation!==a) return
    a.timeout?.let(main::removeCallbacks); activation=null
    if(error==null && a.generation==inputGeneration && hasFocus() && view.snapshot?.input?.allowed==true && view.snapshot?.input?.enabled==true) { inputIntent.confirmed(); patch { it.copy(inputArmed=true,inputActivating=false) } }
    else { inputIntent.clear(); patch { it.copy(inputActivating=false) }; startInputDisarm() }
    a.completion(if(error==null && view.inputArmed) null else error ?: "PC ainda não confirmou o controle de entrada.")
  }
  /** Stops local input at once; the caller decides whether the user's wish survives. */
  private fun dropInput() {
    // An interrupted re-arm stays silent: the pause that interrupted it re-arms again later.
    activation?.let { a -> a.timeout?.let(main::removeCallbacks); activation=null; if(!a.automatic) a.completion("Controle interrompido."); startInputDisarm() }
    inputGeneration++; discretePending=false; motion.cancel(); scrollMotion.cancel(); patch { it.copy(inputArmed=false,inputActivating=false) }
  }
  override fun enableInput(completion:(String?)->Unit) = run {
    // A pause already queued the re-arm behind its disarm.
    if(inputIntent.wanted && inputDisarming) { completion(null); return@run }
    inputIntent.userOn()
    activate(false,completion)
  }
  private fun activate(automatic:Boolean,completion:(String?)->Unit) {
    if(inputNeedsDisarm && !inputDisarming) startInputDisarm()
    if(inputDisarming) { completion("Aguarde o PC desativar o controle de entrada."); return }
    if(!authenticated || !hasFocus() || !view.inputSupported || view.snapshot?.input?.allowed!=true) { completion("Controle de entrada indisponível neste PC."); return }
    if(view.inputActivating) { completion(null); return }
    val own=inputGeneration; val a=Activation(own,view.snapshot?.revision ?: -1,automatic,completion); activation=a
    val timer=Runnable { finishActivation(a,"PC ainda não confirmou o controle de entrada.") }; a.timeout=timer; main.postDelayed(timer,8000)
    patch { it.copy(inputActivating=true) }
    request(RemoteAction.InputEnable(true)) { e ->
      if(activation!==a || own!=inputGeneration) return@request
      if(e!=null) { finishActivation(a,e); return@request }
      a.acked=true
      if(a.fresh) finishActivation(a,null)
      else request(RemoteAction.StateGet) { stateError -> if(stateError!=null && activation===a) finishActivation(a,stateError) }
    }
  }
  override fun disableInput() = run {
    inputIntent.userDisabled()
    dropInput()
    startInputDisarm()
  }
  /** Background or focus loss: a phone that is not in use must not leave the PC armed, but the wish survives. */
  private fun pauseInput() {
    dropInput()
    startInputDisarm()
  }
  /** Arms again a session paused by background or focus loss, once nothing is in flight. */
  private fun resumeInput() {
    val usable=authenticated && hasFocus() && view.inputSupported && view.snapshot?.input?.allowed==true
    if(!inputIntent.shouldRearm(usable,view.inputArmed,view.inputActivating || inputDisarming)) return
    // A disarm that failed earlier must settle first; its completion calls back here.
    if(inputNeedsDisarm) { startInputDisarm(); return }
    activate(true) { error -> if(error!=null) fail(error) }
  }
  private fun startInputDisarm() {
    if(inputDisarming || !authenticated || !view.inputSupported) return
    inputNeedsDisarm=true; inputDisarming=true; disarmAck=false; disarmStateSeen=view.snapshot?.input?.enabled==false
    val own=generation
    // An unconfirmed disarm also drops the wish: arming again needs a fresh tap.
    val timer=Runnable { if(own==generation && inputDisarming) { inputDisarming=false; disarmTimer=null; inputIntent.clear(); fail("O PC não confirmou a desativação do controle de entrada.") } }
    disarmTimer=timer; main.postDelayed(timer,8000)
    request(RemoteAction.InputEnable(false)) { error ->
      if(own!=generation || !inputDisarming) return@request
      if(error!=null) { inputDisarming=false; disarmTimer?.let(main::removeCallbacks); disarmTimer=null; inputIntent.clear(); fail("Não foi possível desativar o controle de entrada no PC."); return@request }
      disarmAck=true
      if(disarmStateSeen) { clearDisarm(); resumeInput() }
      else request(RemoteAction.StateGet) { stateError -> if(own==generation && stateError!=null) { inputDisarming=false; inputIntent.clear(); fail(stateError) } }
    }
  }
  private fun clearDisarm() {
    disarmTimer?.let(main::removeCallbacks); disarmTimer=null
    inputDisarming=false; inputNeedsDisarm=false; disarmAck=false; disarmStateSeen=false
  }
  override fun movePointer(dx:Float,dy:Float) = run { motion.move(dx,dy) }
  override fun cancelPointerMotion() = run { motion.cancel() }
  override fun scroll(steps:Int) = run { scrollMotion.add(steps) }
  override fun cancelScroll() = run { scrollMotion.cancel() }
  override fun clearReceived() = run { if(view.receivedText.isNotEmpty()) patch { it.copy(receivedText="") } }
  override fun clearError() = run { if(view.error.isNotEmpty()) patch { it.copy(error="") } }
  override fun isInputTextAllowed(text:String)=Protocol.validText(text,1024,true)
  override fun selectSession(id:String)=run { if(view.snapshot?.sessions?.any { it.id==id }==true) { userPickedSession=true; patch { it.copy(selectedSessionId=id) } } }

  private companion object {
    /** One header line each; see PROTOCOL.md for what each extension adds. */
    val FEATURES=listOf("input.v1","files.v1","files.v2","search.v1","info.v1")
    /** Smaller files finish before a foreground service would help. */
    const val SERVICE_MIN_BYTES=8L*1024*1024
  }
}
