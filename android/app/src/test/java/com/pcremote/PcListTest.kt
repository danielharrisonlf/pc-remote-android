package com.pcremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcListTest {
  private fun pc(n: Int, token: String = "t$n") = PairedComputer("192.168.0.$n", 43127, "f".repeat(63) + n, "device-$n", "Celular", "pc-$n", "PC $n", token)

  @Test fun migratesTheSinglePairingOfEarlierVersionsWithItsWindowsName() {
    val list = PcList.migrate(pc(1), "Windows 11 Pro", 5)
    assertEquals("pc-1", list.currentId)
    assertEquals(listOf(SavedComputer("pc-1", "PC 1", "Windows 11 Pro", true)), list.computers())
    assertTrue(PcList.migrate(null, "Windows 11", 5).entries.isEmpty())
  }

  @Test fun pairingAddsOrReplacesTheSamePcAndBecomesCurrent() {
    val two = PcList().upsert(pc(1), 1)!!.upsert(pc(2), 2)!!
    assertEquals("pc-2", two.currentId)
    val again = two.upsert(pc(1, token = "novo"), 3)!!
    assertEquals(2, again.entries.size)
    assertEquals("pc-1", again.currentId)
    assertEquals("novo", again.current!!.computer.token)
  }

  @Test fun keepsTheWindowsNameWhenTheSamePcPairsAgain() {
    val list = PcList().upsert(pc(1), 1)!!.touch("pc-1", "PC 1", "Windows 11", 2).upsert(pc(1, token = "novo"), 3)!!
    assertEquals("Windows 11", list.current!!.os)
  }

  @Test fun refusesANinthPcButAcceptsPairingASavedOneAgain() {
    var list = PcList()
    for (n in 1..PcList.MAX) list = list.upsert(pc(n), n.toLong())!!
    assertFalse(list.canAdd("pc-99"))
    assertNull(list.upsert(pc(99), 100))
    assertNotNull(list.upsert(pc(3, token = "outro"), 100))
  }

  @Test fun removingTheCurrentPcMovesToTheMostRecentlyUsed() {
    val list = PcList().upsert(pc(1), 10)!!.upsert(pc(2), 30)!!.upsert(pc(3), 20)!!.select("pc-2", 40)
    val next = list.remove("pc-2")
    assertEquals("pc-3", next.currentId)
    // Removing another PC keeps the current one.
    assertEquals("pc-3", next.remove("pc-1").currentId)
    assertEquals("", next.remove("pc-1").remove("pc-3").currentId)
  }

  @Test fun listsTheCurrentPcFirstThenByLastUse() {
    val list = PcList().upsert(pc(1), 10)!!.upsert(pc(2), 30)!!.upsert(pc(3), 20)!!.select("pc-1", 5)
    assertEquals(listOf("pc-1", "pc-2", "pc-3"), list.computers().map { it.id })
    assertEquals(listOf(true, false, false), list.computers().map { it.current })
  }

  @Test fun selectingAnUnknownPcChangesNothing() {
    val list = PcList().upsert(pc(1), 1)!!
    assertEquals(list, list.select("pc-9", 2))
  }

  @Test fun jsonRoundTripAndDefensiveRead() {
    val list = PcList().upsert(pc(1), 10)!!.upsert(pc(2), 20)!!.touch("pc-2", "Escritório", "Windows 10", 30)
    assertEquals(list, PcList.fromJson(list.toJson()))
    assertNull(PcList.fromJson("{}"))
    assertNull(PcList.fromJson("""{"v":2,"current":"","pcs":[]}"""))
    // A current id that is not in the list falls back to the most recently used PC.
    val broken = list.toJson().replace("\"current\":\"pc-2\"", "\"current\":\"pc-9\"")
    assertEquals("pc-2", PcList.fromJson(broken)!!.currentId)
  }

  @Test fun aRecordWithoutPcIdIsKeyedByItsPinnedIdentity() {
    val legacy = pc(1).copy(pcId = "")
    val list = PcList.migrate(legacy, "", 1)
    assertEquals("fp:" + legacy.fingerprint, list.currentId)
    assertEquals(legacy, list.current!!.computer)
  }
}
