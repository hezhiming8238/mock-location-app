package com.mocklocation.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mocklocation.assistant.databinding.ActivityMainBinding
import com.mocklocation.assistant.databinding.DialogManualCoordBinding
import com.mocklocation.assistant.service.MockLocationService
import com.mocklocation.assistant.util.CoordSystem
import com.mocklocation.assistant.util.CoordinateConverter
import com.mocklocation.assistant.util.GeocodingHelper
import com.mocklocation.assistant.util.LocationStore
import com.mocklocation.assistant.util.MockPermissionHelper
import com.mocklocation.assistant.util.SearchPlace
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: LocationStore

    private var selectedWgsLat: Double = LocationStore.DEFAULT_WGS_LAT
    private var selectedWgsLng: Double = LocationStore.DEFAULT_WGS_LNG
    private var selectedLabel: String = LocationStore.DEFAULT_LABEL

    private var mapLoaded: Boolean = false
    private var nudgeStepMeters: Double = 50.0
    private var walkBearingIndex: Int = 0
    private val walkDirections = listOf(
        0f to "向北",
        90f to "向东",
        180f to "向南",
        270f to "向西"
    )
    private val stepChoices = listOf(10.0, 50.0, 200.0)
    private var pendingStartAfterPermission: Boolean = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        refreshPermissionBanner()
        if (pendingStartAfterPermission) {
            pendingStartAfterPermission = false
            if (MockPermissionHelper.hasLocationPermission(this)) {
                startOrUpdateMockService()
            } else {
                showToast(getString(R.string.err_missing_location_permission))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = LocationStore(this)
        val last = store.getLastTarget()
        selectedWgsLat = last.wgsLat
        selectedWgsLng = last.wgsLng
        selectedLabel = last.label
        nudgeStepMeters = store.getStepMeters()

        setupWebView()
        setupUiListeners()
        updateBottomCard()
        updateStepButtonLabel()
        observeServiceState()
        requestStartupPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionBanner()
        refreshAntiDriftBanner()
        MockLocationService.refreshOverlay(this)
    }

    override fun onDestroy() {
        binding.webViewMap.destroy()
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(binding.webViewMap.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        binding.webViewMap.addJavascriptInterface(MapJsBridge(), "AndroidBridge")
        binding.webViewMap.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                mapLoaded = true
                syncPinToMap(centerMap = true)
            }
        }
        binding.webViewMap.loadUrl("file:///android_asset/map.html")
    }

    private fun setupUiListeners() {
        binding.btnSearch.setOnClickListener { performSearch() }
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else {
                false
            }
        }

        binding.chipAntiDrift.setOnClickListener { showAntiDriftDialog() }
        binding.btnFixAntiDrift.setOnClickListener { showAntiDriftDialog() }
        binding.chipCities.setOnClickListener { showCitiesDialog() }
        binding.chipManualCoord.setOnClickListener { showManualCoordDialog() }
        binding.chipFavorites.setOnClickListener { showFavoritesAndHistoryDialog() }
        binding.chipNudge.setOnClickListener {
            val isVisible = binding.layoutDpad.visibility == View.VISIBLE
            binding.layoutDpad.visibility = if (isVisible) View.GONE else View.VISIBLE
        }
        binding.chipHelp.setOnClickListener { showHelpDialog() }

        binding.btnOpenDevSettings.setOnClickListener {
            MockPermissionHelper.openDeveloperSettings(this)
        }

        binding.btnFavoriteToggle.setOnClickListener {
            val nowFav = store.toggleFavorite(selectedLabel, selectedWgsLat, selectedWgsLng)
            updateFavoriteButton(nowFav)
            showToast(if (nowFav) "已添加到收藏夹" else "已从收藏夹移除")
        }

        binding.tvWgsCoords.setOnClickListener {
            copyToClipboard(
                "WGS-84",
                String.format(Locale.US, "%.6f, %.6f", selectedWgsLat, selectedWgsLng)
            )
        }
        binding.tvGcjCoords.setOnClickListener {
            val gcj = CoordinateConverter.wgs84ToGcj02(selectedWgsLat, selectedWgsLng)
            copyToClipboard(
                "GCJ-02",
                String.format(Locale.US, "%.6f, %.6f", gcj.lat, gcj.lng)
            )
        }

        binding.btnPrimaryAction.setOnClickListener {
            handleStartOrUpdateClick()
        }
        binding.btnStopAction.setOnClickListener {
            MockLocationService.stop(this)
        }

        // Nudge & Walk controls
        binding.btnNudgeStep.setOnClickListener {
            val idx = stepChoices.indexOfFirst { it == nudgeStepMeters }
            nudgeStepMeters = stepChoices[(idx + 1) % stepChoices.size]
            store.setStepMeters(nudgeStepMeters)
            updateStepButtonLabel()
        }
        binding.btnNudgeNorth.setOnClickListener { nudgeBy(nudgeStepMeters, 0.0) }
        binding.btnNudgeSouth.setOnClickListener { nudgeBy(-nudgeStepMeters, 0.0) }
        binding.btnNudgeEast.setOnClickListener { nudgeBy(0.0, nudgeStepMeters) }
        binding.btnNudgeWest.setOnClickListener { nudgeBy(0.0, -nudgeStepMeters) }

        binding.btnWalkDirection.setOnClickListener {
            walkBearingIndex = (walkBearingIndex + 1) % walkDirections.size
            val (bearing, dirLabel) = walkDirections[walkBearingIndex]
            binding.btnWalkDirection.text = dirLabel
            val currentState = MockLocationService.state.value
            if (currentState.isRunning && currentState.isWalking) {
                MockLocationService.updateWalkMode(this, 1.5f, bearing)
            }
        }

        binding.btnToggleWalk.setOnClickListener {
            val currentState = MockLocationService.state.value
            if (!currentState.isRunning) {
                showToast("请先点击“开始虚拟定位”，再开启模拟行走")
                return@setOnClickListener
            }
            if (currentState.isWalking) {
                MockLocationService.updateWalkMode(this, 0f, walkDirections[walkBearingIndex].first)
            } else {
                MockLocationService.updateWalkMode(this, 1.5f, walkDirections[walkBearingIndex].first)
            }
        }
    }

    private fun observeServiceState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MockLocationService.state.collect { state ->
                    if (!state.errorMessage.isNullOrBlank()) {
                        showToast(state.errorMessage)
                        refreshPermissionBanner()
                    }
                    if (state.isRunning) {
                        if (state.isWalking) {
                            selectedWgsLat = state.wgsLat
                            selectedWgsLng = state.wgsLng
                            store.saveLastTarget(selectedWgsLat, selectedWgsLng, selectedLabel)
                            updateBottomCard()
                            syncPinToMap(centerMap = false)
                        } else {
                            syncPinToMap(centerMap = false)
                        }
                        binding.btnPrimaryAction.text = getString(R.string.btn_update_mock)
                        binding.btnStopAction.visibility = View.VISIBLE
                        binding.tvStatusBadge.text = if (state.isWalking) {
                            getString(R.string.status_walking, state.walkSpeedMps, state.tickCount)
                        } else {
                            getString(R.string.status_active, state.tickCount)
                        }
                        binding.tvStatusBadge.setBackgroundColor(
                            ContextCompat.getColor(this@MainActivity, R.color.statusActiveBg)
                        )
                        binding.tvStatusBadge.setTextColor(
                            ContextCompat.getColor(this@MainActivity, R.color.statusActiveText)
                        )
                        binding.btnToggleWalk.text = if (state.isWalking) {
                            getString(R.string.walk_toggle_stop)
                        } else {
                            getString(R.string.walk_toggle_start)
                        }
                    } else {
                        binding.btnPrimaryAction.text = getString(R.string.btn_start_mock)
                        binding.btnStopAction.visibility = View.GONE
                        binding.tvStatusBadge.text = getString(R.string.status_idle)
                        binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_status_card)
                        binding.tvStatusBadge.setTextColor(
                            ContextCompat.getColor(this@MainActivity, R.color.colorPrimaryDark)
                        )
                        binding.btnToggleWalk.text = getString(R.string.walk_toggle_start)
                        syncPinToMap(centerMap = false)
                    }
                }
            }
        }
    }

    private fun requestStartupPermissionsIfNeeded() {
        val perms = mutableListOf<String>()
        if (!MockPermissionHelper.hasLocationPermission(this)) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
            perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !MockPermissionHelper.hasNotificationPermission(this)
        ) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (perms.isNotEmpty()) {
            permissionLauncher.launch(perms.toTypedArray())
        }
    }

    private fun refreshPermissionBanner() {
        val hasMock = MockPermissionHelper.canUseMockLocation(this)
        val hasLoc = MockPermissionHelper.hasLocationPermission(this)
        when {
            !hasLoc -> {
                binding.cardPermissionBanner.visibility = View.VISIBLE
                binding.tvPermissionWarning.text = getString(R.string.err_missing_location_permission)
                binding.btnOpenDevSettings.text = "授权"
                binding.btnOpenDevSettings.setOnClickListener { requestStartupPermissionsIfNeeded() }
            }
            !hasMock -> {
                binding.cardPermissionBanner.visibility = View.VISIBLE
                binding.tvPermissionWarning.text = getString(R.string.permission_banner_title)
                binding.btnOpenDevSettings.text = getString(R.string.permission_banner_action)
                binding.btnOpenDevSettings.setOnClickListener {
                    MockPermissionHelper.openDeveloperSettings(this)
                }
            }
            else -> {
                binding.cardPermissionBanner.visibility = View.GONE
            }
        }
    }

    private fun handleStartOrUpdateClick() {
        if (!MockPermissionHelper.hasLocationPermission(this)) {
            pendingStartAfterPermission = true
            requestStartupPermissionsIfNeeded()
            return
        }
        if (!MockPermissionHelper.canUseMockLocation(this)) {
            refreshPermissionBanner()
            MaterialAlertDialogBuilder(this)
                .setTitle("需要设置模拟位置应用")
                .setMessage(
                    "Android 系统要求必须在“开发者选项”中授权本应用，定位才能对高德、百度、腾讯等地图软件生效。\n\n" +
                        "操作路径：\n1. 点击下方「前往开发者选项」\n2. 找到「选择模拟位置信息应用」\n3. 选择「虚拟定位助手」"
                )
                .setPositiveButton("前往开发者选项") { _, _ ->
                    MockPermissionHelper.openDeveloperSettings(this)
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        startOrUpdateMockService()
    }

    private fun startOrUpdateMockService() {
        store.saveLastTarget(selectedWgsLat, selectedWgsLng, selectedLabel)
        store.addToHistory(selectedLabel, selectedWgsLat, selectedWgsLng)
        MockLocationService.startOrUpdate(
            context = this,
            wgsLat = selectedWgsLat,
            wgsLng = selectedWgsLng,
            label = selectedLabel,
            jitter = store.isJitterEnabled()
        )
    }

    private fun selectLocation(
        wgsLat: Double,
        wgsLng: Double,
        label: String,
        centerMap: Boolean
    ) {
        selectedWgsLat = wgsLat
        selectedWgsLng = wgsLng
        selectedLabel = label
        store.saveLastTarget(wgsLat, wgsLng, label)
        updateBottomCard()
        syncPinToMap(centerMap = centerMap)

        if (MockLocationService.state.value.isRunning) {
            startOrUpdateMockService()
        }
    }

    private fun nudgeBy(northMeters: Double, eastMeters: Double) {
        val next = CoordinateConverter.offsetMeters(
            selectedWgsLat, selectedWgsLng, northMeters, eastMeters
        )
        val label = if (selectedLabel.startsWith("地图微调") || selectedLabel.startsWith("地图选点")) {
            "地图微调点"
        } else {
            selectedLabel
        }
        selectLocation(next.lat, next.lng, label, centerMap = true)
    }

    private fun updateBottomCard() {
        binding.tvLocationTitle.text = selectedLabel
        val gcj = CoordinateConverter.wgs84ToGcj02(selectedWgsLat, selectedWgsLng)
        binding.tvWgsCoords.text = String.format(
            Locale.US,
            "WGS-84 (系统GPS): %.6f, %.6f  [点击复制]",
            selectedWgsLat,
            selectedWgsLng
        )
        binding.tvGcjCoords.text = String.format(
            Locale.US,
            "GCJ-02 (高德/腾讯): %.6f, %.6f",
            gcj.lat,
            gcj.lng
        )
        updateFavoriteButton(store.isFavorite(selectedWgsLat, selectedWgsLng))
    }

    private fun updateFavoriteButton(isFav: Boolean) {
        binding.btnFavoriteToggle.text = if (isFav) "★ 已收藏" else "☆ 收藏"
    }

    private fun updateStepButtonLabel() {
        binding.btnNudgeStep.text = getString(R.string.nudge_step_label, nudgeStepMeters.toInt())
    }

    private fun syncPinToMap(centerMap: Boolean) {
        if (!mapLoaded) return
        val gcj = CoordinateConverter.wgs84ToGcj02(selectedWgsLat, selectedWgsLng)
        val isMocking = MockLocationService.state.value.isRunning
        val js = String.format(
            Locale.US,
            "window.updatePinFromNative(%.7f, %.7f, %.7f, %.7f, %s, %s);",
            selectedWgsLat,
            selectedWgsLng,
            gcj.lat,
            gcj.lng,
            centerMap.toString(),
            isMocking.toString()
        )
        binding.webViewMap.evaluateJavascript(js, null)
    }

    private fun performSearch() {
        val query = binding.etSearch.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) {
            showToast("请输入地点名称或经纬度")
            return
        }
        hideKeyboard()
        binding.progressSearch.visibility = View.VISIBLE
        binding.btnSearch.isEnabled = false

        lifecycleScope.launch {
            val results = GeocodingHelper.search(this@MainActivity, query)
            binding.progressSearch.visibility = View.GONE
            binding.btnSearch.isEnabled = true

            if (results.isEmpty()) {
                showToast("未找到匹配地点，可尝试直接输入经纬度或在地图上手动选点")
                return@launch
            }
            if (results.size == 1 && CoordinateConverter.parseSmartCoordinateInput(query) == null) {
                val item = results.first()
                selectLocation(item.wgsLat, item.wgsLng, item.title, centerMap = true)
            } else {
                showSearchResultsDialog("搜索结果：$query", results)
            }
        }
    }

    private fun showSearchResultsDialog(title: String, places: List<SearchPlace>) {
        val labels = places.map { "${it.title}\n${it.subtitle}" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(labels) { _, which ->
                val chosen = places[which]
                selectLocation(chosen.wgsLat, chosen.wgsLng, chosen.title, centerMap = true)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCitiesDialog() {
        showSearchResultsDialog("热门城市与地标", GeocodingHelper.BUILT_IN_CITIES)
    }

    private fun showManualCoordDialog() {
        val dialogBinding = DialogManualCoordBinding.inflate(layoutInflater)
        val gcj = CoordinateConverter.wgs84ToGcj02(selectedWgsLat, selectedWgsLng)
        dialogBinding.etInputLat.setText(String.format(Locale.US, "%.6f", gcj.lat))
        dialogBinding.etInputLng.setText(String.format(Locale.US, "%.6f", gcj.lng))

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.manual_coord_title)
            .setView(dialogBinding.root)
            .setPositiveButton("定位到此坐标", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val lat = dialogBinding.etInputLat.text?.toString()?.trim()?.toDoubleOrNull()
                val lng = dialogBinding.etInputLng.text?.toString()?.trim()?.toDoubleOrNull()
                if (lat == null || lng == null || lat !in -85.0..85.0 || lng !in -180.0..180.0) {
                    showToast("请输入有效的纬度 (-85~85) 和经度 (-180~180)")
                    return@setOnClickListener
                }
                val sys = when {
                    dialogBinding.rbWgs84.isChecked -> CoordSystem.WGS84
                    dialogBinding.rbBd09.isChecked -> CoordSystem.BD09
                    else -> CoordSystem.GCJ02
                }
                val wgs = CoordinateConverter.toWgs84(lat, lng, sys)
                val customLabel = dialogBinding.etInputLabel.text?.toString()?.trim()
                    .takeUnless { it.isNullOrBlank() } ?: "手动坐标点"
                selectLocation(wgs.lat, wgs.lng, customLabel, centerMap = true)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showFavoritesAndHistoryDialog() {
        val favs = store.getFavorites()
        val history = store.getHistory()
        val combined = mutableListOf<Pair<String, Pair<Double, Double>>>()

        favs.forEach {
            combined.add("★ [收藏] ${it.name}\n${String.format(Locale.US, "%.5f, %.5f", it.wgsLat, it.wgsLng)}" to (it.wgsLat to it.wgsLng))
        }
        history.forEach {
            combined.add("🕘 [历史] ${it.name}\n${String.format(Locale.US, "%.5f, %.5f", it.wgsLat, it.wgsLng)}" to (it.wgsLat to it.wgsLng))
        }

        if (combined.isEmpty()) {
            showToast("暂无收藏或历史记录，选点后点击“☆ 收藏”即可保存")
            return
        }

        val items = combined.map { it.first }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("收藏夹与最近定位")
            .setItems(items) { _, which ->
                val rawTitle = combined[which].first.lineSequence().firstOrNull() ?: "已保存地点"
                val cleanName = rawTitle
                    .removePrefix("★ [收藏] ")
                    .removePrefix("🕘 [历史] ")
                val (lat, lng) = combined[which].second
                selectLocation(lat, lng, cleanName, centerMap = true)
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showHelpDialog() {
        val vivoHint = if (MockPermissionHelper.isVivoDevice()) {
            "【检测到 vivo / iQOO 手机防跳回必设】\n" +
                "• 多任务界面将「虚拟定位助手」下拉加锁，防止切高德后被冻结。\n" +
                "• 设置 → 应用与权限 → 应用管理 → 虚拟定位助手 → 电量 → 允许后台高耗电。\n" +
                "• 点击顶部「防跳回设置」，关闭 Wi-Fi 与系统「WLAN 和蓝牙扫描」，并开启悬浮窗保活胶囊。\n\n"
        } else {
            ""
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("使用说明与防漂移提示")
            .setMessage(
                "1. 开启开发者选项：\n" +
                    "   打开手机「设置 → 关于手机」，连续点击“版本号”7次。\n\n" +
                    "2. 授权模拟位置应用：\n" +
                    "   进入「设置 → 开发者选项 → 选择模拟位置信息应用」，选择「虚拟定位助手」。\n\n" +
                    "3. 开始使用：\n" +
                    "   在地图上点击任意位置（或搜索/输入经纬度），点击「开始虚拟定位」，再打开高德、百度、腾讯或 Google 地图即可显示目标位置。\n\n" +
                    vivoHint +
                    "4. 关于高德防跳回与微信说明：\n" +
                    "   • 若高德 1~2 秒跳回真实位置，通常是开启了 Wi-Fi/WLAN 扫描或后台被系统冻结。请点击「防跳回设置」逐项优化。\n" +
                    "   • 微信内置 isFromMockProvider() 模拟位置检测与基站交叉校验，未 Root 手机通过系统开发者选项无法绕过微信的实时位置检测。"
            )
            .setPositiveButton("打开防跳回设置") { _, _ ->
                showAntiDriftDialog()
            }
            .setNegativeButton("知道了", null)
            .show()
    }

    private fun refreshAntiDriftBanner() {
        val wifiOn = MockPermissionHelper.isWifiEnabled(this)
        val scanOn = MockPermissionHelper.isWifiScanAlwaysEnabled(this)
        val batteryRestricted = !MockPermissionHelper.isIgnoringBatteryOptimizations(this)
        val overlayMissing = store.isOverlayEnabled() && !MockPermissionHelper.canDrawOverlays(this)

        val issues = mutableListOf<String>()
        if (wifiOn) issues.add("Wi-Fi未关")
        if (scanOn) issues.add("WLAN扫描未关")
        if (batteryRestricted) issues.add("未设省电无限制")
        if (overlayMissing) issues.add("未开悬浮窗保活")

        if (issues.isEmpty()) {
            binding.cardAntiDriftBanner.visibility = View.GONE
        } else {
            binding.cardAntiDriftBanner.visibility = View.VISIBLE
            binding.tvAntiDriftSummary.text =
                "防跳回提醒：${issues.joinToString("、")}（可能导致高德 1~2 秒跳回真实位置）"
        }
    }

    private fun showAntiDriftDialog() {
        val wifiOn = MockPermissionHelper.isWifiEnabled(this)
        val scanOn = MockPermissionHelper.isWifiScanAlwaysEnabled(this)
        val batteryOk = MockPermissionHelper.isIgnoringBatteryOptimizations(this)
        val overlayGranted = MockPermissionHelper.canDrawOverlays(this)
        val overlayEnabled = store.isOverlayEnabled()
        val isVivo = MockPermissionHelper.isVivoDevice()

        val options = arrayOf(
            "${if (!wifiOn) "✅" else "⚠️"} 1. 关闭 Wi-Fi 开关（防路由器 MAC 反查跳回）\n    当前：${if (wifiOn) "已开启（建议关闭，改用移动数据）" else "已关闭"}",
            "${if (!scanOn) "✅" else "⚠️"} 2. 关闭系统「WLAN 和蓝牙扫描」\n    当前：${if (scanOn) "后台扫描开启中（点击前往关闭）" else "已关闭"}",
            "${if (batteryOk) "✅" else "⚠️"} 3. 忽略电池优化（防止后台降频）\n    当前：${if (batteryOk) "已设为无限制" else "受省电策略限制（点击授权）"}",
            "${if (overlayGranted && overlayEnabled) "✅" else "⚠️"} 4. 悬浮窗保活胶囊（防止切高德后被冻结）\n    当前：${
                when {
                    !overlayGranted -> "未授予悬浮窗权限（点击授权）"
                    overlayEnabled -> "已开启（点击可暂时关闭）"
                    else -> "已关闭（点击开启）"
                }
            }",
            "📱 5. 应用后台详情设置" + if (isVivo) {
                "（vivo 必设：电量→允许后台高耗电；并在多任务栏下拉锁定本应用）"
            } else {
                "（允许后台高耗电 / 自启动）"
            }
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(if (isVivo) "vivo 防跳回诊断与优化" else "防跳回诊断与优化")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> MockPermissionHelper.openWifiSettings(this)
                    1 -> MockPermissionHelper.openLocationScanningSettings(this)
                    2 -> MockPermissionHelper.requestIgnoreBatteryOptimizations(this)
                    3 -> {
                        if (!overlayGranted) {
                            MockPermissionHelper.openOverlaySettings(this)
                        } else {
                            val next = !overlayEnabled
                            store.setOverlayEnabled(next)
                            MockLocationService.refreshOverlay(this)
                            refreshAntiDriftBanner()
                            showToast(if (next) "已开启悬浮窗保活胶囊" else "已关闭悬浮窗保活胶囊")
                        }
                    }
                    4 -> MockPermissionHelper.openAppDetailsSettings(this)
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun copyToClipboard(label: String, value: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
        showToast("已复制 $label: $value")
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        currentFocus?.windowToken?.let { imm?.hideSoftInputFromWindow(it, 0) }
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    inner class MapJsBridge {
        @JavascriptInterface
        fun onMapReady() {
            runOnUiThread {
                mapLoaded = true
                syncPinToMap(centerMap = true)
            }
        }

        @JavascriptInterface
        fun onMapTapped(lat: Double, lng: Double, coordType: String) {
            val wgs = if (coordType == "GCJ02") {
                CoordinateConverter.gcj02ToWgs84(lat, lng)
            } else {
                com.mocklocation.assistant.util.LatLng(lat, lng)
            }
            runOnUiThread {
                val label = String.format(Locale.US, "地图选点 (%.4f, %.4f)", lat, lng)
                selectLocation(wgs.lat, wgs.lng, label, centerMap = false)
            }
        }
    }
}
