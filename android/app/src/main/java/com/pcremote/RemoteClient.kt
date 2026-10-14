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
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
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
  override var view = UiState(preferences = Preferences(prefs.getBoolean("upload",false),prefs.getBoolean("download",false),prefs.getBoolean("paused",false),prefs.getBoolean("autoApply",false),prefs.getBoolean("haptics",true)),pcName=store.restore()?.pcName ?: "")
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
  private var pairId: String? = null
  private var helloId: String? = null
  private var attempt = 0
  private var reconnectTask: Runnable? = null
  private val pending = LinkedHashMap<String, Pending>()
  private data class Pending(val completion:(String?)->Unit,val timeout:Runnable,val generation:Int)
  private val events = ClipboardEvents()
  private var clipboardRevision = 0L
  private var pendingBackgroundText: String? = null
  private var lastClipboardText: String? = null
  private var suppressedText: String? = null
  private var lastAppliedText: String? = null
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
  private data class Activation(val generation:Int,val revision:Long,val completion:(String?)->Unit,var acked:Boolean=false,var fresh:Boolean=false,var timeout:Runnable?=null)
  private var activation:Activation?=null
  private val motion = InputMotion(main,{ canInput() },{ dx,dy,done -> request(RemoteAction.PointerMove(dx,dy),done) },{ fail(it) })
  private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener { main.post { localClipboardChanged() } }
  private var discovery: NsdManager.DiscoveryListener? = null
  private val networkCallback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) { main.post { if(foreground && !requestedDisconnect) { startDiscovery(); autoReconnect() } } }
    override fun onLost(network: Network) { main.post { if(!requestedDisconnect) { socket?.cancel(); lost(generation) } } }
  }
  init {
    clipboard.addPrimaryClipChangedListener(clipboardListener)
    try { connectivity.registerDefaultNetworkCallback(networkCallback) } catch (_: Exception) {}
    if(Build.VERSION.SDK_INT>=26) (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel("clipboard","Texto recebido",NotificationManager.IMPORTANCE_DEFAULT))
  }
  private fun run(block:()->Unit) { if(Looper.myLooper()==Looper.getMainLooper()) block() else main.post(block) }
  private fun patch(f:(UiState)->UiState) { view=f(view); listeners.toList().forEach { it(view) } }
  private fun fail(text:String) = patch { it.copy(error=text) }
  override fun observe(listener:(UiState)->Unit):()->Unit { run { listeners.add(listener); listener(view) }; return { run { listeners.remove(listener) } } }
  override fun foreground(activity:Activity) = run {
    this.activity=WeakReference(activity); foreground=true
    lastClipboardText=readLocalClipboardOrNull()
    pendingBackgroundText?.let { onClipboard(Incoming.Clipboard(UUID.randomUUID().toString(),clipboardRevision,it,false),false); pendingBackgroundText=null }
    autoReconnect(); if(!requestedDisconnect) startDiscovery()
  }
  override fun background() = run {
    foreground=false; activity.clear(); lastClipboardText=null
    disableInput(); stopDiscovery()
  }
  private fun hasFocus() = foreground && activity.get()?.hasWindowFocus()==true
  override fun pair(host:String,port:Int,fingerprint:String,code:String) = run {
    val h=host.trim(); val fp=fingerprint.trim().replace(":","").lowercase()
    if(!addressValid(h,port) || !fp.matches(Regex("[a-f0-9]{64}")) || !code.trim().matches(Regex("[0-9]{8}"))) { fail("Confira endereço, porta, código e fingerprint."); return@run }
    close(false)
    requestedDisconnect=false; endpoint=PairedComputer(h,port,fp,UUID.randomUUID().toString(),"Celular Android")
    pairCode=code.trim(); patch { it.copy(status="Pareando",error="",snapshot=null,receivedText="") }; open(false)
  }
  private fun addressValid(host:String,port:Int)=port in 1..65535 && host.matches(Regex("[a-zA-Z0-9.-]{1,253}")) && !host.contains("..")
  override fun reconnect() = run { requestedDisconnect=false; autoReconnect() }
  private fun autoReconnect() {
    if(socket != null || requestedDisconnect) return
    endpoint=endpoint ?: store.restore()
    if(endpoint?.token.isNullOrBlank()) return
    pairCode=null; open(true)
  }
  override fun disconnect() = run { close(true); patch { it.copy(status="Desconectado",snapshot=null,receivedText="",draggingVolume=null) } }
  override fun forget() = run { close(true); prefs.edit().remove("credential").apply(); endpoint=null; patch { it.copy(status="Desconectado",pcName="",snapshot=null,receivedText="") } }
  override fun savedAddress():Pair<String,Int>? = (endpoint ?: store.restore())?.let { it.host to it.port }
  override fun updateAddress(host:String,port:Int,completion:(String?)->Unit) = run {
    val old=endpoint ?: store.restore()
    if(old==null || old.token.isBlank()) { completion("Pareie um PC primeiro."); return@run }
    if(!addressValid(host.trim(),port)) { completion("Endereço ou porta inválida."); return@run }
    close(false); endpoint=old.copy(host=host.trim().lowercase(),port=port); store.persist(endpoint!!)
    requestedDisconnect=false; open(true); completion(null)
  }
  private fun close(user:Boolean) {
    requestedDisconnect=user; reconnectTask?.let(main::removeCallbacks); reconnectTask=null
    generation++; authenticated=false; clearDisarm(); pairId=null; helloId=null; pairCode=null
    dropInput(); rejectPending(); volumeTask?.let(main::removeCallbacks); volumeTask=null; finalVolumeTimeout?.let(main::removeCallbacks); finalVolumeTimeout=null; finalVolume=null
    socket?.close(1000,"Desconectado"); socket=null; client?.dispatcher?.executorService?.shutdown(); client=null
  }
  private fun open(reconnecting:Boolean) {
    val e=endpoint ?: return
    reconnectTask?.let(main::removeCallbacks); reconnectTask=null
    generation++; val own=generation; authenticated=false; clearDisarm(); dropInput(); rejectPending()
    patch { it.copy(status=if(reconnecting) "Reconectando" else "Conectando",snapshot=null,inputSupported=false) }
    try {
      val trust=object:X509TrustManager {
        override fun getAcceptedIssuers():Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain:Array<X509Certificate>,authType:String) { throw CertificateException("client") }
        override fun checkServerTrusted(chain:Array<X509Certificate>,authType:String) {
          if(chain.size!=1) throw CertificateException("chain")
          chain[0].checkValidity()
          val digest=MessageDigest.getInstance("SHA-256").digest(chain[0].encoded).joinToString("") { "%02x".format(it) }
          if(digest!=e.fingerprint) throw CertificateException("fingerprint")
        }
      }
      val ssl=SSLContext.getInstance("TLS").apply { init(null,arrayOf<TrustManager>(trust),SecureRandom()) }
      client=OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory,trust).dns(object:Dns {
        override fun lookup(hostname:String):List<InetAddress> {
          if(hostname!="pc-remote.local") throw UnknownHostException(hostname)
          return InetAddress.getAllByName(e.host).filterIsInstance<Inet4Address>().ifEmpty { throw UnknownHostException(e.host) }
        }
      }).connectTimeout(8,TimeUnit.SECONDS).pingInterval(20,TimeUnit.SECONDS).build()
      socket=client!!.newWebSocket(Request.Builder().url("wss://pc-remote.local:${e.port}/v1/ws").header("X-PC-Remote-Features","input.v1").build(),object:WebSocketListener() {
        override fun onOpen(ws:WebSocket,response:Response) { main.post {
          if(own!=generation) { ws.close(1000,null); return@post }
          val id=UUID.randomUUID().toString(); val code=pairCode
          if(code!=null) { pairId=id; patch { it.copy(status="Pareando") }; ws.send(JSONObject().put("v",1).put("type","pair.request").put("id",id).put("deviceId",e.deviceId).put("deviceName",e.deviceName).put("code",code).toString()) }
          else { helloId=id; ws.send(JSONObject().put("v",1).put("type","hello").put("id",id).put("deviceId",e.deviceId).put("token",e.token).toString()) }
        } }
        override fun onMessage(ws:WebSocket,text:String) { main.post { if(own!=generation) return@post; val msg=Protocol.parse(text); if(msg==null) { ws.close(1002,null); return@post }; onMessage(ws,msg) } }
        override fun onMessage(ws:WebSocket,bytes:ByteString) { ws.close(1003,null) }
        override fun onClosing(ws:WebSocket,code:Int,reason:String) { ws.close(code,null) }
        override fun onClosed(ws:WebSocket,code:Int,reason:String) { main.post { lost(own) } }
        override fun onFailure(ws:WebSocket,t:Throwable,response:Response?) { main.post { if(own==generation) { fail(if(t is javax.net.ssl.SSLException) "Identidade TLS do PC não confere ou certificado expirou." else "Não foi possível conectar ao PC na rede local."); lost(own) } } }
      })
    } catch (_:Exception) { fail("Endereço ou certificado inválido."); lost(own) }
  }
  private fun onMessage(ws:WebSocket,msg:Incoming) {
    when(msg) {
      is Incoming.PairPending -> if(pairCode!=null && msg.id==pairId) patch { it.copy(status="Aguardando aprovação") } else ws.close(1002,null)
      is Incoming.PairResult -> {
        if(pairCode==null || msg.id!=pairId) { ws.close(1002,null); return }
        val e=endpoint ?: return
        endpoint=e.copy(pcId=msg.pcId,pcName=msg.pcName,token=msg.token); store.persist(endpoint!!)
        pairCode=null; pairId=null; val id=UUID.randomUUID().toString(); helloId=id
        ws.send(JSONObject().put("v",1).put("type","hello").put("id",id).put("deviceId",e.deviceId).put("token",msg.token).toString())
      }
      is Incoming.Welcome -> {
        if(helloId!=msg.id || endpoint?.pcId!=msg.pcId || endpoint?.token.isNullOrBlank()) { ws.close(1002,null); return }
        helloId=null; authenticated=true; attempt=0; events.clear(); clipboardRevision=0; dropInput()
        patch { it.copy(status="Conectado",error="",pcName=msg.pcName,snapshot=null,receivedText="",inputSupported=msg.input,draggingVolume=null) }
        request(RemoteAction.StateGet) {}; sendSync()
      }
      is Incoming.Error -> if(msg.id==pairId || msg.id==helloId) {
        if(msg.code=="unauthorized") { prefs.edit().remove("credential").apply(); endpoint=null }
        val message=when(msg.code) { "pairing_expired"->"Código expirou. Gere outro no PC."; "pairing_denied"->"Pareamento recusado no PC."; "rate_limited"->"Muitas tentativas. Aguarde."; "unauthorized"->"Acesso revogado. Pareie novamente."; "unavailable"->"Outro celular já controla este PC."; else->"Pareamento ou autenticação falhou." }
        close(true); patch { it.copy(status="Desconectado",error=message) }
      } else if(authenticated) complete(msg.id,msg.message)
      is Incoming.Ack -> if(authenticated) complete(msg.id,null) else ws.close(1002,null)
      is Incoming.State -> if(authenticated) onState(msg.snapshot) else ws.close(1002,null)
      is Incoming.Clipboard -> if(authenticated) onClipboard(msg,foreground) else ws.close(1002,null)
    }
  }
  private fun lost(own:Int) {
    if(own!=generation) return
    authenticated=false; clearDisarm(); socket=null; client?.dispatcher?.executorService?.shutdown(); client=null; dropInput(); rejectPending(); finalVolumeTimeout?.let(main::removeCallbacks); finalVolumeTimeout=null; finalVolume=null
    patch { it.copy(status="Desconectado",snapshot=null,inputSupported=false,draggingVolume=null) }
    if(requestedDisconnect || !foreground || endpoint?.token.isNullOrBlank()) return
    val delay=intArrayOf(1,2,4,8,16,30)[attempt.coerceAtMost(5)]*1000L+SecureRandom().nextInt(500)
    attempt++; val task=Runnable { reconnectTask=null; if(foreground && !requestedDisconnect) open(true) }; reconnectTask=task; main.postDelayed(task,delay)
  }
  private fun complete(id:String,error:String?) { val p=pending.remove(id) ?: return; main.removeCallbacks(p.timeout); if(p.generation==generation) p.completion(error) }
  private fun rejectPending() { val all=pending.values.toList(); pending.clear(); all.forEach { main.removeCallbacks(it.timeout); it.completion("Conexão interrompida.") } }
  private fun request(action:RemoteAction,completion:(String?)->Unit) {
    val body=Protocol.action(action)
    if(body==null) { completion("Comando inválido."); return }
    if(!authenticated || socket==null) { completion("Computador desconectado."); return }
    val input=action is RemoteAction.InputEnable || action is RemoteAction.PointerMove || action is RemoteAction.PointerClick || action is RemoteAction.PointerScroll || action is RemoteAction.KeyboardText || action is RemoteAction.KeyboardKey
    val disable=action is RemoteAction.InputEnable && !action.enabled
    if(input && (!view.inputSupported || (!disable && !foreground))) { completion("Controle de entrada indisponível."); return }
    if(action is RemoteAction.InputEnable && action.enabled && (!hasFocus() || view.snapshot?.input?.allowed!=true)) { completion("Controle de entrada não autorizado pelo PC."); return }
    if(input && !disable && action !is RemoteAction.InputEnable && !canInput()) { completion("Ative o controle de entrada no PC e no celular."); return }
    if(action is RemoteAction.ClipboardSet && action.automatic && (!hasFocus() || !view.preferences.upload || view.preferences.paused)) { completion("Envio automático está desativado."); return }
    val id=UUID.randomUUID().toString(); val frame=JSONObject().put("v",1).put("type","request").put("id",id).put("action",body).toString()
    if(frame.toByteArray(Charsets.UTF_8).size>Protocol.MAX_FRAME) { completion("Comando muito grande."); return }
    val own=generation; val timeout=Runnable { pending.remove(id)?.completion?.invoke("O PC não confirmou a ação a tempo.") }
    pending[id]=Pending(completion,timeout,own); main.postDelayed(timeout,8000)
    if(socket?.send(frame)!=true) complete(id,"Conexão interrompida.")
  }
  override fun execute(action:RemoteAction,completion:(String?)->Unit) = run {
    if(action is RemoteAction.MediaControl) {
      val session=view.snapshot?.sessions?.firstOrNull { it.id==action.sessionId }
      val allowed=session?.capabilities?.let { when(action.command) { "play"->it.play; "pause"->it.pause; "previous"->it.previous; "next"->it.next; "seek"->it.seek; else->false } }==true
      if(!allowed) { completion("Controle de mídia indisponível nesta sessão."); return@run }
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
    clipboardRevision=maxOf(clipboardRevision,s.clipboardRevision)
    if(s.input?.allowed==false || (s.input?.enabled==false && view.inputArmed)) dropInput()
    if(inputDisarming && s.input?.enabled==false) {
      disarmStateSeen=true
      if(disarmAck) clearDisarm()
    }
    val activating=activation
    if(activating!=null && s.revision>activating.revision && s.input?.allowed==true && s.input.enabled) {
      activating.fresh=true
    }
    val selected=if(s.sessions.any { it.id==view.selectedSessionId }) view.selectedSessionId else s.sessions.firstOrNull()?.id ?: ""
    val f=finalVolume
    val done=f!=null && f.acked && f.connection==generation && f.drag==dragGeneration && s.audio.percent==f.value && s.revision>f.revision
    if(done) { finalVolume=null; finalVolumeTimeout?.let(main::removeCallbacks); finalVolumeTimeout=null }
    patch { it.copy(snapshot=s,selectedSessionId=selected,draggingVolume=if(done) null else it.draggingVolume) }
    if(activating?.fresh==true && activating.acked) finishActivation(activating,null)
  }
  private fun onClipboard(e:Incoming.Clipboard,allowAuto:Boolean) {
    if(!events.accept(e,view.snapshot?.clipboardRevision ?: 0)) return
    clipboardRevision=maxOf(clipboardRevision,e.revision)
    if(!foreground) { pendingBackgroundText=e.text; notifyClipboard(); return }
    patch { it.copy(receivedText=e.text) }
    if(allowAuto && hasFocus() && view.preferences.download && view.preferences.autoApply && !view.preferences.paused) writeClipboard(e.text)
  }
  private fun sendSync() { if(authenticated) { val p=view.preferences; request(RemoteAction.SyncSet(p.upload,p.download,p.paused)) {} } }
  override fun setPreferences(preferences:Preferences) = run {
    prefs.edit().putBoolean("upload",preferences.upload).putBoolean("download",preferences.download).putBoolean("paused",preferences.paused).putBoolean("autoApply",preferences.autoApply).putBoolean("haptics",preferences.haptics).apply()
    patch { it.copy(preferences=preferences) }; lastClipboardText=if(foreground && preferences.upload && !preferences.paused) readLocalClipboardOrNull() else null; sendSync()
  }
  private fun sensitive()=clipboard.primaryClipDescription?.extras?.getBoolean("android.content.extra.IS_SENSITIVE",false)==true
  private fun readLocalClipboardOrNull():String? = try {
    if(sensitive() || clipboard.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)!=true) null
    else clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.takeIf(Protocol::validText)
  } catch (_:Exception) { null }
  private fun localClipboardChanged() {
    if(!hasFocus() || !view.preferences.upload || view.preferences.paused) return
    val text=readLocalClipboardOrNull() ?: return
    if(text==suppressedText) { suppressedText=null; lastClipboardText=text; return }
    if(text==lastClipboardText) return
    lastClipboardText=text
    if(text==lastAppliedText) { lastAppliedText=null; return }
    if(text.isNotEmpty()) sendText(text,true) {}
  }
  private fun sendText(text:String,automatic:Boolean,completion:(String?)->Unit) {
    if(!Protocol.validText(text)) { completion("Texto acima de 64 KiB ou inválido."); return }
    request(RemoteAction.ClipboardSet(UUID.randomUUID().toString(),text,automatic,clipboardRevision),completion)
  }
  override fun receiveShare(intent:Intent) = run {
    if(intent.action!=Intent.ACTION_SEND || intent.type!="text/plain") return@run
    val text=intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: return@run
    if(!Protocol.validText(text)) fail("Texto compartilhado inválido ou maior que 64 KiB.") else patch { it.copy(sharedText=text) }
  }
  override fun sendClipboard(text:String?,completion:(String?)->Unit) = run {
    val value=if(text!=null) text else {
      if(!hasFocus()) { completion("Abra o aplicativo para ler o texto copiado."); return@run }
      if(sensitive()) { completion("Texto marcado como sensível pelo Android."); return@run }
      val copied=readLocalClipboardOrNull()
      if(copied==null) { completion("A área de transferência não contém texto simples válido."); return@run }
      copied
    }
    sendText(value,false) { e -> if(e==null) patch { it.copy(sharedText="") }; completion(e) }
  }
  override fun getClipboard(completion:(String?)->Unit) = execute(RemoteAction.ClipboardGet,completion)
  override fun copyReceived(completion:(String?)->Unit) = run {
    val text=view.receivedText
    if(text.isEmpty()) { completion("Não há texto recebido."); return@run }
    completion(writeClipboard(text))
  }
  private fun writeClipboard(text:String):String? {
    if(!hasFocus()) return "Abra o aplicativo para copiar o texto."
    if(!Protocol.validText(text)) return "Texto inválido."
    return try { suppressedText=text; lastAppliedText=text; clipboard.setPrimaryClip(ClipData.newPlainText("PC Remote",text)); null } catch (_:Exception) { suppressedText=null; "O Android não permitiu copiar o texto." }
  }
  override fun scanQr(activity:Activity,completion:(Invite?,String?)->Unit) {
    GmsBarcodeScanning.getClient(activity).startScan().addOnSuccessListener { barcode -> run {
      val raw=barcode.rawValue
      val invite=raw?.let(Protocol::parseInvite)
      completion(invite,if(invite==null) "QR incompatível." else null)
    } }.addOnFailureListener { run { completion(null,"Não foi possível ler o QR.") } }.addOnCanceledListener { run { completion(null,"Leitura cancelada.") } }
  }
  override fun startDiscovery() = run {
    if(discovery!=null) return@run
    val listener=object:NsdManager.DiscoveryListener {
      override fun onDiscoveryStarted(serviceType:String) {}
      override fun onDiscoveryStopped(serviceType:String) {}
      override fun onStartDiscoveryFailed(serviceType:String,errorCode:Int) { main.post { discovery=null; fail("Descoberta indisponível. Use o endereço manual.") } }
      override fun onStopDiscoveryFailed(serviceType:String,errorCode:Int) {}
      override fun onServiceLost(serviceInfo:NsdServiceInfo) { main.post { patch { v -> v.copy(discovered=v.discovered.filterNot { it.name==serviceInfo.serviceName }) } } }
      override fun onServiceFound(serviceInfo:NsdServiceInfo) { try { nsd.resolveService(serviceInfo,object:NsdManager.ResolveListener {
        override fun onResolveFailed(info:NsdServiceInfo,errorCode:Int) {}
        override fun onServiceResolved(info:NsdServiceInfo) { main.post {
          val host=(info.host as? Inet4Address)?.hostAddress ?: return@post
          if(!addressValid(host,info.port)) return@post
          val item=DiscoveredComputer(info.serviceName,host,info.port)
          patch { it.copy(discovered=(it.discovered.filterNot { d -> d.name==item.name }+item).takeLast(50)) }
          val e=endpoint ?: return@post
          val advertised=info.attributes["id"]?.toString(Charsets.UTF_8)
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
    if(Build.VERSION.SDK_INT>=33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return
    val intent=Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val pending=PendingIntent.getActivity(app,7,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val notification=NotificationCompat.Builder(app,"clipboard").setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Texto recebido do PC").setContentText("Abra o PC Remote para copiar.").setContentIntent(pending).setAutoCancel(true).build()
    (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(7,notification)
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
    if(error==null && a.generation==inputGeneration && hasFocus() && view.snapshot?.input?.allowed==true && view.snapshot?.input?.enabled==true) patch { it.copy(inputArmed=true,inputActivating=false) }
    else { patch { it.copy(inputActivating=false) }; startInputDisarm() }
    a.completion(if(error==null && view.inputArmed) null else error ?: "PC ainda não confirmou o controle de entrada.")
  }
  private fun dropInput() {
    activation?.let { a -> a.timeout?.let(main::removeCallbacks); activation=null; a.completion("Controle interrompido."); startInputDisarm() }
    inputGeneration++; discretePending=false; motion.cancel(); patch { it.copy(inputArmed=false,inputActivating=false) }
  }
  override fun enableInput(completion:(String?)->Unit) = run {
    if(inputNeedsDisarm && !inputDisarming) startInputDisarm()
    if(inputDisarming) { completion("Aguarde o PC desativar o controle de entrada."); return@run }
    if(!authenticated || !hasFocus() || !view.inputSupported || view.snapshot?.input?.allowed!=true) { completion("Controle de entrada indisponível neste PC."); return@run }
    if(view.inputActivating) { completion(null); return@run }
    val own=inputGeneration; val a=Activation(own,view.snapshot?.revision ?: -1,completion); activation=a
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
    dropInput()
    startInputDisarm()
  }
  private fun startInputDisarm() {
    if(inputDisarming || !authenticated || !view.inputSupported) return
    inputNeedsDisarm=true; inputDisarming=true; disarmAck=false; disarmStateSeen=view.snapshot?.input?.enabled==false
    val own=generation
    val timer=Runnable { if(own==generation && inputDisarming) { inputDisarming=false; disarmTimer=null; fail("O PC não confirmou a desativação do controle de entrada.") } }
    disarmTimer=timer; main.postDelayed(timer,8000)
    request(RemoteAction.InputEnable(false)) { error ->
      if(own!=generation || !inputDisarming) return@request
      if(error!=null) { inputDisarming=false; disarmTimer?.let(main::removeCallbacks); disarmTimer=null; fail("Não foi possível desativar o controle de entrada no PC."); return@request }
      disarmAck=true
      if(disarmStateSeen) clearDisarm()
      else request(RemoteAction.StateGet) { stateError -> if(own==generation && stateError!=null) { inputDisarming=false; fail(stateError) } }
    }
  }
  private fun clearDisarm() {
    disarmTimer?.let(main::removeCallbacks); disarmTimer=null
    inputDisarming=false; inputNeedsDisarm=false; disarmAck=false; disarmStateSeen=false
  }
  override fun movePointer(dx:Float,dy:Float) = run { motion.move(dx,dy) }
  override fun cancelPointerMotion() = run { motion.cancel() }
  override fun isInputTextAllowed(text:String)=Protocol.validText(text,1024,true)
  override fun selectSession(id:String)=run { if(view.snapshot?.sessions?.any { it.id==id }==true) patch { it.copy(selectedSessionId=id) } }
}
