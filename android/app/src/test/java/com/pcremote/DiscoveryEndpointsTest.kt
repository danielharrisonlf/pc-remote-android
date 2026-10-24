package com.pcremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException

class DiscoveryEndpointsTest {
  @Test fun changedAddressKeepsPinnedIdentityUntilWelcome() {
    val saved = PairedComputer("192.168.1.10",43127,"a".repeat(64),"phone","Phone","pc-2","Work PC","secret")
    val found = listOf(
      DiscoveredComputer("Other","192.168.1.20",43127,"pc-1"),
      DiscoveredComputer("Work PC","192.168.1.30",43128,"pc-2")
    )
    val selected = discoveredEndpoint(saved,found)
    assertEquals("192.168.1.30", selected.host)
    assertEquals(43128, selected.port)
    assertEquals(saved.fingerprint, selected.fingerprint)
    assertEquals(saved.token, selected.token)
    assertEquals(saved.deviceId, selected.deviceId)
    assertEquals(saved, discoveredEndpoint(saved,found.take(1)))
  }

  @Test fun failedResolutionAdvancesToNextAndThenSuccess() {
    val queue = DiscoveryResolver<String>()
    queue.start()
    queue.found("one","first")
    queue.found("two","second")
    val first = queue.next()!!
    assertNull(queue.next())
    assertEquals("first",first.service)
    assertTrue(queue.complete(first)) // NSD onResolveFailed
    val second = queue.next()!!
    assertEquals("second",second.service)
    assertTrue(queue.complete(second)) // NSD onServiceResolved
    assertNull(queue.next())
  }

  @Test fun retiredScanKeepsNativeSlotUntilItsRealCallback() {
    val queue = DiscoveryResolver<String>()
    queue.start()
    queue.found("old","old address")
    val stale=queue.next()!!
    queue.stop()
    queue.start()
    queue.found("new","new address")
    assertNull(queue.next())
    assertFalse(queue.isValid(stale))
    assertTrue(queue.complete(stale))
    val current=queue.next()!!
    assertEquals("new address",current.service)
    assertFalse(queue.complete(stale))
    assertTrue(queue.isValid(current))
  }

  @Test fun sameServiceNameFromNewScanWaitsForOldCallback() {
    val queue=DiscoveryResolver<String>()
    queue.start()
    queue.found("pc","old address")
    val old=queue.next()!!
    queue.stop()
    queue.start()
    queue.found("pc","new address")
    assertNull(queue.next())
    assertTrue(queue.complete(old))
    assertEquals("new address",queue.next()!!.service)
  }

  @Test fun lostServiceCannotPublishItsLateResolution() {
    val queue=DiscoveryResolver<String>()
    queue.start()
    queue.found("pc","192.168.1.2")
    val ticket=queue.next()!!
    queue.lost("pc")
    assertFalse(queue.isValid(ticket))
    assertTrue(queue.complete(ticket))
  }

  @Test fun rediscoveredServiceWaitsForOldResolutionToRetire() {
    val queue=DiscoveryResolver<String>()
    queue.start()
    queue.found("pc","old address")
    val old=queue.next()!!
    queue.lost("pc")
    queue.found("pc","new address")
    assertFalse(queue.isValid(old))
    assertTrue(queue.complete(old))
    assertEquals("new address",queue.next()!!.service)
  }

  @Test fun duplicateCallbackCannotFinishTheNextResolution() {
    val queue=DiscoveryResolver<String>()
    queue.start()
    queue.found("one","first")
    queue.found("two","second")
    val finished=queue.next()!!
    assertTrue(queue.complete(finished))
    val next=queue.next()!!
    assertFalse(queue.complete(finished))
    assertTrue(queue.isValid(next))
    assertEquals("second",next.service)
  }

  @Test fun busyResolutionRetriesAreBounded() {
    val queue=DiscoveryResolver<String>()
    queue.start()
    queue.found("pc","address")
    repeat(5) {
      val ticket=queue.next()!!
      assertTrue(queue.complete(ticket))
      assertTrue(queue.retryBusy(ticket))
    }
    val last=queue.next()!!
    assertTrue(queue.complete(last))
    assertFalse(queue.retryBusy(last))
    assertNull(queue.next())
  }

  @Test fun queueIsBoundedAndLostServicesAreRemoved() {
    val queue = DiscoveryResolver<Int>()
    queue.start()
    for(n in 0..50) queue.found("pc-$n",n)
    queue.lost("pc-1")
    assertEquals(2,queue.next()!!.service)
  }

  @Test fun connectionErrorsGiveDistinctSafeNetworkGuidance() {
    assertTrue(connectionFailure(SocketTimeoutException()).contains("não respondeu a tempo"))
    assertTrue(connectionFailure(ConnectException()).contains("recusou"))
    assertTrue(connectionFailure(SSLHandshakeException("hidden").apply { initCause(CertificateException("fingerprint")) }).contains("identidade"))
    assertFalse(connectionFailure(ConnectException("private address" )).contains("private address"))
  }
}
