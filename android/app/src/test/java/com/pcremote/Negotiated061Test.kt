package com.pcremote

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** power.v1 and clipboard.v2 (apps 0.6.1) against the shared examples and the strict parser. */
class Negotiated061Test {
  private val id = UUID.randomUUID().toString()
  private val transfer = UUID.randomUUID().toString()
  private val ticket = "c0ffee00".repeat(8)

  private fun examples(): JSONObject {
    var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
    while (dir != null && !File(dir, "docs/protocol-v1.examples.json").isFile) dir = dir.parentFile
    return JSONObject(File(dir!!, "docs/protocol-v1.examples.json").readText()).getJSONObject("negotiated061")
  }

  private fun keys(o: JSONObject) = o.keys().asSequence().toSet()

  /** Builds the phone's frame from an example, or null when the builder cannot express it (missing or extra fields). */
  private fun build(a: JSONObject): JSONObject? = when (a.getString("kind")) {
    "power.run" -> if (keys(a) != setOf("kind", "action", "delaySeconds")) null else {
      val delay = a.get("delaySeconds")
      if (delay !is Int) null else Protocol.action(RemoteAction.PowerRun(a.getString("action"), delay))
    }
    "power.cancel" -> if (keys(a) != setOf("kind")) null else Protocol.action(RemoteAction.PowerCancel)
    "file.offer" -> {
      val c = a.getJSONObject("clipboard")
      if (keys(a) != setOf("kind", "transferId", "name", "size", "mime", "clipboard") || keys(c) != setOf("eventId", "automatic", "baseRevision")) null
      else Protocol.action(RemoteAction.FileOffer(a.getString("transferId"), a.getString("name"), a.getLong("size"), a.getString("mime"), twoWay = true,
        clipboard = ClipOffer(c.getString("eventId"), c.getBoolean("automatic"), c.getLong("baseRevision"))))
    }
    else -> fail(a.toString()).let { null }
  }

  @Test fun goldenExamples() {
    val section = examples()
    val headers = section.getJSONArray("headers")
    assertEquals(listOf("input.v1", "files.v1", "files.v2", "search.v1", "info.v1", "power.v1", "clipboard.v2"), (0 until headers.length()).map { headers.getString(it) })
    val server = section.getJSONArray("server")
    for (i in 0 until server.length()) assertNotNull(server.get(i).toString(), Protocol.parse(server.get(i).toString()))
    val welcome = Protocol.parse(server.get(0).toString()) as Incoming.Welcome
    assertTrue(welcome.power && welcome.clipboardImage && welcome.fileReceive)
    val scheduled = (Protocol.parse(server.get(1).toString()) as Incoming.State).snapshot.power!!
    assertEquals(PowerState(true, PowerSchedule("shutdown", 1740)), scheduled)
    assertEquals(PowerState(false, null), (Protocol.parse(server.get(2).toString()) as Incoming.State).snapshot.power)
    assertEquals(ClipPush(8, true), (Protocol.parse(server.get(3).toString()) as Incoming.FilePush).clipboard)
    val client = section.getJSONArray("client")
    for (i in 0 until client.length()) {
      val a = client.getJSONObject(i).getJSONObject("action")
      val built = build(a)
      assertNotNull(a.toString(), built)
      assertEquals(a.toString(), JSONObject(a.toString()).toString().length, built.toString().length)
      assertEquals(keys(a), keys(built!!))
      for (key in keys(a)) assertEquals(key, a.get(key).toString(), built.get(key).toString())
    }
    val invalid = section.getJSONArray("invalidClient")
    for (i in 0 until invalid.length()) {
      val a = invalid.getJSONObject(i).getJSONObject("action")
      // Shapes the builder cannot even express (missing or extra fields) count as refused.
      assertNull(a.toString(), build(a))
    }
    val fromPc = section.getJSONObject("fileSocket").getJSONArray("server")
    for (i in 0 until fromPc.length()) assertNotNull(fromPc.get(i).toString(), Protocol.parseFile(fromPc.get(i).toString()))
  }

