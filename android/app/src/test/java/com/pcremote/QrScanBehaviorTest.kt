package com.pcremote

import com.google.mlkit.common.MlKitException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrScanBehaviorTest {
  @Test
  fun gateAllowsOnlyOneScanAndCanBeReusedAfterCompletion() {
    val gate = QrScanGate()

    assertTrue(gate.begin())
    assertFalse(gate.begin())
    assertTrue(gate.finish())
    assertFalse(gate.finish())
    assertTrue(gate.begin())
  }

  @Test
  fun detachedGateSuppressesAnInFlightResultAndRejectsNewScans() {
    val gate = QrScanGate()

    assertTrue(gate.begin())
    gate.detach()

    assertFalse(gate.finish())
    assertFalse(gate.begin())
  }

  @Test
  fun scannerUnavailableMessageIsActionableAndDoesNotExposeExceptionText() {
    val privateDetail = "QR invite secret: example-token"
    val message = QrScanErrors.message(
      MlKitException(privateDetail, MlKitException.CODE_SCANNER_UNAVAILABLE),
    )

    assertTrue(message.contains("Conecte à internet"))
    assertTrue(message.contains("preencha os dados manualmente"))
    assertFalse(message.contains(privateDetail))
  }

  @Test
  fun unknownFailureUsesSafeManualPairingFallback() {
    val message = QrScanErrors.message(IllegalStateException("invite secret"))

    assertTrue(message.contains("Tente novamente"))
    assertTrue(message.contains("preencha os dados manualmente"))
    assertFalse(message.contains("invite secret"))
  }

  @Test
  fun cancellationIsRecognizedFromApiExceptionOrWrappedCause() {
    val apiEx = com.google.android.gms.common.api.ApiException(
      com.google.android.gms.common.api.Status(com.google.android.gms.common.api.CommonStatusCodes.CANCELED)
    )
    val message = QrScanErrors.message(apiEx)
    assertTrue(message.contains("Leitura cancelada"))

    val wrapped = RuntimeException("wrapper", apiEx)
    assertTrue(QrScanErrors.message(wrapped).contains("Leitura cancelada"))
  }
}
