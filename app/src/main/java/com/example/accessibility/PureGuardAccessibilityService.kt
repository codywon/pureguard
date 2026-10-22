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
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PureGuardAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastClickTime = 0L
    private var lastInspectTime = 0L
    private var lastSwipeTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isRunning.value = true
        Log.i(TAG, "PureGuardAccessibilityService 已与系统建立连接并激活")
    }

    /**
     * 高爆发 Fling 向上滑动手势：
     * 起点避开底部全面屏手势小白条（70% 屏幕高度），终点至 18% 屏幕高度，
     * 200ms 持续时间产生充足的 TOUCH_MOVE 采样点，并在 ACTION_UP 时触发标准的 ViewPager2 抛掷翻页。
     */
    private fun performSwipeUp(packageName: String, reason: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val now = System.currentTimeMillis()
        if (now - lastSwipeTime < 600L) {
            return false
        }

        return try {
            val displayMetrics = resources.displayMetrics
            val width = displayMetrics.widthPixels.toFloat()
            val height = displayMetrics.heightPixels.toFloat()

            val startX = width * 0.50f
            val startY = height * 0.70f
            val endX = width * 0.50f
            val endY = height * 0.18f

            val swipePath = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }

            val stroke = GestureDescription.StrokeDescription(swipePath, 0L, 200L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    Log.i(TAG, "上滑手势分发成功完成: $reason")
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.w(TAG, "上滑手势被系统取消: $reason")
                }
            }, null)

            Log.i(TAG, "提交上滑手势: pkg=$packageName, reason=$reason, dispatched=$dispatched")
            if (dispatched) {
                lastSwipeTime = now
                lastClickTime = now
                onNodeClicked?.invoke(packageName, "极速手势 · 自动划过黑屏广告", reason)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行上滑手势异常: ${e.message}")
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

        val packageName = event.packageName?.toString() ?: ""
        if (packageName == applicationContext.packageName) {
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastClickTime < 300L) {
            return
        }

        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && (now - lastInspectTime < 150L)) {
            return
        }
        lastInspectTime = now

        // 优先使用活跃窗口根节点，若为 null 则回退使用事件源节点，杜绝全屏 SDK 浮层下 root 为空的漏检
        val rootNode = rootInActiveWindow ?: event.source ?: return

        try {
            findAndClickSkipOrCloseNode(rootNode, packageName)
        } catch (e: Exception) {
            Log.w(TAG, "处理无障碍节点异常: ${e.message}")
        }
    }

    private fun findAndClickSkipOrCloseNode(root: AccessibilityNodeInfo, packageName: String) {
        val isHongguo = packageName == "com.phoenix.read" ||
            packageName == "com.dragon.read" ||
            packageName.startsWith("com.phoenix.") ||
            packageName.startsWith("com.dragon.")

        // 1. 针对短剧应用（红果免费短剧）：检索“上滑继续观看短剧”等关键词，自动执行手势切集
        for (keyword in SWIPE_SEARCH_KEYWORDS) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    val nodeText = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
                    if (isSwipeUpHint(nodeText)) {
                        Log.i(TAG, "[$packageName] 识别到上滑切集提示: $nodeText")
                        if (performSwipeUp(packageName, "命中提示: $nodeText")) {
                            return
                        }
                    }
                }
            }
        }

        // 2. 按官方红果短剧及主流广告 SDK 关闭/跳过 ViewId 检索
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

        // 3. 检索常用跳过/关闭文本
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

        // 4. 若为红果短剧，检测纯广告标签或倒计时卡片
        if (isHongguo) {
            inspectHongguoAdCard(root, packageName)
        }
    }

    private fun inspectHongguoAdCard(root: AccessibilityNodeInfo, packageName: String): Boolean {
        val adLabelNodes = root.findAccessibilityNodeInfosByText("广告")
        if (!adLabelNodes.isNullOrEmpty()) {
            val episodeNodes = root.findAccessibilityNodeInfosByText("选集")
            if (episodeNodes.isNullOrEmpty()) {
                Log.i(TAG, "[$packageName] 检测到非正片广告卡片，立即注入手势划过")
                return performSwipeUp(packageName, "短剧广告卡片即时划过")
            }
        }
        return false
    }

    private fun isSwipeUpHint(text: String): Boolean {
        if (text.isBlank()) return false
        val trimmed = text.trim()
        if (trimmed.contains("上滑") || trimmed.contains("向上滑") || trimmed.contains("滑动") || trimmed.contains("赏花")) {
            return true
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
        if (trimmed.contains("放弃奖励")) return true
        if (trimmed.contains("0s") && (trimmed.contains("跳过") || trimmed.contains("关闭"))) return true
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
                Log.i(TAG, "[$packageName] 点击节点成功: target=$targetDesc, rule=$ruleName")
                onNodeClicked?.invoke(packageName, targetDesc, ruleName)
                return true
            }
        }
        return false
    }

    override fun onInterrupt() {
        _isRunning.value = false
        Log.w(TAG, "PureGuardAccessibilityService 中断")
    }

    override fun onDestroy() {
        _isRunning.value = false
        serviceScope.cancel()
        Log.i(TAG, "PureGuardAccessibilityService 销毁")
        super.onDestroy()
    }

    companion object {
        const val TAG = "PureGuard-A11y"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _autoSkipEnabled = MutableStateFlow(true)
        val autoSkipEnabled: StateFlow<Boolean> = _autoSkipEnabled.asStateFlow()

        fun setAutoSkipEnabled(enabled: Boolean) {
            _autoSkipEnabled.value = enabled
        }

        var onNodeClicked: ((packageName: String, nodeInfo: String, ruleName: String) -> Unit)? = null

        fun isServiceRunning(context: Context): Boolean {
            if (_isRunning.value) return true

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

        private val SWIPE_SEARCH_KEYWORDS = listOf(
            "上滑",
            "滑动",
            "继续观看",
            "赏花",
            "下一集",
            "下一部",
            "继续看",
            "可上滑",
            "看短剧"
        )

        private val COMMON_AD_CLOSE_VIEW_IDS = listOf(
            "com.phoenix.read:id/tt_video_ad_close_layout",
            "com.phoenix.read:id/tt_reward_full_count_down",
            "com.phoenix.read:id/tt_splash_skip_btn",
            "com.phoenix.read:id/iv_close_interstitial",
            "com.phoenix.read:id/ad_float_banner_close",
            "com.phoenix.read:id/skip_view",
            "com.phoenix.read:id/skip_btn",
            "com.phoenix.read:id/close_btn",
            "com.phoenix.read:id/tt_top_dislike",
            "com.dragon.read:id/tt_video_ad_close_layout",
            "com.dragon.read:id/tt_reward_full_count_down",
            "com.dragon.read:id/tt_splash_skip_btn",
            "com.dragon.read:id/iv_close_interstitial",
            "com.dragon.read:id/ad_float_banner_close",
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
    }
}
