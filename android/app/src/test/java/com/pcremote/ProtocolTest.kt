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
    assertEquals(FileRules.MAX_BYTES,(Protocol.parse(state.replace("\"maxBytes\":52428800","\"maxBytes\":1073741824")) as Incoming.State).snapshot.files!!.maxBytes)
    assertNull(Protocol.parse(state.replace("\"maxBytes\":52428800","\"maxBytes\":1073741825")))
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
    assertNotNull(Protocol.parseFile(progress.replace("262144","1073741824")))
    assertNull(Protocol.parseFile(progress.replace("262144","1073741825")))
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
  @Test fun negotiated06WelcomeAndPushAreStrict() {
    val welcome="""{"v":1,"type":"welcome","id":"$id","pcId":"$id","pcName":"PC","capabilities":["audio","file.transfer","file.receive","search.youtube"],"os":"Windows 11"}"""
    val parsed=Protocol.parse(welcome) as Incoming.Welcome
    assertTrue(parsed.files && parsed.fileReceive && parsed.search)
    assertEquals("Windows 11",parsed.os)
    assertEquals("",(Protocol.parse(welcome.replace(",\"os\":\"Windows 11\"","")) as Incoming.Welcome).os)
    assertNull(Protocol.parse(welcome.replace("Windows 11","")))
    assertNull(Protocol.parse(welcome.replace("Windows 11","a\u0007b")))
    assertNull(Protocol.parse(welcome.replace("Windows 11","x".repeat(65))))
    assertNull(Protocol.parse(welcome.replace("\"os\"","\"system\"")))
    val push="""{"v":1,"type":"file.push","transferId":"$transfer","name":"Relatório.pdf","size":123,"mime":"application/pdf","ticket":"$ticket"}"""
    assertEquals(Incoming.FilePush(transfer,"Relatório.pdf",123,"application/pdf",ticket),Protocol.parse(push))
    assertNull(Protocol.parse(push.replace(ticket,ticket.uppercase())))
    assertNull(Protocol.parse(push.replace("\"size\":123","\"size\":0")))
    assertNull(Protocol.parse(push.replace("\"size\":123","\"size\":1073741825")))
    assertNull(Protocol.parse(push.replace("application/pdf","Application/PDF")))
    assertNull(Protocol.parse(push.replace("Relatório.pdf","")))
    assertNull(Protocol.parse(push.replace("Relatório.pdf","a\nb.pdf")))
    assertNull(Protocol.parse(push.dropLast(1)+",\"path\":\"C:\\\\\"}"))
    // file.push is a control event; file.end belongs only to /v1/files.
    assertNull(Protocol.parseFile(push))
    val end="""{"v":1,"type":"file.end","id":"$id","transferId":"$transfer","sha256":"$ticket"}"""
    assertEquals(FileMessage.End(id,transfer,ticket),Protocol.parseFile(end))
    assertNull(Protocol.parseFile(end.replace(ticket,ticket.drop(1))))
    assertNull(Protocol.parse(end))
  }

  @Test fun negotiated06BuildersFollowTheContract() {
    assertNotNull(Protocol.action(RemoteAction.FileOffer(transfer,"Viagem 2026.mp4",734003200,"video/mp4",twoWay=true)))
    assertNull(Protocol.action(RemoteAction.FileOffer(transfer,"Viagem 2026.mp4",734003200,"video/mp4")))
    val decline=Protocol.action(RemoteAction.FileDecline(transfer,"storage"))!!
    assertEquals(setOf("kind","transferId","code"),decline.keys().asSequence().toSet())
    assertNull(Protocol.action(RemoteAction.FileDecline(transfer,"busy")))
    val search=Protocol.action(RemoteAction.SearchOpen("vídeos de Node.js"))!!
    assertEquals(setOf("kind","service","query"),search.keys().asSequence().toSet())
    assertEquals("youtube",search.getString("service"))
    assertNotNull(Protocol.action(RemoteAction.SearchOpen("🙂".repeat(200))))
    for (bad in listOf("","   ","x".repeat(201),"linha\nquebrada","tab\tx")) assertNull(bad,Protocol.action(RemoteAction.SearchOpen(bad)))
    assertNull(Protocol.fileFetch(id,transfer,ticket.uppercase()))
    assertNull(Protocol.fileProgress(transfer,-1))
    assertNull(Protocol.fileProgress(transfer,FileRules.MAX_BYTES+1))
    assertNull(Protocol.fileDone(id,transfer,""))
    assertNull(Protocol.fileError(id,"disk_full","x",true))
    assertEquals(setOf("v","type","id","code","message","retryable"),JSONObject(Protocol.fileError("00000000-0000-0000-0000-000000000000","storage","Sem espaço",true)!!).keys().asSequence().toSet())
  }

  /** negotiated06 in the shared examples: every PC frame parses and every phone frame is what the builders emit. */
  @Test fun negotiated06GoldenExamples() {
    var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
    while (dir != null && !File(dir,"docs/protocol-v1.examples.json").isFile) dir = dir.parentFile
    val examples=JSONObject(File(dir!!,"docs/protocol-v1.examples.json").readText()).getJSONObject("negotiated06")
    val headers=examples.getJSONArray("headers")
    assertEquals((0 until headers.length()).map { headers.getString(it) },listOf("input.v1","files.v1","files.v2","search.v1","info.v1"))
    val server=examples.getJSONArray("server")
    for (i in 0 until server.length()) assertNotNull(server.get(i).toString(),Protocol.parse(server.get(i).toString()))
    fun same(expected: JSONObject, built: JSONObject) {
      assertEquals(expected.keys().asSequence().toSet(),built.keys().asSequence().toSet())
      for (key in expected.keys()) assertEquals(key,expected.get(key).toString(),built.get(key).toString())
    }
    val client=examples.getJSONArray("client")
    for (i in 0 until client.length()) {
      val a=client.getJSONObject(i).getJSONObject("action")
      val built=when (a.getString("kind")) {
        "file.offer" -> Protocol.action(RemoteAction.FileOffer(a.getString("transferId"),a.getString("name"),a.getLong("size"),a.getString("mime"),twoWay=true))
        "file.decline" -> Protocol.action(RemoteAction.FileDecline(a.getString("transferId"),a.getString("code")))
        "search.open" -> Protocol.action(RemoteAction.SearchOpen(a.getString("query")))
        else -> null
      }
      assertNotNull(a.toString(),built)
      same(a,built!!)
    }
    val invalid=examples.getJSONArray("invalidClient")
    for (i in 0 until invalid.length()) {
      val a=invalid.getJSONObject(i).getJSONObject("action")
      val built=when (a.getString("kind")) {
        "file.offer" -> Protocol.action(RemoteAction.FileOffer(a.getString("transferId"),a.getString("name"),a.getLong("size"),a.getString("mime"),twoWay=true))
        "file.decline" -> Protocol.action(RemoteAction.FileDecline(a.getString("transferId"),a.getString("code")))
        // The builder only knows youtube and never adds other fields.
        "search.open" -> if (a.getString("service")!="youtube" || a.length()!=3) null else Protocol.action(RemoteAction.SearchOpen(a.getString("query")))
        else -> fail(a.toString())
      }
      assertNull(a.toString(),built)
    }
    val socket=examples.getJSONObject("fileSocket")
    val fromPc=socket.getJSONArray("server")
    for (i in 0 until fromPc.length()) assertNotNull(fromPc.get(i).toString(),Protocol.parseFile(fromPc.get(i).toString()))
    val fromPhone=socket.getJSONArray("client")
    for (i in 0 until fromPhone.length()) {
      val m=fromPhone.getJSONObject(i)
      val built=when (m.getString("type")) {
        "file.fetch" -> Protocol.fileFetch(m.getString("id"),m.getString("transferId"),m.getString("ticket"))
        "file.progress" -> Protocol.fileProgress(m.getString("transferId"),m.getLong("received"))
        "file.done" -> Protocol.fileDone(m.getString("id"),m.getString("transferId"),m.getString("name"))
        "error" -> Protocol.fileError(m.getString("id"),m.getString("code"),m.getString("message"),m.getBoolean("retryable"))
        else -> null
      }
      assertNotNull(m.toString(),built)
      same(m,JSONObject(built!!))
      assertEquals(m.toString().length,built.length)
    }
  }
}
