package com.pcremote

import org.junit.Assert.*
import org.junit.Test
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
  @Test fun pairingQrMatchesDesktopContract() {
    val invite="""{"v":1,"host":"192.168.1.2","port":43127,"pcId":"$id","fingerprint":"${"a".repeat(64)}","code":"12345678"}"""
    assertEquals("192.168.1.2",Protocol.parseInvite(invite)?.host)
    assertNull(Protocol.parseInvite(invite.replace("\"port\":43127","\"port\":\"43127\"")))
    assertNull(Protocol.parseInvite(invite.replace("\"v\":1","\"v\":1.5")))
    assertNull(Protocol.parseInvite(invite.replace("\"pcId\":\"$id\",","")))
    assertNull(Protocol.parseInvite(invite.dropLast(1)+",\"extra\":true}"))
  }
}
