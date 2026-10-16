package com.pcremote

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ProtocolTest {
  private val id = UUID.randomUUID().toString()
  @Test fun strictIncomingRejectsCoercionAndUnknownFields() {
    val good="""{"v":1,"type":"ack","id":"$id"}"""
    assertEquals(Incoming.Ack(id),Protocol.parse(good))
    assertNull(Protocol.parse(good.dropLast(1)+",\"extra\":1}"))
    assertNull(Protocol.parse(good.replace("\"v\":1","\"v\":\"1\"")))
    assertNull(Protocol.parse(good.replace("\"id\":\"$id\"","\"id\":1")))
    assertNull(Protocol.parse(good+" ".repeat(Protocol.MAX_FRAME)))
  }
  @Test fun unicodeAndActionBounds() {
    assertTrue(Protocol.validText("🙂é"))
    assertFalse(Protocol.validText("\uD800"))
    assertFalse(Protocol.validText("\u0000"))
    assertFalse(Protocol.validText("a".repeat(65537)))
    assertTrue(Protocol.validText("🙂",1024,true))
    assertFalse(Protocol.validText("a\n",1024,true))
    assertNull(Protocol.action(RemoteAction.VolumeStep(1)))
    assertNull(Protocol.action(RemoteAction.KeyboardKey("f1")))
    assertNull(Protocol.action(RemoteAction.PointerMove(513,0)))
    assertNull(Protocol.action(RemoteAction.PointerClick("right",2)))
    assertNotNull(Protocol.action(RemoteAction.ClipboardSet(id,"Olá 🙂",false,0)))
  }
  @Test fun nestedStateIsStrict() {
    val state="""{"v":1,"type":"state","revision":1,"audio":{"available":true,"percent":33,"muted":false,"error":null},"media":{"sessions":[]},"clipboardRevision":0,"sync":{"upload":false,"download":false,"paused":false},"input":{"allowed":true,"enabled":false}}"""
    assertEquals(33,(Protocol.parse(state) as Incoming.State).snapshot.audio.percent)
    assertNull(Protocol.parse(state.replace("\"percent\":33","\"percent\":\"33\"")))
    assertNull(Protocol.parse(state.replace("\"paused\":false","\"paused\":false,\"other\":1")))
    assertNull(Protocol.parse(state.replace("\"enabled\":false","\"enabled\":\"false\"")))
  }
  @Test fun clipboardDedupAndAuthoritativeRevision() {
    val events=ClipboardEvents()
    val first=Incoming.Clipboard(id,4,"Olá",true)
    assertTrue(events.accept(first,3))
    assertFalse(events.accept(first,3))
    assertFalse(events.accept(Incoming.Clipboard(UUID.randomUUID().toString(),2,"old",false),3))
    assertFalse(events.accept(Incoming.Clipboard(UUID.randomUUID().toString(),5,"stale automatic",true),6))
    assertTrue(events.accept(Incoming.Clipboard(UUID.randomUUID().toString(),6,"manual",false),7))
    events.clear()
    assertTrue(events.accept(first,0))
  }
  private val transfer = UUID.randomUUID().toString()
  private val ticket = "0123456789abcdef".repeat(4)

  @Test fun filesCapabilityAndStateAreOptionalAndStrict() {
    val welcome="""{"v":1,"type":"welcome","id":"$id","pcId":"$id","pcName":"PC","capabilities":["audio","input.control","file.transfer"]}"""
    assertEquals(true,(Protocol.parse(welcome) as Incoming.Welcome).files)
    assertEquals(false,(Protocol.parse(welcome.replace(",\"file.transfer\"","")) as Incoming.Welcome).files)
    assertNull(Protocol.parse(welcome.replace("file.transfer","file.upload")))
    val state="""{"v":1,"type":"state","revision":1,"audio":{"available":true,"percent":33,"muted":false,"error":null},"media":{"sessions":[]},"clipboardRevision":0,"sync":{"upload":false,"download":false,"paused":false},"files":{"allowed":true,"maxBytes":52428800}}"""
    assertEquals(FilesState(true,52428800),(Protocol.parse(state) as Incoming.State).snapshot.files)
    assertNull((Protocol.parse(state.replace(",\"files\":{\"allowed\":true,\"maxBytes\":52428800}","")) as Incoming.State).snapshot.files)
    assertNull(Protocol.parse(state.replace("\"maxBytes\":52428800","\"maxBytes\":52428800,\"extra\":1")))
    assertNull(Protocol.parse(state.replace("\"maxBytes\":52428800","\"maxBytes\":\"52428800\"")))
    assertNull(Protocol.parse(state.replace("\"maxBytes\":52428800","\"maxBytes\":0")))
    assertNull(Protocol.parse(state.replace("\"maxBytes\":52428800","\"maxBytes\":52428801")))
    assertNull(Protocol.parse(state.replace("\"allowed\":true","\"allowed\":1")))
    assertNull(Protocol.parse(state.replace(",\"maxBytes\":52428800","")))
  }

  @Test fun fileTicketIsStrict() {
    val good="""{"v":1,"type":"file.ticket","id":"$id","transferId":"$transfer","ticket":"$ticket","chunkBytes":65536,"windowBytes":1048576}"""
    assertEquals(Incoming.FileTicket(id,transfer,ticket,65536,1048576),Protocol.parse(good))
    assertNull(Protocol.parse(good.replace(ticket,ticket.uppercase())))
    assertNull(Protocol.parse(good.replace(ticket,ticket.drop(2))))
    assertNull(Protocol.parse(good.replace("\"chunkBytes\":65536","\"chunkBytes\":65537")))
    assertNull(Protocol.parse(good.replace("\"chunkBytes\":65536","\"chunkBytes\":0")))
    assertNull(Protocol.parse(good.replace("\"windowBytes\":1048576","\"windowBytes\":1024")))
    assertNull(Protocol.parse(good.replace("\"windowBytes\":1048576","\"windowBytes\":1048576.5")))
    assertNull(Protocol.parse(good.replace("\"transferId\":\"$transfer\"","\"transferId\":\"x\"")))
    assertNull(Protocol.parse(good.dropLast(1)+",\"extra\":true}"))
  }

  @Test fun newErrorCodesAreAccepted() {
    for (code in listOf("file_type","file_busy","file_integrity","storage")) {
      val error="""{"v":1,"type":"error","id":"$id","code":"$code","message":"Falha","retryable":true}"""
      assertEquals(code,(Protocol.parse(error) as Incoming.Error).code)
      assertEquals(code,(Protocol.parseFile(error) as FileMessage.Error).code)
    }
    assertNull(Protocol.parseFile("""{"v":1,"type":"error","id":"$id","code":"disk_full","message":"Falha","retryable":true}"""))
  }

  @Test fun fileSocketMessagesAreStrict() {
    val accept="""{"v":1,"type":"file.accept","id":"$id","transferId":"$transfer"}"""
    assertEquals(FileMessage.Accept(id,transfer),Protocol.parseFile(accept))
    assertNull(Protocol.parseFile(accept.dropLast(1)+",\"extra\":1}"))
    val progress="""{"v":1,"type":"file.progress","transferId":"$transfer","received":262144}"""
    assertEquals(FileMessage.Progress(transfer,262144),Protocol.parseFile(progress))
    assertNull(Protocol.parseFile(progress.replace("262144","-1")))
    assertNull(Protocol.parseFile(progress.replace("262144","1.5")))
    assertNull(Protocol.parseFile(progress.replace("262144","\"262144\"")))
    assertNull(Protocol.parseFile(progress.replace("262144","52428801")))
    val done="""{"v":1,"type":"file.done","id":"$id","transferId":"$transfer","name":"foto (1).jpg"}"""
    assertEquals(FileMessage.Done(id,transfer,"foto (1).jpg"),Protocol.parseFile(done))
    assertNull(Protocol.parseFile(done.replace("foto (1).jpg","")))
    assertNull(Protocol.parseFile(done.replace("foto (1).jpg","a\\u0007b.jpg")))
    // Control messages never belong on /v1/files, and file messages never on /v1/ws.
    assertNull(Protocol.parseFile("""{"v":1,"type":"ack","id":"$id"}"""))
    assertNull(Protocol.parse(accept))
    assertNull(Protocol.parseFile(accept.replace("\"v\":1","\"v\":2")))
    assertNull(Protocol.parseFile(done.replace("foto (1).jpg","a".repeat(Protocol.MAX_FILE_FRAME))))
  }

  @Test fun fileActionsAndFileSocketFramesFollowTheContract() {
    val offer=Protocol.action(RemoteAction.FileOffer(transfer,"IMG_0001.jpg",123456,"image/jpeg"))!!
    assertEquals(setOf("kind","transferId","name","size","mime"),offer.keys().asSequence().toSet())
    assertEquals("file.offer",offer.getString("kind")); assertEquals(123456L,offer.getLong("size"))
    assertNull(Protocol.action(RemoteAction.FileOffer("x","a.jpg",1,"image/jpeg")))
    assertNull(Protocol.action(RemoteAction.FileOffer(transfer,"a.jpg",0,"image/jpeg")))
    assertNull(Protocol.action(RemoteAction.FileOffer(transfer,"a.jpg",FileRules.MAX_BYTES+1,"image/jpeg")))
    assertNull(Protocol.action(RemoteAction.FileOffer(transfer,"a.png",1,"image/jpeg")))
    assertNull(Protocol.action(RemoteAction.FileOffer(transfer,"a.gif",1,"image/gif")))
    assertEquals(setOf("kind","transferId"),Protocol.action(RemoteAction.FileCancel(transfer))!!.keys().asSequence().toSet())
    assertNull(Protocol.action(RemoteAction.FileCancel("nope")))
    val begin=JSONObject(Protocol.fileBegin(id,transfer,ticket)!!)
    assertEquals(setOf("v","type","id","transferId","ticket"),begin.keys().asSequence().toSet())
    assertNull(Protocol.fileBegin(id,transfer,ticket.uppercase()))
    val end=JSONObject(Protocol.fileEnd(id,transfer,ticket)!!)
    assertEquals(setOf("v","type","id","transferId","sha256"),end.keys().asSequence().toSet())
    assertNull(Protocol.fileEnd(id,"x",ticket))
  }

  /** The examples file is shared with pc-remote-desktop; the Android side must read every server frame. */
  @Test fun sharedGoldenExamples() {
    var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
    while (dir != null && !File(dir,"docs/protocol-v1.examples.json").isFile) dir = dir.parentFile
    val examples=JSONObject(File(dir!!,"docs/protocol-v1.examples.json").readText())
    val server=examples.getJSONArray("server")
    for (i in 0 until server.length()) assertNotNull(server.get(i).toString(),Protocol.parse(server.get(i).toString()))
    val files=examples.getJSONObject("fileSocket")
    val fileServer=files.getJSONArray("server")
    for (i in 0 until fileServer.length()) assertNotNull(fileServer.get(i).toString(),Protocol.parseFile(fileServer.get(i).toString()))
    val requests=examples.getJSONArray("client")
    val offer=(0 until requests.length()).mapNotNull { requests.getJSONObject(it).optJSONObject("action") }.single { it.getString("kind")=="file.offer" }
    val built=Protocol.action(RemoteAction.FileOffer(offer.getString("transferId"),offer.getString("name"),offer.getLong("size"),offer.getString("mime")))!!
    assertEquals(offer.toString().length,built.toString().length)
    for (key in offer.keys()) assertEquals(key,offer.get(key).toString(),built.get(key).toString())
    val invalid=examples.getJSONArray("invalidClient")
    for (i in 0 until invalid.length()) {
      val a=invalid.getJSONObject(i).getJSONObject("action")
      // Offers that are invalid only through unknown fields are the PC's to reject; the builder cannot emit them.
      if (a.getString("kind")=="file.offer" && a.length()==5) assertNull(a.toString(),Protocol.action(RemoteAction.FileOffer(a.getString("transferId"),a.getString("name"),a.getLong("size"),a.getString("mime"))))
    }
    val begin=files.getJSONArray("client").getJSONObject(0)
    assertEquals(begin.toString().length,Protocol.fileBegin(begin.getString("id"),begin.getString("transferId"),begin.getString("ticket"))!!.length)
    val end=files.getJSONArray("client").getJSONObject(1)
    assertEquals(end.toString().length,Protocol.fileEnd(end.getString("id"),end.getString("transferId"),end.getString("sha256"))!!.length)
  }

  @Test fun pairingQrMatchesDesktopContract() {
    val invite="""{"v":1,"host":"192.168.1.2","port":43127,"pcId":"$id","fingerprint":"${"a".repeat(64)}","code":"12345678"}"""
    assertEquals("192.168.1.2",Protocol.parseInvite(invite)?.host)
    assertNull(Protocol.parseInvite(invite.replace("\"port\":43127","\"port\":\"43127\"")))
    assertNull(Protocol.parseInvite(invite.replace("\"v\":1","\"v\":1.5")))
    assertNull(Protocol.parseInvite(invite.replace("\"pcId\":\"$id\",","")))
    assertNull(Protocol.parseInvite(invite.dropLast(1)+",\"extra\":true}"))
  }
}
