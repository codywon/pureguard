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
    // 倒计时快速点击轮询 Job（500ms 高频盯梢 k9d 按钮是否变为可点击）
    private var countdownPollingJob: Job? = null
    @Volatile
    private var isGesturing = false

    private fun isHongguoPackage(pkg: String): Boolean {
        return pkg == "com.phoenix.read" || pkg == "com.dragon.read"
    }

    private fun cancelAdRetry() {
        if (swipeRetryJob?.isActive == true) {
            Log.i(TAG, "熔断：离开红果短剧或离开播放页，立即彻底终止广告滑动任务")
            swipeRetryJob?.cancel()
            swipeRetryJob = null
        }
        if (countdownPollingJob?.isActive == true) {
            countdownPollingJob?.cancel()
            countdownPollingJob = null
        }
    }

    /**
     * 严格判断是否在短剧播放器界面：
     * 必须存在短剧 ViewPager (dtd) 或底栏上滑 ID (aq7/apt) 或上滑双箭头 (aq6)
     */
    private fun isInDramaPlayer(root: AccessibilityNodeInfo): Boolean {
        return !root.findAccessibilityNodeInfosByViewId("com.phoenix.read:id/dtd").isNullOrEmpty() ||
            !root.findAccessibilityNodeInfosByViewId("com.phoenix.read:id/aq7").isNullOrEmpty() ||
            !root.findAccessibilityNodeInfosByViewId("com.phoenix.read:id/apt").isNullOrEmpty() ||
            !root.findAccessibilityNodeInfosByViewId("com.phoenix.read:id/aq6").isNullOrEmpty()
    }

    /**
     * 【关键突破】穿山甲广告倒计时高频轮询机制：
     *
     * 穿山甲全屏广告在倒计时期间，系统内部存在 k9d 跳过/关闭节点，但 isEnabled=false；
     * 倒计时归零的瞬间，SDK 将 k9d 的 isEnabled 置为 true。
     * 本方法每 500ms 扫描一次所有候选跳过节点的 isEnabled/isClickable 状态，
     * 一旦变为可点击，立即精准系统点击，彻底绕开广告倒计时等待！
     *
     * 同时也会检测 aq7/apt 的上滑解锁条，一旦倒计时结束解锁，
     * 立即执行精准单页上滑手势切走广告。
     */
    private fun startCountdownPolling(packageName: String) {
        if (countdownPollingJob?.isActive == true) return
        countdownPollingJob = serviceScope.launch {
            Log.i(TAG, "[$packageName] 启动 500ms 高频倒计时轮询，盯梢跳过按钮解锁瞬间")
            var pollCount = 0
            val maxPolls = 30 // 最多轮询 15 秒 (30 x 500ms)
            while (pollCount < maxPolls) {
                delay(500L)
                pollCount++

                val root = rootInActiveWindow ?: break
                val pkg = root.packageName?.toString() ?: ""

                // 熔断：离开红果短剧立即退出
                if (!isHongguoPackage(pkg)) {
                    Log.i(TAG, "[$packageName] 倒计时轮询熔断：已切出红果应用")
                    break
                }

                // 尝试 1：直接寻找并点击跳过/关闭节点（k9d / 各类跳过按钮）
                if (tryClickSkipNode(root, pkg)) {
                    Log.i(TAG, "[$packageName] 倒计时轮询第 $pollCount 次：精准点击跳过节点成功！")
                    break
                }

                // 核心防线：检查是否仍处于全屏广告倒计时进行中（k9d 或 tt_reward_full_count_down 存在但未解锁）
                // 只要倒计时还在倒数，全屏蒙层还在，绝对不能执行上滑手势（避免被拦截+避免自杀轮询+避免连翻两集）！
                val isCountingDown = isAdCountingDown(root)
                if (isCountingDown) {
                    Log.d(TAG, "[$packageName] 倒计时轮询第 $pollCount 次：广告倒计时进行中，保持盯梢，禁止滑动")
                    continue
                }

                // 尝试 2：全屏广告不存在或已结束，若此时 aq7/apt 上滑条已就绪，执行精准单页上滑
                if (trySwipeIfUnlocked(root, pkg)) {
                    Log.i(TAG, "[$packageName] 倒计时轮询第 $pollCount 次：aq7/apt 已解锁，执行单页上滑成功！")
                    break
                }

                // 尝试 3：检测是否已自然进入正片（广告已消失）
                val hasAq7 = !root.findAccessibilityNodeInfosByViewId("com.phoenix.read:id/aq7").isNullOrEmpty()
                val hasApt = !root.findAccessibilityNodeInfosByViewId("com.phoenix.read:id/apt").isNullOrEmpty()
                if (!hasAq7 && !hasApt && !isInDramaPlayer(root)) {
                    Log.i(TAG, "[$packageName] 倒计时轮询第 $pollCount 次：广告已自然结束，退出轮询")
                    break
                }

                Log.d(TAG, "[$packageName] 倒计时轮询第 $pollCount 次：跳过按钮尚未解锁，继续等待")
            }
            countdownPollingJob = null
        }
    }

    /**
     * 尝试点击所有可能的跳过/关闭节点（包括穿山甲的 k9d 节点）：
     * 通过 isEnabled && isClickable 双重验证，确保只在倒计时归零后才精准点击
     */
    private fun tryClickSkipNode(root: AccessibilityNodeInfo, packageName: String): Boolean {
        // 1. 直接通过 ViewId 命中 k9d（红果历史上的穿山甲跳过按钮）
        val k9dNodes = root.findAccessibilityNodeInfosByViewId("com.phoenix.read:id/k9d")
        if (!k9dNodes.isNullOrEmpty()) {
            for (node in k9dNodes) {
                if (node.isEnabled && node.isClickable) {
                    val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) {
                        lastClickTime = System.currentTimeMillis()
                        Log.i(TAG, "[$packageName] 精准点击 k9d 跳过按钮成功！倒计时已归零")
                        onNodeClicked?.invoke(packageName, "k9d跳过按钮", "倒计时归零精准点击")
                        return true
                    }
                }
                // k9d 节点存在但尚未解锁（isEnabled=false），说明倒计时仍在进行，记录状态
                val nodeText = node.text?.toString() ?: ""
                Log.d(TAG, "[$packageName] k9d 节点存在但未解锁: text=$nodeText, enabled=${node.isEnabled}, clickable=${node.isClickable}")
            }
        }

        // 2. 通过文本匹配寻找跳过/关闭按钮（支持 "X秒后跳过"、"跳过"、"关闭" 等）
        for (keyword in COMMON_SKIP_TEXT_KEYWORDS) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    val text = node.text?.toString() ?: ""
                    if (isActionableSkipText(text)) {
                        // 向上寻找可点击父节点
                        var target: AccessibilityNodeInfo? = node
                        while (target != null && !target.isClickable) {
                            target = target.parent
                        }
                        if (target != null && target.isEnabled && target.isClickable) {
                            val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            if (clicked) {
                                lastClickTime = System.currentTimeMillis()
                                Log.i(TAG, "[$packageName] 精准点击文本跳过节点成功: text=$text")
                                onNodeClicked?.invoke(packageName, "文本跳过: $text", "倒计时轮询命中")
                                return true
                            }
                        }
                    }
                }
            }
        }

        return false
    }

    /**
     * 判断当前是否正处于广告强制倒计时中（全屏蒙层锁死中）
     * 倒计时期间坚决禁止执行任何滑动操作，必须高频轮询盯梢解锁瞬间
     */
    private fun isAdCountingDown(root: AccessibilityNodeInfo): Boolean {
        // 1. 穿山甲核心倒计时 ID
        val countdownIds = listOf(
            "com.phoenix.read:id/k9d",
            "com.phoenix.read:id/tt_reward_full_count_down",
            "com.phoenix.read:id/tt_video_ad_close_layout",
            "com.dragon.read:id/k9d",
            "com.dragon.read:id/tt_reward_full_count_down",
            "com.dragon.read:id/tt_video_ad_close_layout"
        )
        for (id in countdownIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) {
                val node = nodes.first()
                // 如果存在倒计时控件，且尚未解锁（未使能或不可点击），或者文本带数字秒数
                if (!node.isEnabled || !node.isClickable) {
                    return true
                }
                val text = node.text?.toString() ?: ""
                if (text.contains(Regex("""\d+\s*[秒s]""")) || text.contains("后可")) {
                    return true
                }
            }
        }

        // 2. 文本特征辅助判断（如 "5s", "3秒后可跳过", "奖励将于"）
        val countdownTexts = listOf("后可跳过", "后可关闭", "后发放", "秒后可")
        for (ct in countdownTexts) {
            val nodes = root.findAccessibilityNodeInfosByText(ct)
            if (!nodes.isNullOrEmpty()) {
                return true
            }
        }

        return false
    }

    /**
     * 检查 aq7/apt 节点是否已被穿山甲 SDK 解锁（isEnabled=true），
     * 若解锁则执行单页上滑手势
     */
    private fun trySwipeIfUnlocked(root: AccessibilityNodeInfo, packageName: String): Boolean {
        // 检查 aq7 或 apt 节点是否 isEnabled=true（解锁状态）
        for (viewId in listOf("com.phoenix.read:id/aq7", "com.phoenix.read:id/apt")) {
            val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
            if (!nodes.isNullOrEmpty()) {
                val node = nodes.first()
                if (node.isEnabled) {
                    Log.i(TAG, "[$packageName] $viewId 节点已解锁(isEnabled=true)，立即执行单页上滑")
                    return performSwipeUp(packageName, "$viewId 解锁上滑", root)
                }
            }
        }
        return false
    }

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
     * 严密封闭的单次垂直标准单页吸附滑动手势：
     * 1. 严格检查必须在红果短剧前台，且必须在短剧播放器界面；
     * 2. 具备 2800ms 强力冷却锁，翻页后杜绝任何二次翻页；
     * 3. 严格垂直居中（X: 50%），行程为 72% -> 22%（跨度 50%），持续 300ms，
     *    既能稳定超过 ViewPager 的吸附阈值翻到下一集，又完全消除暴冲惯性，绝对杜绝连翻两集错过视频！
     */
    private fun performSwipeUp(packageName: String, reason: String, root: AccessibilityNodeInfo? = null): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        // 铁律 1：严禁在非红果应用中注入任何手势！
        if (!isHongguoPackage(packageName)) {
            Log.w(TAG, "拒绝在非红果应用中执行手势: $packageName")
            cancelAdRetry()
            return false
        }

        // 铁律 2：如果提供了 root，必须验证是否在短剧播放界面，严禁在红果其它页面乱滑！
        if (root != null && !isInDramaPlayer(root)) {
            Log.w(TAG, "拒绝在非短剧播放界面执行手势")
            cancelAdRetry()
            return false
        }

        val now = System.currentTimeMillis()
        // 关键防护：2800ms 硬核冷却锁，彻底杜绝连续翻两页错过短剧
        if (now - lastSwipeTime < 2800L || isGesturing) {
            return false
        }

        // 标准单页吸附拖拽手势
        return try {
            isGesturing = true
            val displayMetrics = resources.displayMetrics
            val width = displayMetrics.widthPixels.toFloat()
            val height = displayMetrics.heightPixels.toFloat()

            val startX = width * 0.50f
            val startY = height * 0.70f
            val endX = width * 0.50f
            val endY = height * 0.25f

            val swipePath = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }

            // 360ms 平稳单页拖拽抛掷，行程 45% 适度越过吸附中线，消除暴冲动量，杜绝二次连翻
            val stroke = GestureDescription.StrokeDescription(swipePath, 0L, 360L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    isGesturing = false
                    Log.i(TAG, "标准单页上滑手势成功完成: $reason")
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    isGesturing = false
                    Log.w(TAG, "上滑手势被系统取消: $reason")
                }
            }, null)

            Log.i(TAG, "提交标准单页上滑手势: pkg=$packageName, reason=$reason, dispatched=$dispatched")
            if (dispatched) {
                lastSwipeTime = now
                lastClickTime = now
                cancelAdRetry() // 既然已经成功划走，立即彻底自毁所有重试任务，严禁后方补刀二次滑动！
                onNodeClicked?.invoke(packageName, "精准单页手势 · 自动划过黑屏广告", reason)
                true
            } else {
                isGesturing = false
                false
            }
        } catch (e: Exception) {
            isGesturing = false
            Log.e(TAG, "执行上滑手势异常: ${e.message}")
            false
        }
    }

    private fun scheduleAdSwipeRetry(packageName: String, reason: String) {
        if (!isHongguoPackage(packageName)) return
        if (swipeRetryJob?.isActive == true) return
        val now = System.currentTimeMillis()
        if (now - lastSwipeTime < 2800L) return // 刚翻过页处于 2800ms 冷却期内，严禁挂载重试

        swipeRetryJob = serviceScope.launch {
            // 每隔 1000ms 探测一次（最多重试 4 次），等待倒计时结束瞬间单次划过
            for (i in 1..4) {
                delay(1000L)
                val currentRoot = rootInActiveWindow ?: break
                val currentPkg = currentRoot.packageName?.toString() ?: ""
                // 一旦离开红果短剧或离开播放页，立即彻底自毁退出！
                if (!isHongguoPackage(currentPkg) || !isInDramaPlayer(currentRoot)) {
                    Log.i(TAG, "[$packageName] 已离开短剧播放界面，重试任务自毁")
                    break
                }

                val hasAq7 = !currentRoot.findAccessibilityNodeInfosByViewId("com.phoenix.read:id/aq7").isNullOrEmpty()
                val hasApt = !currentRoot.findAccessibilityNodeInfosByViewId("com.phoenix.read:id/apt").isNullOrEmpty()
                if (hasAq7 || hasApt) {
                    Log.i(TAG, "[$packageName] 广告倒计时已结束 (第 $i 次探测解锁)，执行精准单次上滑")
                    val swiped = performSwipeUp(currentPkg, "$reason (探测 $i)", currentRoot)
                    if (swiped) {
                        Log.i(TAG, "[$packageName] 单次翻页手势已成功派发，立即彻底终止重试循环，杜绝二次连翻！")
                        break // 关键修复：一旦滑过立即退出循环，坚决杜绝再次滑动错过视频！
                    }
                } else {
                    Log.i(TAG, "[$packageName] 广告已不在前台，停止重试")
                    break
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !_autoSkipEnabled.value) return

        val packageName = event.packageName?.toString() ?: ""
        // 关键守卫 1：一旦离开红果短剧（包括桌面、微信等），立即彻底熔断手势与重试！
        if (!isHongguoPackage(packageName)) {
            cancelAdRetry()
            return
        }

        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
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

        val rootNode = rootInActiveWindow ?: event.source ?: return
        // 关键守卫 2：如果在红果短剧内部，但不在短剧播放界面（如我的、书架、详情页），立即熔断重试并返回！
        if (!isInDramaPlayer(rootNode)) {
            cancelAdRetry()
            return
        }

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

        // 0-PRE. 【穿山甲倒计时精准处理 · 最高优先级】
        // k9d 节点在倒计时期间存在但 isEnabled=false，归零瞬间变为 isEnabled=true
        // 策略：检测到 k9d 且已解锁 → 立即精准点击；倒计时进行中 → 启动 500ms 高频轮询盯梢，等到归零再点
        if (isHongguo) {
            val k9dNodes = root.findAccessibilityNodeInfosByViewId("com.phoenix.read:id/k9d")
            if (!k9dNodes.isNullOrEmpty()) {
                val k9dNode = k9dNodes.first()
                if (k9dNode.isEnabled && k9dNode.isClickable) {
                    // 倒计时已归零，立即精准点击！
                    val clicked = k9dNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) {
                        lastClickTime = System.currentTimeMillis()
                        cancelAdRetry()
                        Log.i(TAG, "[$packageName] k9d 已解锁，精准点击跳过广告成功！")
                        onNodeClicked?.invoke(packageName, "k9d倒计时解锁", "倒计时归零即时点击")
                        return
                    }
                } else {
                    // 倒计时仍在进行，启动 500ms 高频轮询盯梢解锁瞬间
                    val nodeText = k9dNode.text?.toString() ?: ""
                    Log.i(TAG, "[$packageName] 穿山甲广告倒计时进行中（k9d: $nodeText, enabled=${k9dNode.isEnabled}），启动高频轮询")
                    startCountdownPolling(packageName)
                    return // 等待轮询处理，本次事件不再往下走
                }
            }

            // 通用倒计时安全防护：若检测到全屏广告倒计时进行中（如带倒计时文本或倒计时View），
            // 坚决拦截本次滑动，启动 500ms 高频盯梢轮询，杜绝倒计时期间误划与自杀式轮询
            if (isAdCountingDown(root)) {
                Log.i(TAG, "[$packageName] 检测到广告倒计时中，启动高频轮询盯梢，拦截本次滑动")
                startCountdownPolling(packageName)
                return
            }
        }

        // 0. 实机精准命中：针对官方红果短剧 (com.phoenix.read) 广告底栏（上滑继续观看短剧）精确 ViewId
        // 注意：此处仅处理倒计时已结束、上滑条已出现的情况（k9d 已消失 or 不存在）
        if (isHongguo) {
            for (viewId in HONGGUO_SWIPE_UP_VIEW_IDS) {
                val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
                if (!nodes.isNullOrEmpty()) {
                    Log.i(TAG, "[$packageName] 命中红果广告底栏精准ID: $viewId，立即触发标准单页上滑")
                    val swiped = performSwipeUp(packageName, "命中红果短剧广告底栏精准ID: $viewId", root)
                    if (swiped) {
                        return // 成功翻页，直接返回，绝不挂载重试，杜绝二次翻页！
                    } else {
                        scheduleAdSwipeRetry(packageName, "精准ID: $viewId")
                    }
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
                        if (swiped) {
                            return // 成功翻页，直接返回，杜绝二次翻页！
                        } else {
                            scheduleAdSwipeRetry(packageName, "提示: $nodeText")
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
        // 1. 检查是否存在广告标签且非正常选集
        val adLabelNodes = root.findAccessibilityNodeInfosByText("广告")
        if (!adLabelNodes.isNullOrEmpty()) {
            val episodeNodes = root.findAccessibilityNodeInfosByText("选集")
            if (episodeNodes.isNullOrEmpty()) {
                Log.i(TAG, "[$packageName] 检测到非正片广告卡片，立即注入标准单页手势")
                val swiped = performSwipeUp(packageName, "短剧广告卡片即时划过", root)
                if (swiped) {
                    return true
                } else {
                    scheduleAdSwipeRetry(packageName, "短剧广告卡片即时划过")
                    return false
                }
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
                        if (swiped) {
                            return true
                        } else {
                            scheduleAdSwipeRetry(packageName, "几何命中底部上滑栏")
                            return false
                        }
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
