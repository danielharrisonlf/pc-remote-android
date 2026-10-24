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

  @Test fun failedResolutionRetriesAreBoundedAndRotateBehindOtherComputers() {
    val queue=DiscoveryResolver<String>()
    queue.start()
    queue.found("failing","address")
    queue.found("healthy","address 2")
    val first=queue.next(0)!!
    assertEquals("failing",first.name)
    assertTrue(queue.complete(first))
    assertTrue(queue.retryFailed(first,0))
    val healthy=queue.next(0)!!
    assertEquals("healthy",healthy.name)
    assertTrue(queue.complete(healthy))
    assertNull(queue.next(999))
    assertEquals(1_000L,queue.nextWakeAt(0))
    var retry=queue.next(1_000)!!
    assertEquals(1,retry.retries)
    assertTrue(queue.complete(retry))
    assertTrue(queue.retryFailed(retry,1_000))
    assertNull(queue.next(2_999))
    assertEquals(3_000L,queue.nextWakeAt(2_999))
    retry=queue.next(3_000)!!
    assertEquals(2,retry.retries)
    assertTrue(queue.complete(retry))
    assertTrue(queue.retryFailed(retry,3_000))
    assertNull(queue.next(6_999))
    assertEquals(7_000L,queue.nextWakeAt(6_999))
    retry=queue.next(7_000)!!
    assertEquals(3,retry.retries)
    assertTrue(queue.complete(retry))
    assertFalse(queue.retryFailed(retry,7_000))
    assertNull(queue.next(7_000))
  }

  @Test fun api34CallbackFailuresRetryBehindWaitingServicesAndAreFenced() {
    val queue=ServiceInfoQueue<String>()
    queue.start()
    queue.found("same-name-id-a","first")
    queue.found("same-name-id-b","second")
    val first=queue.next(0)!!
    assertEquals("same-name-id-a",first.key)
    assertTrue(queue.retryFailed(first,0))
    assertFalse(queue.isValid(first))
    val second=queue.next(0)!!
    assertEquals("same-name-id-b",second.key)
    assertTrue(queue.isValid(second))
    queue.found("third","third")
    assertEquals("third",queue.next(0)!!.key)
    assertNull(queue.next(999))
    assertEquals(1_000L,queue.nextWakeAt(999))
    val retry=queue.next(1_000)!!
    assertEquals(first.key,retry.key)
    assertEquals(1,retry.retries)
    assertFalse(queue.isValid(first))
    assertTrue(queue.isValid(retry))
  }

  @Test fun api34TemporaryLossKeepsWatcherWhileDefinitiveLossAndStopFenceIt() {
    val queue=ServiceInfoQueue<String>()
    queue.start()
    queue.found("instance","old")
    val old=queue.next(0)!!
    assertTrue(queue.temporarilyLost(old))
    assertTrue(queue.isValid(old)) // onServiceUpdated can restore this same registration
    assertTrue(queue.lost(old)) // DiscoveryListener.onServiceLost retires it definitively
    assertFalse(queue.isValid(old))
    queue.found("instance","new")
    val current=queue.next(0)!!
    assertEquals("new",current.service)
    assertFalse(queue.lost(old))
    assertTrue(queue.isValid(current))
    val retired=queue.stop()
    assertEquals(listOf(current),retired)
    queue.start()
    assertFalse(queue.isValid(current))
    queue.found("instance","after restart")
    assertEquals("after restart",queue.next(0)!!.service)
  }

  @Test fun api34CallbackRetriesStopAfterThreeRetriesAndQueueStaysBounded() {
    val queue=ServiceInfoQueue<String>(limit=2)
    queue.start()
    queue.found("one","one")
    queue.found("two","two")
    queue.found("three","three")
    val first=queue.next(0)!!
    assertEquals("two",first.key) // limit 2 evicts the oldest waiting service
    assertTrue(queue.retryFailed(first,0))
    val second=queue.next(0)!!
    assertEquals("three",second.key)
    val retry=queue.next(1_000)!!
    assertEquals("two",retry.key)
    assertEquals(1,retry.retries)
    assertTrue(queue.retryFailed(retry,1_000))
    assertNull(queue.next(2_999))
    val retry2=queue.next(3_000)!!
    assertEquals(2,retry2.retries)
    assertTrue(queue.retryFailed(retry2,3_000))
    assertNull(queue.next(6_999))
    val retry3=queue.next(7_000)!!
    assertEquals(3,retry3.retries)
    assertFalse(queue.retryFailed(retry3,7_000))
    assertNull(queue.next(7_000))
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

  @Test fun wrongPairingCodeAndExpiredCodeHaveDifferentMessages() {
    assertTrue(pairingFailureMessage("pairing_code_incorrect").contains("incorreto"))
    assertTrue(pairingFailureMessage("pairing_expired").contains("expirou"))
    assertFalse(pairingFailureMessage("pairing_code_incorrect") == pairingFailureMessage("pairing_expired"))
  }
}
