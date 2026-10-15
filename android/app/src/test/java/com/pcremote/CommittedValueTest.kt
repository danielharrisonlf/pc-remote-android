package com.pcremote

import org.junit.Assert.*
import org.junit.Test

class CommittedValueTest {
  @Test fun failedWriteCannotExposeCandidateAfterCacheWasMutated() {
    var backing: String? = "previous-encrypted-blob"
    var commits = 0
    val store = CommittedValue({ backing }) { value ->
      backing = value
      ++commits != 1
    }
    assertThrows(IllegalStateException::class.java) { store.write("candidate-encrypted-blob") }
    assertEquals("previous-encrypted-blob", backing)
    assertEquals("previous-encrypted-blob", store.read())
  }

  @Test fun failedFirstPairAndFailedRollbackStillHaveNoCredential() {
    var backing: String? = null
    val store = CommittedValue({ backing }) { value -> backing = value; false }
    assertThrows(IllegalStateException::class.java) { store.write("candidate-encrypted-blob") }
    backing = "candidate-encrypted-blob" // Another cache/disk observer cannot replace the authoritative value.
    assertNull(store.read())
  }

  @Test fun recoveryAfterDoubleFailureUsesOnlySuccessfullyCommittedValue() {
    var backing: String? = "old"
    var writable = false
    val store = CommittedValue({ backing }) { value -> backing = value; writable }
    assertThrows(IllegalStateException::class.java) { store.write("failed") }
    backing = "failed"
    assertEquals("old", store.read())
    writable = true
    store.write("confirmed")
    assertEquals("confirmed", store.read())
    store.write(null)
    assertNull(store.read())
  }

  @Test fun failedRemovalAndExceptionsPreservePreviousValue() {
    var backing: String? = "old"
    val store = CommittedValue({ backing }) { value ->
      backing = value
      throw SecurityException("storage unavailable")
    }
    assertThrows(SecurityException::class.java) { store.write(null) }
    assertEquals("old", store.read())
  }
}
