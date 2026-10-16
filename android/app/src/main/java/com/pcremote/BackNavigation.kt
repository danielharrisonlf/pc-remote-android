package com.pcremote

import android.annotation.TargetApi
import android.app.Activity
import android.os.Build
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/** Keep API 33 classes out of MainActivity's fields and method signatures on older Android. */
internal object BackNavigation {
  fun register(activity: Activity, onBack: () -> Unit): () -> Unit =
    if (Build.VERSION.SDK_INT >= 33) Api33.register(activity, onBack) else ({})

  @TargetApi(33)
  private object Api33 {
    fun register(activity: Activity, onBack: () -> Unit): () -> Unit {
      val callback = OnBackInvokedCallback { onBack() }
      activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
      return { activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback) }
    }
  }
}
