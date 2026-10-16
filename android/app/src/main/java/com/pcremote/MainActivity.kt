package com.pcremote

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi

/** The Activity owns Views only. Connection and command state live in RemoteClient. */
internal class MainActivity : Activity(), ScreenHost {
  private val remote: RemoteController get() = (application as RemoteApplication).client
  private val updates: UpdateManager get() = (application as RemoteApplication).updates
  private val mainHandler = Handler(Looper.getMainLooper())
  private val qrScanner = QrScanner()
  private lateinit var prefs: UiPreferences
  private lateinit var kit: Ui
  private lateinit var navigation: TabNavigation
  private var state = UiState()
  private var updateState = UpdateState()
  private var lastError = ""
  private var lastStatus = ""
  private var resumed = false
  private var fadingOut = false
  private var unsubscribe: (() -> Unit)? = null
  private var unsubscribeUpdates: (() -> Unit)? = null
  private var updateSheet: UpdateSheet? = null
  /** Sent to "Instalar apps desconhecidos"; the update continues on return. */
  private var awaitingInstallPermission = false
  /** Permission granted in a new process: install once the check finds the release again. */
  private var installAfterCheck = false
  private var unregisterBack: (() -> Unit)? = null
  private var pairingSheet: Sheet? = null
  private var pairingPanel: PairingPanel? = null

  private lateinit var root: FrameLayout
  private lateinit var column: LinearLayout
  private lateinit var topBar: LinearLayout
  private lateinit var title: TextView
  private lateinit var statusChip: StatusChip
  private lateinit var banner: LinearLayout
  private lateinit var bannerTitle: TextView
  private lateinit var bannerSubtitle: TextView
  private lateinit var navBar: NavigationBar
  private lateinit var snackbar: Snackbar
  override lateinit var panels: PanelLayer
  private lateinit var screens: Map<Tab, Screen>
  private lateinit var connection: ConnectionScreen

  override val activity: Activity get() = this
  override val ui: Ui get() = kit
  override val client: RemoteController get() = remote
  override val themeMode: ThemeMode get() = prefs.theme
  override val update: UpdateState get() = updateState

  private val volumeInteraction by lazy {
    VolumeInteraction(remote::beginVolume, remote::updateVolume, remote::endVolume,
      { task, delay -> mainHandler.postDelayed(task, delay); Unit },
      { task -> mainHandler.removeCallbacks(task); Unit })
  }

