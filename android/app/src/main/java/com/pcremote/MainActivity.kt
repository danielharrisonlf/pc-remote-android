package com.pcremote

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** The Activity owns Views only. Connection and command state live in RemoteClient. */
internal class MainActivity : Activity(), ScreenHost {
  private val remote: RemoteController get() = (application as RemoteApplication).client
  private val mainHandler = Handler(Looper.getMainLooper())
  private val qrScanner = QrScanner()
  private lateinit var prefs: UiPreferences
  private lateinit var kit: Ui
  private lateinit var navigation: TabNavigation
  private var state = UiState()
  private var updateState = UpdateState()
  private var latency: Long? = null
  private var lastError = ""
  private var lastStatus = ""
  private var resumed = false
  private var fadingOut = false
  private var unsubscribe: (() -> Unit)? = null
  private var unregisterBack: (() -> Unit)? = null
  private var pairingSheet: Sheet? = null
  private var pairingPanel: PairingPanel? = null

  private lateinit var root: FrameLayout
  private lateinit var column: LinearLayout
  private lateinit var topBar: LinearLayout
  private lateinit var title: TextView
  private lateinit var statusChip: StatusChip
  private lateinit var latencyText: TextView
  private lateinit var banner: LinearLayout
  private lateinit var bannerTitle: TextView
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

  private val latencyTask = object : Runnable {
    override fun run() {
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
    updateState = UpdateState(lookup = lastLookup, installed = Releases.installed(installedVersionName()), skipped = prefs.skippedUpdate)
    val restored = savedInstanceState?.getString(KEY_TAB)?.let { runCatching { Tab.valueOf(it) }.getOrNull() }
    navigation = TabNavigation({ remote.disableInput(); volumeInteraction.finish() }, restored ?: homeTab())
    if (savedInstanceState == null && intent?.action == Intent.ACTION_SEND) navigation.goTo(Tab.CLIPBOARD)
    buildUi()
    remote.receiveShare(intent)
    unsubscribe = remote.observe { next ->
      runOnUiThread {
        val changed = state.forDisplay() != next.forDisplay()
        state = next // Always keep the real revisions for actions and reconciliation.
        if (next.status != "Conectado" || next.snapshot?.audio?.available != true) volumeInteraction.cancel()
        if (changed) render()
      }
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
  }

  override fun onResume() {
    super.onResume()
    resumed = true
    remote.foreground(this)
    mainHandler.removeCallbacks(latencyTask)
    mainHandler.post(latencyTask)
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
    super.onPause()
  }

  override fun onDestroy() {
    mainHandler.removeCallbacksAndMessages(null)
    unregisterBack?.invoke()
    unregisterBack = null
    qrScanner.detach()
    pairingSheet?.dismiss()
    unsubscribe?.invoke()
    unsubscribe = null
    super.onDestroy()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    remote.receiveShare(intent)
    navigation.newIntent(shared = intent.action == Intent.ACTION_SEND)
    volumeInteraction.finish()
    render()
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
    if (tab != navigation.tab) hideKeyboard()
    navigation.goTo(tab)
    volumeInteraction.finish()
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
    latencyText = kit.tabular(kit.text("", TextStyle.CAPTION, p.textTertiary))
    title = kit.text("PC Remote", TextStyle.TITLE).apply { maxLines = 1; accessibilityHeading() }
    topBar = kit.row(8).apply {
      setPadding(kit.dp(20), kit.dp(12), kit.dp(8), kit.dp(12))
      addView(kit.column(6).apply {
        addView(title)
        addView(kit.row(8).apply { addView(statusChip); addView(latencyText) })
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
      addView(kit.column(2).apply {
        addView(bannerTitle)
        addView(kit.text("Toque para ver as novidades.", TextStyle.CAPTION, p.textSecondary).apply { maxLines = 1 })
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
    latencyText.text = if (connected && latency != null) "$latency ms" else ""
    latencyText.visibility = if (latencyText.text.isEmpty()) View.GONE else View.VISIBLE

    val notify = updateState.notify
    banner.visibility = if (notify) View.VISIBLE else View.GONE
    updateState.release?.let { bannerTitle.text = "Versão ${it.version} disponível" }
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
    if (!resumed || state.status != "Conectado") {
      if (latency != null) { latency = null; connection.setLatency(null); render() }
      return
    }
    remote.ping { ms ->
      runOnUiThread {
        if (isDestroyed) return@runOnUiThread
        latency = ms
        connection.setLatency(ms)
        render()
      }
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
    UpdateSheet.show(this, release, updateState.installed)
  }

  override fun checkUpdates(manual: Boolean) {
    if (updateState.checking) return
    updateState = updateState.copy(checking = true)
    render()
    releases.check { lookup ->
      lastLookup = lookup
      runOnUiThread {
        if (isDestroyed) return@runOnUiThread
        prefs.lastUpdateCheck = System.currentTimeMillis()
        updateState = updateState.copy(checking = false, lookup = lookup)
        render()
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
    updateState = updateState.copy(skipped = version)
    render()
  }

  private companion object {
    const val KEY_TAB = "tab"
    const val KEY_FADE_IN = "fadeIn"
    const val LATENCY_INTERVAL_MS = 5000L
    const val UPDATE_INTERVAL_MS = 6 * 60 * 60 * 1000L
    const val SNACKBAR_OFFSET_DP = 92
    val releases = ReleaseChecker()

    /** Survives theme recreation so the banner does not blink away. */
    @Volatile var lastLookup: ReleaseLookup? = null
  }
}
