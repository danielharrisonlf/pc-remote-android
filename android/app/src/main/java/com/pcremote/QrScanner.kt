package com.pcremote

import android.app.Activity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/** Activity-owned scanner: the application client never retains an Activity or scan callback. */
internal class QrScanner {
  private val gate = QrScanGate()
  private var callback: ((Invite?, String?) -> Unit)? = null

  fun start(activity: Activity, completion: (Invite?, String?) -> Unit) {
    if (!gate.begin()) return
    callback = completion
    try {
      if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(activity) != ConnectionResult.SUCCESS) {
        deliver(null, "Atualize ou ative o Google Play Services para ler o QR, ou use o código.")
        return
      }
      val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
      GmsBarcodeScanning.getClient(activity, options).startScan().addOnCompleteListener { task ->
        try {
          when {
            task.isCanceled -> deliver(null, "Leitura cancelada.")
            !task.isSuccessful -> deliver(null, QrScanErrors.message(task.exception))
            else -> {
              val invite = task.result.rawValue?.let(Protocol::parseInvite)
              deliver(invite, if (invite == null) "QR incompatível. Gere um novo no PC ou use o código." else null)
            }
          }
        } catch (error: Exception) {
          deliver(null, QrScanErrors.message(error))
        } catch (error: LinkageError) {
          deliver(null, QrScanErrors.message(error))
        }
      }
    } catch (error: Exception) {
      deliver(null, QrScanErrors.message(error))
    } catch (error: LinkageError) {
      // A missing or optimized library component must not terminate the whole app.
      deliver(null, QrScanErrors.message(error))
    }
  }

  fun detach() {
    gate.detach()
    callback = null
  }

  private fun deliver(invite: Invite?, problem: String?) {
    if (!gate.finish()) return
    val listener = callback
    callback = null
    listener?.invoke(invite, problem)
  }
}

/** Fixed messages avoid accidentally exposing a QR code or pairing credential in an exception. */
internal object QrScanErrors {
  fun message(error: Throwable?): String {
    val mlKit = (error as? MlKitException) ?: (error?.cause as? MlKitException)
    val api = (error as? com.google.android.gms.common.api.ApiException)
      ?: (error?.cause as? com.google.android.gms.common.api.ApiException)
    return when {
      mlKit != null && (mlKit.errorCode == MlKitException.CODE_SCANNER_CANCELLED || mlKit.errorCode == MlKitException.CANCELLED) ->
        "Leitura cancelada."
      api != null && api.statusCode == com.google.android.gms.common.api.CommonStatusCodes.CANCELED ->
        "Leitura cancelada."
      mlKit != null && mlKit.errorCode == MlKitException.CODE_SCANNER_UNAVAILABLE ->
        "Leitor de QR ainda indisponível. Conecte à internet e tente de novo em instantes, ou use o código."
      mlKit != null && mlKit.errorCode == MlKitException.CODE_SCANNER_GOOGLE_PLAY_SERVICES_VERSION_TOO_OLD ->
        "Atualize o Google Play Services para ler o QR, ou use o código."
      mlKit != null && mlKit.errorCode == MlKitException.CODE_SCANNER_CAMERA_PERMISSION_NOT_GRANTED ->
        "O leitor não acessou a câmera. Verifique as permissões do Google Play Services ou use o código."
      mlKit != null && mlKit.errorCode == MlKitException.CODE_SCANNER_TASK_IN_PROGRESS ->
        "Outra leitura já está aberta."
      else -> "Não foi possível abrir o leitor de QR. Tente novamente ou use o código."
    }
  }
}
