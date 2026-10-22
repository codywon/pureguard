package com.example.model

data class DnsRule(
    val id: String,
    val domainPattern: String,
    val category: String,
    val description: String,
    val isEnabled: Boolean = true
)

data class SkipSelectorRule(
    val id: String,
    val name: String,
    val targetPackage: String,
    val activityName: String,
    val selectorExpression: String,
    val matchTextKeywords: List<String>,
    val matchViewIdKeywords: List<String>,
    val actionDelayMs: Long = 120L,
    val isEnabled: Boolean = true,
    val hitCount: Int = 0
)

data class InterceptLogEntry(
    val id: String,
    val timestamp: String,
    val type: InterceptType,
    val target: String,
    val ruleApplied: String,
    val savedTimeMs: Long,
    val blocked: Boolean
)

enum class InterceptType(val label: String) {
    DNS_BLOCK("DNS 拦截"),
    UI_AUTO_SKIP("节点触发"),
    REWARD_CALLBACK("回调放行")
}

enum class SimulatedAdType(
    val title: String,
    val subtitle: String,
    val viewId: String,
    val buttonText: String,
    val adDomain: String,
    val durationSec: Int
) {
    SPLASH_COUNTDOWN(
        title = "开屏广告",
        subtitle = "5s 倒计时跳过节点",
        viewId = "com.dragon.read:id/tt_splash_skip_btn",
        buttonText = "跳过",
        adDomain = "ad.zijieapi.com",
        durationSec = 5
    ),
    EPISODE_INTERSTITIAL(
        title = "插屏弹窗",
        subtitle = "剧集切换全屏图层",
        viewId = "com.phoenix.drama:id/iv_close_interstitial",
        buttonText = "关闭",
        adDomain = "pangolin-sdk-toutiao.com",
        durationSec = 3
    ),
    REWARD_UNLOCK(
        title = "激励视频",
        subtitle = "完播解锁回调节点",
        viewId = "com.dragon.read:id/tt_video_ad_close_layout",
        buttonText = "跳过",
        adDomain = "mon.snssdk.com",
        durationSec = 15
    ),
    BOTTOM_BANNER(
        title = "底部浮层",
        subtitle = "播放页悬浮推广条",
        viewId = "com.dragon.read:id/ad_float_banner_close",
        buttonText = "关闭",
        adDomain = "mi.gdt.qq.com",
        durationSec = 1
    )
}
