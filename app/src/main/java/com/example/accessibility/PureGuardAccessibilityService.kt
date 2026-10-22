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
import kotlinx.coroutines.Job
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
    private var swipeRetryJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            serviceInfo = info
        } catch (_: Exception) {
        }
        _isRunning.value = true
        Log.i(TAG, "PureGuardAccessibilityService 已与系统建立连接并成功激活 FLAG_REPORT_VIEW_IDS")
    }

    /**
     * 超长行程强力 Fling 向上滑动手势 + ViewPager 原生 ACTION_SCROLL_FORWARD 联动：
     * 1. 尝试直接调用 ViewPager (dtd / l0) 的原生翻页 action，零阻尼秒切；
     * 2. 起点 86% 屏幕高度，终点直达 8% 屏幕高度，行程高达 78% 屏幕高度（小米17真机超 2000px）；
     * 3. 150ms 超高速抛掷，赋予 ViewPager 充沛动量，彻底击穿穿山甲广告卡片的翻页阻尼阈值，杜绝回弹！
     */
    private fun performSwipeUp(packageName: String, reason: String, root: AccessibilityNodeInfo? = null): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val now = System.currentTimeMillis()
        if (now - lastSwipeTime < 350L) {
            return false
        }

        // 1. 原生 ViewPager 翻页指令尝试
        if (root != null) {
            try {
                val scrollableContainers = listOf("com.phoenix.read:id/dtd", "com.phoenix.read:id/l0")
                for (id in scrollableContainers) {
                    val nodes = root.findAccessibilityNodeInfosByViewId(id)
                    if (!nodes.isNullOrEmpty()) {
                        for (node in nodes) {
                            if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                                Log.i(TAG, "[$packageName] 成功通过 ViewPager ACTION_SCROLL_FORWARD 翻页: $id")
                                lastSwipeTime = now
                                lastClickTime = now
                                onNodeClicked?.invoke(packageName, "原生翻页 · 自动划过广告", reason)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "尝试 ACTION_SCROLL_FORWARD 异常: ${e.message}")
            }
        }

        // 2. 超长行程 Fling 手势抛掷
        return try {
            val displayMetrics = resources.displayMetrics
            val width = displayMetrics.widthPixels.toFloat()
            val height = displayMetrics.heightPixels.toFloat()

            val startX = width * 0.50f
            val startY = height * 0.86f
            val endX = width * 0.50f
            val endY = height * 0.08f

            val swipePath = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }

            val stroke = GestureDescription.StrokeDescription(swipePath, 0L, 150L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    Log.i(TAG, "超长 Fling 上滑手势分发成功完成: $reason")
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.w(TAG, "上滑手势被系统取消: $reason")
                }
            }, null)

            Log.i(TAG, "提交超长上滑手势: pkg=$packageName, reason=$reason, dispatched=$dispatched")
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

    private fun scheduleAdSwipeRetry(packageName: String, reason: String) {
        if (swipeRetryJob?.isActive == true) return
        swipeRetryJob = serviceScope.launch {
            for (i in 1..8) {
                delay(500L)
                val currentRoot = rootInActiveWindow ?: continue
                val hasAq7 = !currentRoot.findAccessibilityNodeInfosByViewId("com.phoenix.read:id/aq7").isNullOrEmpty()
                val hasApt = !currentRoot.findAccessibilityNodeInfosByViewId("com.phoenix.read:id/apt").isNullOrEmpty()
                val hasAd = !currentRoot.findAccessibilityNodeInfosByText("广告").isNullOrEmpty()
                val hasSelect = !currentRoot.findAccessibilityNodeInfosByText("选集").isNullOrEmpty()

                val isStillOnAd = (hasAq7 || hasApt) || (hasAd && !hasSelect)
                if (isStillOnAd) {
                    Log.i(TAG, "[$packageName] 广告卡片依然存在 (第 $i 次重试倒计时解锁翻页)，再次执行强力上滑")
                    performSwipeUp(packageName, "$reason (重试 $i)", currentRoot)
                } else {
                    Log.i(TAG, "[$packageName] 广告卡片已消失，短剧已成功恢复播放，停止重试")
                    break
                }
            }
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

        // 0. 实机精准命中：针对官方红果短剧 (com.phoenix.read) 广告底栏（上滑继续观看短剧）精确 ViewId
        if (isHongguo) {
            for (viewId in HONGGUO_SWIPE_UP_VIEW_IDS) {
                val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
                if (!nodes.isNullOrEmpty()) {
                    Log.i(TAG, "[$packageName] 命中红果广告底栏精准ID: $viewId，立即触发上滑并挂载重试")
                    val swiped = performSwipeUp(packageName, "命中红果短剧广告底栏精准ID: $viewId", root)
                    scheduleAdSwipeRetry(packageName, "精准ID: $viewId")
                    if (swiped) return
                }
            }
        }

        // 1. 针对短剧应用（红果免费短剧）：检索“上滑继续观看短剧”等关键词，自动执行手势切集
        for (keyword in SWIPE_SEARCH_KEYWORDS) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    val nodeText = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
                    if (isSwipeUpHint(nodeText)) {
                        Log.i(TAG, "[$packageName] 识别到上滑切集提示: $nodeText")
                        val swiped = performSwipeUp(packageName, "命中提示: $nodeText", root)
                        scheduleAdSwipeRetry(packageName, "提示: $nodeText")
                        if (swiped) return
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
        // 1. 检查是否存在广告标签且非正常选集
        val adLabelNodes = root.findAccessibilityNodeInfosByText("广告")
        if (!adLabelNodes.isNullOrEmpty()) {
            val episodeNodes = root.findAccessibilityNodeInfosByText("选集")
            if (episodeNodes.isNullOrEmpty()) {
                Log.i(TAG, "[$packageName] 检测到非正片广告卡片，立即注入手势划过并挂载重试")
                val swiped = performSwipeUp(packageName, "短剧广告卡片即时划过", root)
                scheduleAdSwipeRetry(packageName, "短剧广告卡片即时划过")
                return swiped
            }
        }

        // 2. 几何特征兜底：检查屏幕底部是否存在高度在 88% 以下的底栏容器 (即 aq7/apt 上滑条)
        try {
            val displayMetrics = resources.displayMetrics
            val screenHeight = displayMetrics.heightPixels
            val bottomThreshold = (screenHeight * 0.88f).toInt()

            val rect = android.graphics.Rect()
            fun searchBottomBar(node: AccessibilityNodeInfo, depth: Int): Boolean {
                if (depth > 8) return false
                node.getBoundsInScreen(rect)
                if (rect.top >= bottomThreshold && rect.height() in 25..180 && rect.width() > 180) {
                    val id = node.viewIdResourceName ?: ""
                    val text = node.text?.toString() ?: ""
                    val desc = node.contentDescription?.toString() ?: ""
                    val isAdBottomBar = id.contains("aq7") || id.contains("apt") || id.contains("aq6") ||
                        text.contains("上滑继续观看") || text.contains("赏花继续观看") ||
                        desc.contains("上滑继续观看")
                    if (isAdBottomBar) {
                        Log.i(TAG, "[$packageName] 几何与特征双重命中底部上滑栏: bounds=$rect, id=$id, text=$text")
                        val swiped = performSwipeUp(packageName, "几何命中底部上滑栏: $rect", root)
                        scheduleAdSwipeRetry(packageName, "几何命中底部上滑栏")
                        return swiped
                    }
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    if (searchBottomBar(child, depth + 1)) return true
                }
                return false
            }
            if (searchBottomBar(root, 0)) return true
        } catch (_: Exception) {
        }

        return false
    }

    private fun isSwipeUpHint(text: String): Boolean {
        if (text.isBlank()) return false
        val trimmed = text.trim()
        // 严格排除打分、评论、点赞、分享等正常看剧元素，坚决防止正片被误滑跳过
        if (trimmed.contains("评分") || trimmed.contains("评论") || trimmed.contains("点赞") || trimmed.contains("分享") || trimmed.contains("投币")) {
            return false
        }
        if (trimmed.contains("上滑继续观看") ||
            trimmed.contains("上滑看下一集") ||
            trimmed.contains("上滑观看下一集") ||
            trimmed.contains("赏花继续观看") ||
            trimmed.contains("上滑查看更多")
        ) {
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

        // 实测小米17真机红果短剧 (com.phoenix.read) 广告底栏（上滑继续观看短剧）精准 ViewId
        private val HONGGUO_SWIPE_UP_VIEW_IDS = listOf(
            "com.phoenix.read:id/aq7", // “上滑继续观看短剧” 文本节点
            "com.phoenix.read:id/apt", // “上滑继续观看短剧” 底部容器
            "com.phoenix.read:id/aq6", // 上滑向上双箭头 ImageView
            "aq7",
            "apt",
            "aq6"
        )

        private val SWIPE_SEARCH_KEYWORDS = listOf(
            "上滑继续观看短剧",
            "上滑继续观看",
            "上滑观看下一集",
            "上滑看下一集",
            "赏花继续观看短剧",
            "赏花继续观看",
            "上滑查看更多短剧"
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