  /** Pings only while the Conexão tab is on screen; it stops by itself elsewhere. */
  private val latencyTask = object : Runnable {
    override fun run() {
      if (!resumed || navigation.tab != Tab.CONNECTION) return
      measureLatency()
      mainHandler.postDelayed(this, LATENCY_INTERVAL_MS)
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    prefs = UiPreferences(this)
    kit = Ui(this, Palette.resolve(this, prefs.theme))
    // Platform widgets (cursor, selection handles, radio buttons) follow the chosen theme too.
    setTheme(if (kit.palette.dark) R.style.Theme_PcRemote_Dark else R.style.Theme_PcRemote_Light)
    super.onCreate(savedInstanceState)
    state = remote.view
    lastStatus = state.status
    UpdateNotice.seen(this)
    updateState = UpdateState(lookup = lastLookup, installed = Releases.installed(installedVersionName()), skipped = prefs.skippedUpdate, flow = updates.flow)
    awaitingInstallPermission = savedInstanceState?.getBoolean(KEY_INSTALL_PERMISSION) == true
    val restored = savedInstanceState?.getString(KEY_TAB)?.let { runCatching { Tab.valueOf(it) }.getOrNull() }
    navigation = TabNavigation(restored ?: homeTab())
    // A recreated Activity or a launch from Recents carries an old share: it was already handled.
    val freshShare = savedInstanceState == null && intent?.let { isShare(it) && it.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0 } == true
    if (freshShare) navigation.goTo(Tab.CLIPBOARD)
    buildUi()
    if (freshShare) remote.receiveShare(intent)
    unsubscribe = remote.observe { next ->
      runOnUiThread {
        val changed = state.forDisplay() != next.forDisplay()
        state = next // Always keep the real revisions for actions and reconciliation.
        if (next.status != "Conectado" || next.snapshot?.audio?.available != true) volumeInteraction.cancel()
        if (changed) render()
      }
    }
    unsubscribeUpdates = updates.observe { flow, notice ->
      updateState = updateState.copy(flow = flow)
      render()
      updateSheet?.render(updateState)
      notice?.let { updateNotice(it, if (flow is UpdateFlow.Failed) Tone.DANGER else Tone.ACCENT) }
    }
    render()
    unregisterBack = BackNavigation.register(this) { navigateBack() }
    if (savedInstanceState?.getBoolean(KEY_FADE_IN) == true) {
      root.alpha = 0f
      root.animate().alpha(1f).setDuration(200).start()
    }
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putString(KEY_TAB, navigation.tab.name)
    outState.putBoolean(KEY_FADE_IN, fadingOut)
    outState.putBoolean(KEY_INSTALL_PERMISSION, awaitingInstallPermission)
  }

  override fun onResume() {
    super.onResume()
    resumed = true
    remote.foreground(this)
    updates.attach(this)
    restartLatency()
    if (awaitingInstallPermission) {
      awaitingInstallPermission = false
      when {
        !canInstallPackages() -> updateNotice("Sem essa autorização o app não instala a atualização. Tente de novo ou use Ver no GitHub.", Tone.DANGER)
        updateState.release != null -> startUpdate()
        else -> installAfterCheck = true
      }
    }
    val stale = System.currentTimeMillis() - prefs.lastUpdateCheck > UPDATE_INTERVAL_MS
    if (lastLookup == null || stale) checkUpdates(manual = false)
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    remote.focusChanged(hasFocus)
  }

  override fun onPause() {
    resumed = false
    mainHandler.removeCallbacks(latencyTask)
    volumeInteraction.finish()
    remote.background()
    updates.detach(this)
    super.onPause()
  }

  override fun onDestroy() {
    mainHandler.removeCallbacksAndMessages(null)
    unregisterBack?.invoke()
    unregisterBack = null
    qrScanner.detach()
    pairingSheet?.dismiss()
    updateSheet?.dismiss()
    unsubscribe?.invoke()
    unsubscribe = null
    unsubscribeUpdates?.invoke()
    unsubscribeUpdates = null
    super.onDestroy()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    remote.receiveShare(intent)
    navigation.newIntent(shared = isShare(intent))
    volumeInteraction.finish()
    render()
  }

  private fun isShare(intent: Intent) = intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    if (requestCode != PICK_FILES) { super.onActivityResult(requestCode, resultCode, data); return }
    if (resultCode != RESULT_OK || data == null) return
    val clip = data.clipData
    val uris = if (clip != null) (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } else listOfNotNull(data.data)
    remote.sendFiles(uris)
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    // Granted or not, the update goes on: the notice is only a convenience.
    if (requestCode == NOTIFY_UPDATE) startUpdate() else super.onRequestPermissionsResult(requestCode, permissions, grantResults)
  }