  @Test fun powerStateIsStrict() {
    val base = """{"v":1,"type":"state","revision":1,"audio":{"available":true,"percent":35,"muted":false,"error":null},"media":{"sessions":[]},"clipboardRevision":0,"sync":{"upload":true,"download":true,"paused":false},"power":POWER}"""
    fun parse(power: String) = (Protocol.parse(base.replace("POWER", power)) as? Incoming.State)?.snapshot?.power
    assertEquals(PowerState(true, null), parse("""{"allowed":true,"scheduled":null}"""))
    assertEquals(PowerState(true, PowerSchedule("sleep", 86400)), parse("""{"allowed":true,"scheduled":{"action":"sleep","remainingSeconds":86400}}"""))
    for (bad in listOf(
      """{"allowed":true}""",
      """{"allowed":"true","scheduled":null}""",
      """{"allowed":true,"scheduled":null,"extra":1}""",
      """{"allowed":true,"scheduled":{"action":"hibernate","remainingSeconds":10}}""",
      """{"allowed":true,"scheduled":{"action":"shutdown","remainingSeconds":0}}""",
      """{"allowed":true,"scheduled":{"action":"shutdown","remainingSeconds":86401}}""",
      """{"allowed":true,"scheduled":{"action":"shutdown","remainingSeconds":1.5}}""",
      """{"allowed":true,"scheduled":{"action":"shutdown"}}""",
      """{"allowed":true,"scheduled":{"action":"shutdown","remainingSeconds":60,"by":"x"}}""",
      """{"allowed":true,"scheduled":"shutdown"}""",
    )) assertNull(bad, Protocol.parse(base.replace("POWER", bad)))
    // Without power.v1 the field never comes, and the snapshot is the 0.6 one.
    assertNull((Protocol.parse(base.replace(",\"power\":POWER", "")) as Incoming.State).snapshot.power)
  }

  @Test fun welcomeAndPushStayStrict() {
    val welcome = """{"v":1,"type":"welcome","id":"$id","pcId":"$id","pcName":"PC","capabilities":["audio","file.transfer","file.receive","power.control","clipboard.image"]}"""
    val w = Protocol.parse(welcome) as Incoming.Welcome
    assertTrue(w.power && w.clipboardImage)
    assertFalse((Protocol.parse(welcome.replace(",\"power.control\",\"clipboard.image\"", "")) as Incoming.Welcome).power)
    assertNull(Protocol.parse(welcome.replace("power.control", "power.v2")))
    val push = """{"v":1,"type":"file.push","transferId":"$transfer","name":"Imagem do PC.png","size":1024,"mime":"image/png","ticket":"$ticket","clipboard":{"revision":3,"automatic":false}}"""
    assertEquals(ClipPush(3, false), (Protocol.parse(push) as Incoming.FilePush).clipboard)
    assertNull((Protocol.parse(push.replace(",\"clipboard\":{\"revision\":3,\"automatic\":false}", "")) as Incoming.FilePush).clipboard)
    assertNull(Protocol.parse(push.replace("\"automatic\":false", "\"automatic\":false,\"text\":\"x\"")))
    assertNull(Protocol.parse(push.replace("\"revision\":3", "\"revision\":-1")))
    assertNull(Protocol.parse(push.replace("\"automatic\":false", "\"automatic\":\"no\"")))
    assertNull(Protocol.parse(push.replace("\"clipboard\":{\"revision\":3,\"automatic\":false}", "\"clipboard\":true")))
  }

  @Test fun powerBuilders() {
    assertNotNull(Protocol.action(RemoteAction.PowerRun("lock", 0)))
    assertNull(Protocol.action(RemoteAction.PowerRun("lock", 900)))
    assertNotNull(Protocol.action(RemoteAction.PowerRun("restart", 86400)))
    assertNull(Protocol.action(RemoteAction.PowerRun("restart", 86401)))
    assertNull(Protocol.action(RemoteAction.PowerRun("sleep", -1)))
    assertNull(Protocol.action(RemoteAction.PowerRun("Shutdown", 0)))
    assertEquals(setOf("kind"), keys(Protocol.action(RemoteAction.PowerCancel)!!))
    // A clipboard offer is a PNG or JPEG of up to 32 MiB with a UUID event id.
    fun clip(mime: String, size: Long, event: String = id, base: Long = 0) =
      Protocol.action(RemoteAction.FileOffer(transfer, "Imagem.png", size, mime, twoWay = true, clipboard = ClipOffer(event, true, base)))
    assertNotNull(clip("image/jpeg", ClipImages.MAX_BYTES))
    assertNull(clip("image/png", ClipImages.MAX_BYTES + 1))
    assertNull(clip("image/webp", 10))
    assertNull(clip("image/png", 0))
    assertNull(clip("image/png", 10, event = "x"))
    assertNull(clip("image/png", 10, base = -1))
  }
}
