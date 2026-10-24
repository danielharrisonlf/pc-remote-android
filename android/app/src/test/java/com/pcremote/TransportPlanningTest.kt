package com.pcremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportPlanningTest {
  private fun saved(host: String = "192.168.1.10", port: Int = 43127, pcId: String = "pc-a") =
    PairedComputer(host,port,"a".repeat(64),"phone","Phone",pcId,"PC A","secret")

  @Test fun reconnectUsesOnlyMatchingPcAddressesThenKeepsSavedEndpointFallback() {
    val plan=connectionPlan(saved(),listOf(
      DiscoveredComputer("Other PC","192.168.1.90",43127,"pc-b",addresses=listOf("192.168.1.90")),
      DiscoveredComputer("PC A","192.168.1.20",43128,"pc-a",addresses=listOf("192.168.1.20","192.168.1.21","192.168.1.20")),
    ))

    assertEquals(listOf(
      EndpointGroup(43128,listOf("192.168.1.20","192.168.1.21")),
      EndpointGroup(43127,listOf("192.168.1.10")),
    ),plan)
    assertTrue(plan.flatMap { it.hosts }.none { it == "192.168.1.90" })
  }

  @Test fun selectedCodePairingIsIsolatedAndRetainsExplicitDnsHostname() {
    val selected=DiscoveredComputer("Selected","pc-a.local",43127,"pc-a",addresses=listOf(
      "192.168.1.20","192.168.1.20","other.example","192.168.1.21",
    ))
    val plan=pairingConnectionPlan(selected)

    assertEquals(listOf(EndpointGroup(43127,listOf("pc-a.local","192.168.1.20","192.168.1.21"))),plan)
    assertFalse(plan.flatMap { it.hosts }.contains("192.168.1.10"))
  }

  @Test fun discoveredIpv4CandidatesAreDeduplicatedAndCappedAtSixteen() {
    val addresses=(1..20).map { "10.0.0.$it" } + "10.0.0.1"
    val plan=pairingConnectionPlan(DiscoveredComputer("PC","10.0.0.1",43127,"pc-a",addresses=addresses))

    assertEquals(1,plan.size)
    assertEquals(16,plan.single().hosts.size)
    assertEquals("10.0.0.1",plan.single().hosts.first())
    assertEquals(16,plan.single().hosts.distinct().size)
    assertTrue(plan.single().hosts.all(::isIpv4Literal))
  }

  @Test fun preOpenTransportFailureAdvancesButOpenAndStaleCallbacksCannotFallback() {
    val progress=TransportPlanProgress(listOf(EndpointGroup(43127,listOf("192.168.1.10")),EndpointGroup(43128,listOf("192.168.1.20"))))
    val first=progress.current()!!

    val second=progress.advanceAfterPreOpenFailure(first.id)
    assertNotNull(second)
    assertEquals(1,second!!.id)
    assertFalse(progress.markOpened(first.id)) // A late open from the retired attempt is fenced.
    assertNull(progress.advanceAfterPreOpenFailure(first.id))
    assertTrue(progress.markOpened(second.id))
    assertNull(progress.advanceAfterPreOpenFailure(second.id)) // After onOpen, never replay hello/pair.request elsewhere.
    assertTrue(progress.hasOpened(second.id))
  }

  @Test fun pinnedIdentityAndCredentialsDoNotChangeWhileBuildingCandidates() {
    val saved=saved()
    val selected=connectionPlan(saved,listOf(DiscoveredComputer("PC A","192.168.1.20",43127,"pc-a",addresses=listOf("192.168.1.20","192.168.1.21"))))

    assertEquals("a".repeat(64),saved.fingerprint)
    assertEquals("secret",saved.token)
    assertEquals("phone",saved.deviceId)
    assertEquals(1,selected.size)
    assertEquals(listOf("192.168.1.20","192.168.1.21","192.168.1.10"),selected.single().hosts)
  }
}