  /**
   * Hardware volume keys move the PC volume on the Touchpad and Mídia tabs while
   * connected; elsewhere, disconnected or with a panel open, the phone keeps them.
   */
  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    val code = event.keyCode
    if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN && code != KeyEvent.KEYCODE_VOLUME_MUTE) return super.dispatchKeyEvent(event)
    if (!VolumeKeys.controlsPc(navigation.tab, resumed, state.status == "Conectado", state.snapshot?.audio?.available == true, panels.isOpen)) return super.dispatchKeyEvent(event)
    if (event.action == KeyEvent.ACTION_DOWN) volumeKey(code, event.repeatCount)
    return true // Both down and up: the phone's own volume and panel must not react.
  }

  private fun volumeKey(code: Int, repeat: Int) {
    if (repeat == 0) haptic()
    val touchpad = navigation.tab == Tab.TOUCHPAD
    if (code == KeyEvent.KEYCODE_VOLUME_MUTE) {
      if (repeat > 0) return
      val muted = state.snapshot?.audio?.muted != true
      remote.execute(RemoteAction.VolumeMute(muted)) { error ->
        runOnUiThread {
          if (isDestroyed) return@runOnUiThread
          if (error != null) snackbar.show(error, Tone.DANGER)
          else if (touchpad) snackbar.show(if (muted) "PC sem som." else "Som do PC ativado.", Tone.ACCENT)
        }
      }
      return
    }
    val current = state.draggingVolume ?: state.snapshot?.audio?.percent ?: return
    val next = VolumeKeys.step(current, code == KeyEvent.KEYCODE_VOLUME_UP)
    if (next != current) volumeInteraction.change(next)
    if (touchpad) snackbar.show("Volume do PC: $next%", Tone.ACCENT)
  }

  private fun restartLatency() {
    mainHandler.removeCallbacks(latencyTask)
    if (resumed && navigation.tab == Tab.CONNECTION) mainHandler.post(latencyTask)
  }

  @Deprecated("Handled for devices before Android 13")
  @SuppressLint("GestureBackNavigation") // API 24–32 fallback; API 33+ uses OnBackInvokedCallback.
  override fun onBackPressed() { navigateBack() }

  /** Touchpad once a PC is paired; Conexão until then. */
  private fun homeTab() = if (remote.savedAddress() == null) Tab.CONNECTION else Tab.TOUCHPAD

  private fun navigateBack() {
    if (panels.isOpen) { panels.dismiss(); return }
    val home = homeTab()
    if (navigation.tab != home) selectTab(home) else finish()
  }

  private fun selectTab(tab: Tab) {
    val changed = tab != navigation.tab
    if (changed) hideKeyboard()
    navigation.goTo(tab)
    volumeInteraction.finish()
    if (changed) restartLatency()
    render()
  }

  @Suppress("DEPRECATION")
  private fun installedVersionName(): String? = try {
    packageManager.getPackageInfo(packageName, 0).versionName
  } catch (_: Exception) { null }

  @Suppress("DEPRECATION")
  private fun buildUi() {
    val p = kit.palette
    window.setBackgroundDrawable(ColorDrawable(p.bg))
    window.statusBarColor = p.bg
    window.navigationBarColor = p.surface

    statusChip = kit.chip()
    title = kit.text("PC Remote", TextStyle.TITLE).apply { maxLines = 1; accessibilityHeading() }
    topBar = kit.row(8).apply {
      setPadding(kit.dp(20), kit.dp(12), kit.dp(8), kit.dp(12))
      addView(kit.column(6).apply {
        addView(title)
        addView(kit.row(8).apply { addView(statusChip) })
      }, kit.weighted())
      addView(kit.iconButton(if (p.dark) R.drawable.ic_sun else R.drawable.ic_moon, if (p.dark) "Usar tema claro" else "Usar tema escuro") {
        setTheme(if (p.dark) ThemeMode.LIGHT else ThemeMode.DARK)
      })
    }

    bannerTitle = kit.text("", TextStyle.LABEL, p.accentText).apply { maxLines = 1 }
    banner = kit.row(4).apply {
      background = kit.ripple(kit.rounded(p.accentSoft, 16f), 16f)
      setPadding(kit.dp(16), kit.dp(8), kit.dp(4), kit.dp(8))
      isClickable = true
      setOnClickListener { openUpdate() }
      addView(kit.icon(R.drawable.ic_circle_arrow_up, p.accentText, 22), LinearLayout.LayoutParams(kit.dp(22), kit.dp(22)).apply { marginEnd = kit.dp(8) })
      bannerSubtitle = kit.text("", TextStyle.CAPTION, p.textSecondary).apply { maxLines = 1 }
      addView(kit.column(2).apply {
        addView(bannerTitle)
        addView(bannerSubtitle)
      }, kit.weighted())
      addView(kit.iconButton(R.drawable.ic_x, "Dispensar esta versão", 44) {
        updateState.release?.let { skipUpdate(it.version.toString()) }
      })
    }

    val tabs = FrameLayout(this)
    panels = PanelLayer(kit)
    connection = ConnectionScreen(this)
    screens = linkedMapOf(
      Tab.TOUCHPAD to TouchpadScreen(this),
      Tab.MEDIA to MediaScreen(this, volumeInteraction),
      Tab.CLIPBOARD to ClipboardScreen(this),
      Tab.CONNECTION to connection,
    )
    screens.values.forEach { tabs.addView(it.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }

    navBar = NavigationBar(kit) { selectTab(it) }
    column = kit.column(0).apply {
      addView(topBar, kit.matchWrap())
      addView(banner, kit.matchWrap().apply { leftMargin = kit.dp(16); rightMargin = kit.dp(16); bottomMargin = kit.dp(8) })
      addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
      addView(kit.divider())
      addView(navBar, kit.matchWrap())
    }
    snackbar = Snackbar(kit)
    // Screen readers skip the tabs while a panel is open.
    panels.onOpenChanged = { open ->
      column.importantForAccessibility = if (open) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }
    root = FrameLayout(this).apply {
      setBackgroundColor(p.bg)
      addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
      addView(snackbar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
        leftMargin = kit.dp(16); rightMargin = kit.dp(16); bottomMargin = kit.dp(SNACKBAR_OFFSET_DP)
      })
      addView(panels, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
    setContentView(root)

    // The insets controller exists only once the decor view is installed.
    if (Build.VERSION.SDK_INT >= 30) {
      val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
      window.insetsController?.setSystemBarsAppearance(if (p.dark) 0 else light, light)
    } else {
      window.decorView.systemUiVisibility = if (p.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
        (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
    }

    if (Build.VERSION.SDK_INT >= 30) {
      // Edge to edge: the app paints behind both bars, so panel scrims cover them too.
      window.setDecorFitsSystemWindows(false)
      window.statusBarColor = Color.TRANSPARENT
      window.navigationBarColor = Color.TRANSPARENT
      window.isNavigationBarContrastEnforced = false
      root.setOnApplyWindowInsetsListener { _, insets ->
        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val ime = insets.getInsets(WindowInsets.Type.ime())
        val typing = ime.bottom > bars.bottom
        topBar.setPadding(kit.dp(20) + bars.left, kit.dp(12) + bars.top, kit.dp(8) + bars.right, kit.dp(12))
        // While typing, the keyboard takes the place of the navigation bar.
        navBar.visibility = if (typing) View.GONE else View.VISIBLE
        navBar.setPadding(bars.left, 0, bars.right, bars.bottom)
        column.setPadding(0, 0, 0, if (typing) ime.bottom else 0)
        (snackbar.layoutParams as FrameLayout.LayoutParams).bottomMargin =
          if (typing) ime.bottom + kit.dp(12) else kit.dp(SNACKBAR_OFFSET_DP) + bars.bottom
        snackbar.requestLayout()
        panels.setInsets(bars.top, bars.bottom, if (typing) ime.bottom else 0)
        insets
      }
      root.requestApplyInsets()
    }
  }

  private fun render() {
    if (!::screens.isInitialized) return
    val connected = state.status == "Conectado"
    val (tone, label) = statusTone(state.status)
    statusChip.set(tone, label)
    title.text = state.pcName.ifBlank { "PC Remote" }

    val notify = updateState.notify
    banner.visibility = if (notify) View.VISIBLE else View.GONE
    val (bannerText, bannerHint) = UpdateLabels.banner(updateState)
    bannerTitle.text = bannerText
    bannerSubtitle.text = bannerHint
    navBar.setBadge(Tab.CONNECTION, notify)

    // A finished pairing lands on the touchpad, ready to use.
    if (connected && (lastStatus == "Aguardando aprovação" || lastStatus == "Pareando")) {
      pairingSheet?.dismiss()
      navigation.goTo(Tab.TOUCHPAD)
      snackbar.show("Pareado com ${state.pcName.ifBlank { "o PC" }}.", Tone.SUCCESS)
    }
    lastStatus = state.status

    navBar.select(navigation.tab)
    screens.forEach { (tab, screen) -> screen.view.visibility = if (tab == navigation.tab) View.VISIBLE else View.GONE }
    screens.values.forEach { it.render(state) }
    pairingPanel?.render(state)

    if (state.error.isNotEmpty() && state.error != lastError) {
      lastError = state.error
      snackbar.show(state.error, Tone.DANGER)
      // Posted: clearing publishes a new state, which would re-enter render().
      mainHandler.post { remote.clearError() }
    } else if (state.error.isEmpty()) lastError = ""
  }

  private fun measureLatency() {
    if (state.status != "Conectado") { connection.setLatency(null); return }
    remote.ping { ms ->
      runOnUiThread { if (!isDestroyed) connection.setLatency(ms) }
    }
  }

  private fun hideKeyboard() {
    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken, 0)
  }

  // ScreenHost

  override fun notify(message: String, tone: Tone) = snackbar.show(message, tone)

  override fun haptic() {
    if (state.preferences.haptics) root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
  }

  override fun result(error: String?, success: String?) {
    runOnUiThread {
      if (isDestroyed) return@runOnUiThread
      if (error != null) snackbar.show(error, Tone.DANGER)
      else {
        haptic()
        success?.let { snackbar.show(it, Tone.SUCCESS) }
      }
    }
  }

  override fun navigate(tab: Tab) = selectTab(tab)

  override fun pickFiles() {
    val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
      addCategory(Intent.CATEGORY_OPENABLE)
      type = "*/*"
      putExtra(Intent.EXTRA_MIME_TYPES, FileRules.MIME_TYPES.toTypedArray())
      putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    }
    try {
      startActivityForResult(picker, PICK_FILES)
    } catch (_: ActivityNotFoundException) {
      snackbar.show("Nenhum seletor de arquivos disponível neste celular.", Tone.DANGER)
    }
  }

  override fun scanQr() {
    qrScanner.start(this) { invite, problem ->
      if (problem != null) snackbar.show(problem, Tone.DANGER)
      if (invite != null) {
        remote.pair(invite.host, invite.port, invite.fingerprint, invite.code)
        pairingSheet?.dismiss()
        selectTab(Tab.CONNECTION)
      }
    }
  }

  override fun openPairingSheet() {
    val sheet = Sheet(this, kit, "Parear outro PC", "O PC atual continua salvo até o PC novo aprovar.")
    val panel = PairingPanel(this) { hideKeyboard(); sheet.dismiss() }
    sheet.body.addView(panel.view)
    sheet.dialog.setOnDismissListener { if (pairingSheet === sheet) { pairingSheet = null; pairingPanel = null } }
    pairingSheet = sheet
    pairingPanel = panel
    remote.startDiscovery()
    panel.render(state)
    sheet.show()
  }

  override fun openUpdate() {
    val release = updateState.release ?: return
    updateSheet?.dismiss()
    val sheet = UpdateSheet(this, release)
    sheet.dialog.setOnDismissListener { if (updateSheet === sheet) updateSheet = null }
    updateSheet = sheet
    sheet.show()
  }

  override fun checkUpdates(manual: Boolean) {
    if (updateState.checking) return
    updateState = updateState.copy(checking = true)
    render()
    releases.check(updates.origin()) { lookup ->
      lastLookup = lookup
      runOnUiThread {
        if (isDestroyed) return@runOnUiThread
        prefs.lastUpdateCheck = System.currentTimeMillis()
        updateState = updateState.copy(checking = false, lookup = lookup)
        render()
        if (installAfterCheck) {
          installAfterCheck = false
          if (updateState.available) { startUpdate(); return@runOnUiThread }
        }
        autoDownload()
        if (!manual) return@runOnUiThread
        when {
          lookup is ReleaseLookup.Unavailable -> snackbar.show(lookup.message, Tone.DANGER)
          updateState.available -> openUpdate()
          else -> snackbar.show("Você já está na versão mais recente.", Tone.SUCCESS)
        }
      }
    }
  }

  /** Fades out, recreates with the new palette and fades back in. */
  override fun setTheme(mode: ThemeMode) {
    if (mode == prefs.theme) return
    prefs.theme = mode
    if (Palette.resolve(this, mode).dark == kit.palette.dark) { render(); return }
    root.animate().alpha(0f).setDuration(120).withEndAction {
      fadingOut = true
      recreate()
    }.start()
  }

  override fun skipUpdate(version: String) {
    prefs.skippedUpdate = version
    updates.keep()
    updateState = updateState.copy(skipped = version)
    render()
  }

  override val autoDownload: Boolean get() = prefs.autoDownloadUpdates

  override fun setAutoDownload(enabled: Boolean) {
    prefs.autoDownloadUpdates = enabled
    render()
    autoDownload()
  }

  /** On unmetered networks a version on offer is downloaded and verified ahead; installing still takes a tap. */
  private fun autoDownload() {
    val release = updateState.release ?: return
    if (!prefs.autoDownloadUpdates || !updateState.notify || !updateState.inApp) return
    if (updateState.flow !is UpdateFlow.Idle && updateState.flow !is UpdateFlow.Failed) return
    val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
    if (connectivity.isActiveNetworkMetered) return
    updates.start(release, install = false)
  }

  /** The tap on "Atualizar agora" or "Instalar agora"; nothing installs without it. */
  override fun startUpdate() {
    val release = updateState.release ?: return
    if (!updateState.available) return
    if (!updateState.inApp) { openReleasePage(release.pageUrl); return }
    // Android 13+: asked once, so a notice can confirm the update after the app closes.
    if (Build.VERSION.SDK_INT >= 33 && !prefs.askedUpdateNotice && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
      prefs.askedUpdateNotice = true
      requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFY_UPDATE)
      return
    }
    if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) { explainInstallPermission(); return }
    updates.start(release, install = true)
  }

  private fun canInstallPackages() = Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()

  @RequiresApi(26)
  private fun explainInstallPermission() {
    AlertDialog.Builder(this, dialogTheme(kit.palette))
      .setTitle("Permitir que o PC Remote instale atualizações")
      .setMessage("O Android pede essa autorização uma vez. Na próxima tela, ative \"Permitir desta fonte\" e volte: a atualização continua sozinha.")
      .setNegativeButton("Agora não", null)
      .setPositiveButton("Abrir ajustes") { _, _ ->
        try {
          startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
          awaitingInstallPermission = true
        } catch (_: ActivityNotFoundException) {
          updateNotice("Abra Ajustes → Apps → PC Remote → Instalar apps desconhecidos.", Tone.DANGER)
        }
      }
      .show()
  }

  /** In the update sheet while it is open (it covers the snackbar), otherwise a snackbar. */
  private fun updateNotice(text: String, tone: Tone) {
    val sheet = updateSheet
    if (sheet != null && sheet.showing) sheet.notice(text, tone == Tone.DANGER) else snackbar.show(text, tone)
  }

  override fun openReleasePage(url: String) {
    if (!url.startsWith("https://github.com/")) return
    try {
      startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
      updateNotice("Nenhum navegador disponível para abrir o GitHub.", Tone.DANGER)
    }
  }

  private companion object {
    const val KEY_TAB = "tab"
    const val KEY_FADE_IN = "fadeIn"
    const val KEY_INSTALL_PERMISSION = "installPermission"
    const val NOTIFY_UPDATE = 42
    const val PICK_FILES = 41
    const val LATENCY_INTERVAL_MS = 5000L
    const val UPDATE_INTERVAL_MS = 6 * 60 * 60 * 1000L
    const val SNACKBAR_OFFSET_DP = 92
    val releases = ReleaseChecker()

    /** Survives theme recreation so the banner does not blink away. */
    @Volatile var lastLookup: ReleaseLookup? = null
  }
}
