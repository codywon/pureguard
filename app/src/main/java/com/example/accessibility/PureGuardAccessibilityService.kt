package com.example.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PureGuardAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastClickTime = 0L
    private var lastInspectTime = 0L
    private var lastSwipeTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isRunning.value = true
    }

    private fun performSwipeUp(packageName: String, reason: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val now = System.currentTimeMillis()
        // 上滑手势冷却防抖，避免连续触发多次连翻数集
        if (now - lastSwipeTime < 1200L) {
            return false
        }

        return try {
            val displayMetrics = resources.displayMetrics
            val width = displayMetrics.widthPixels.toFloat()
            val height = displayMetrics.heightPixels.toFloat()

            // 模拟高初速向上滑动手势：从屏幕 82% 划向 12% 处（跨度达 70% 屏幕高度），产生强力 Fling 换页
            val startX = width * 0.48f
            val startY = height * 0.82f
            val endX = width * 0.48f
            val endY = height * 0.12f

            val swipePath = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }

            val stroke = GestureDescription.StrokeDescription(swipePath, 0L, 180L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            val success = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                }
            }, null)

            if (success) {
                lastSwipeTime = now
                lastClickTime = now
                onNodeClicked?.invoke(packageName, "模拟上滑手势 · 翻过黑屏广告", reason)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !_autoSkipEnabled.value) return

        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        val now = System.currentTimeMillis()
        // 1. 点击去抖动：跳过触发后至少冷却 400ms，避免连击导致误触后续界面
        if (now - lastClickTime < 400L) {
            return
        }

        // 2. 高频内容变动节流：TYPE_WINDOW_CONTENT_CHANGED 至少间隔 250ms 检测一次，杜绝手机发热和掉帧
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && (now - lastInspectTime < 250L)) {
            return
        }
        lastInspectTime = now

        val packageName = event.packageName?.toString() ?: ""
        // 过滤自身应用主界面触发，减少无意义的 View 树扫描开销
        if (packageName == applicationContext.packageName) {
            return
        }

        val rootNode = rootInActiveWindow ?: return

        try {
            findAndClickSkipOrCloseNode(rootNode, packageName)
        } catch (_: Exception) {
        }
    }

    private fun findAndClickSkipOrCloseNode(root: AccessibilityNodeInfo, packageName: String) {
        // 1. 核心针对短剧应用：优先检测“上滑继续观看”等黑屏信息流广告提示，自动注入手势翻页
        for (keyword in COMMON_SWIPE_UP_KEYWORDS) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    val nodeText = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
                    if (isSwipeUpHint(nodeText)) {
                        // 若节点或容器可直接点击，先尝试点击触发
                        performClickOnNode(node, packageName, "点击上滑提示: $nodeText")
                        // 注入原生上滑手势，直接划走黑屏广告页，无缝恢复短剧播放
                        if (performSwipeUp(packageName, "命中上滑关键词: $nodeText")) {
                            return
                        }
                    }
                }
            }
        }

        // 2. 按短剧/穿山甲/优量汇常见关闭和跳过 ViewId 检索（O(1) 索引快速命中）
        for (viewId in COMMON_AD_CLOSE_VIEW_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    if (performClickOnNode(node, packageName, "ViewId匹配: $viewId")) {
                        return
                    }
                }
            }
        }

        // 3. 检索常用文本关键字 (跳过、关闭、继续播放短剧等)
        for (keyword in COMMON_SKIP_TEXT_KEYWORDS) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    val nodeText = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
                    if (isActionableSkipText(nodeText)) {
                        if (performClickOnNode(node, packageName, "文本匹配: $nodeText")) {
                            return
                        }
                    }
                }
            }
        }

        // 4. 浅层递归检索黑屏倒计时结束、带图标关闭按钮或隐式上滑提示 (限制最大深度为 5，防卡顿)
        inspectRecursive(root, packageName, 0)
    }

    private fun isSwipeUpHint(text: String): Boolean {
        if (text.isBlank()) return false
        val trimmed = text.trim()
        if (COMMON_SWIPE_UP_KEYWORDS.any { trimmed.contains(it) }) return true
        if (trimmed.contains("上滑") || trimmed.contains("向上滑") || trimmed.contains("滑动")) {
            if (trimmed.contains("看") || trimmed.contains("继续") || trimmed.contains("剧") || trimmed.contains("下") || trimmed.contains("跳过") || trimmed.contains("播")) {
                return true
            }
        }
        if (trimmed.contains("继续观看") && (trimmed.contains("短剧") || trimmed.contains("剧"))) {
            return true
        }
        return false
    }

    private fun isActionableSkipText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed == "跳过" || trimmed == "关闭" || trimmed == "×" || trimmed == "X" || trimmed == "x") return true
        if (trimmed.startsWith("跳过") && trimmed.length <= 8) return true
        if (trimmed.endsWith("跳过") && trimmed.length <= 8) return true
        if (trimmed.contains("跳过广告") || trimmed.contains("关闭广告")) return true
        // 匹配倒计时结束后的状态，如 "0s 跳过"、"跳过 (0s)"
        if (trimmed.contains("0s") && (trimmed.contains("跳过") || trimmed.contains("关闭"))) return true
        return false
    }

    private fun inspectRecursive(node: AccessibilityNodeInfo?, packageName: String, depth: Int): Boolean {
        if (node == null || depth > 5) return false

        val desc = node.contentDescription?.toString() ?: ""
        val text = node.text?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""

        // 识别“上滑继续观看”等滑动提示
        if (isSwipeUpHint(text) || isSwipeUpHint(desc)) {
            performClickOnNode(node, packageName, "点击上滑控件: ${text.ifBlank { desc }}")
            if (performSwipeUp(packageName, "递归命中上滑提示: ${text.ifBlank { desc }}")) {
                return true
            }
        }

        // 识别常见的广告关闭 ContentDescription
        if (desc.contains("关闭") || desc.contains("跳过") || desc.equals("close", ignoreCase = true) || desc.equals("skip", ignoreCase = true)) {
            if (performClickOnNode(node, packageName, "描述匹配: $desc")) {
                return true
            }
        }

        // 识别穿山甲/激励视频倒计时结束后的特征节点
        if (viewId.contains("close", ignoreCase = true) || viewId.contains("skip", ignoreCase = true)) {
            if (performClickOnNode(node, packageName, "特征匹配: $viewId")) {
                return true
            }
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val matched = inspectRecursive(child, packageName, depth + 1)
                if (matched) return true
            }
        }
        return false
    }

    private fun performClickOnNode(node: AccessibilityNodeInfo, packageName: String, ruleName: String): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) {
            target = target.parent
        }

        if (target != null && target.isClickable) {
            val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clicked) {
                lastClickTime = System.currentTimeMillis()
                val targetDesc = node.viewIdResourceName ?: node.text?.toString() ?: node.contentDescription?.toString() ?: "跳过按钮"
                onNodeClicked?.invoke(packageName, targetDesc, ruleName)
                return true
            }
        }
        return false
    }

    override fun onInterrupt() {
        _isRunning.value = false
    }

    override fun onDestroy() {
        _isRunning.value = false
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _autoSkipEnabled = MutableStateFlow(true)
        val autoSkipEnabled: StateFlow<Boolean> = _autoSkipEnabled.asStateFlow()

        fun setAutoSkipEnabled(enabled: Boolean) {
            _autoSkipEnabled.value = enabled
        }

        var onNodeClicked: ((packageName: String, nodeInfo: String, ruleName: String) -> Unit)? = null

        fun isServiceRunning(context: Context): Boolean {
            // 1. 若服务已与系统建立连接并激活，直接返回 true
            if (_isRunning.value) return true

            // 2. 使用 AccessibilityManager 官方标准 API 获取已启用服务
            try {
                val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                val enabledServices = am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                if (enabledServices != null) {
                    val targetName = PureGuardAccessibilityService::class.java.name
                    val isFound = enabledServices.any { service ->
                        val sInfo = service.resolveInfo?.serviceInfo ?: return@any false
                        sInfo.packageName == context.packageName && (sInfo.name == targetName || sInfo.name.contains("PureGuardAccessibilityService"))
                    }
                    if (isFound) return true
                }
            } catch (_: Exception) {
            }

            // 3. 回退查询 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES 字段
            try {
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                val colonSplitter = enabledServices.split(":")
                for (componentName in colonSplitter) {
                    if (componentName.contains(context.packageName) && componentName.contains("PureGuardAccessibilityService")) {
                        return true
                    }
                    if (componentName.contains("PureGuardAccessibilityService")) {
                        return true
                    }
                }
            } catch (_: Exception) {
            }

            return false
        }

        fun openAccessibilitySettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }

        fun openAppDetailsSettings(context: Context) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }

        // 短剧及常见广告 SDK 的关闭与跳过按钮特征 ID
        private val COMMON_AD_CLOSE_VIEW_IDS = listOf(
            "com.dragon.read:id/tt_video_ad_close_layout",
            "com.dragon.read:id/tt_reward_full_count_down",
            "com.dragon.read:id/tt_splash_skip_btn",
            "com.dragon.read:id/iv_close_interstitial",
            "com.dragon.read:id/ad_float_banner_close",
            "com.phoenix.drama:id/iv_close_interstitial",
            "tt_video_ad_close_layout",
            "tt_reward_full_count_down",
            "tt_splash_skip_btn",
            "iv_close_interstitial",
            "ad_float_banner_close",
            "btn_close",
            "iv_close",
            "close_btn",
            "skip_view",
            "skip_btn"
        )

        private val COMMON_SKIP_TEXT_KEYWORDS = listOf(
            "跳过",
            "关闭",
            "放弃奖励",
            "跳过广告",
            "关闭广告",
            "×",
            "X"
        )

        // 短剧信息流全屏广告常见“上滑继续观看”提示词
        private val COMMON_SWIPE_UP_KEYWORDS = listOf(
            "上滑继续观看短剧",
            "上滑继续观看",
            "上滑看短剧",
            "继续观看短剧",
            "上滑看下一集",
            "上滑看下一部",
            "上滑进入下一集",
            "上滑继续",
            "上滑跳过",
            "滑动继续观看短剧",
            "滑动继续观看",
            "滑动看短剧",
            "滑动继续",
            "滑动跳过",
            "向上滑动",
            "继续观看",
            "上滑",
            "赏花继续观看短剧",
            "赏花继续观看"
        )
    }
}
