package com.example.viewmodel

import androidx.lifecycle.ViewModel
import com.example.model.DnsRule
import com.example.model.InterceptLogEntry
import com.example.model.InterceptType
import com.example.model.SimulatedAdType
import com.example.model.SkipSelectorRule
import com.example.vpn.LocalDnsVpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class GuardUiState(
    val autoSkipEngineEnabled: Boolean = true,
    val preserveRewardCallback: Boolean = true,
    val dnsRules: List<DnsRule> = GuardViewModel.defaultDnsRules(),
    val selectorRules: List<SkipSelectorRule> = GuardViewModel.defaultSelectorRules(),
    val logs: List<InterceptLogEntry> = GuardViewModel.defaultInitialLogs(),
    val totalAdsSkipped: Int = 18,
    val totalDnsBlocked: Int = 42,
    val totalSavedSeconds: Int = 165,
    val currentEpisode: Int = 12,
    val isEpisodeUnlocked: Boolean = true,
    val activeSimulatedAd: SimulatedAdType? = null,
    val lastSandboxStatusMessage: String = "就绪 · 自动匹配已启用"
)

class GuardViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(GuardUiState())
    val uiState: StateFlow<GuardUiState> = _uiState.asStateFlow()

    init {
        LocalDnsVpnService.onDomainInspected = { domain ->
            evaluateDnsQuery(domain)
        }
    }

    fun toggleAutoSkipEngine(enabled: Boolean) {
        _uiState.update {
            it.copy(
                autoSkipEngineEnabled = enabled,
                lastSandboxStatusMessage = if (enabled) {
                    "自动匹配已启用：识别目标节点后立即触发"
                } else {
                    "自动匹配已暂停：需手动关闭弹窗"
                }
            )
        }
    }

    fun togglePreserveRewardCallback(preserve: Boolean) {
        _uiState.update { it.copy(preserveRewardCallback = preserve) }
    }

    fun toggleDnsRule(ruleId: String) {
        _uiState.update { state ->
            state.copy(
                dnsRules = state.dnsRules.map {
                    if (it.id == ruleId) it.copy(isEnabled = !it.isEnabled) else it
                }
            )
        }
    }

    fun addCustomDnsRule(domainPattern: String, category: String, description: String) {
        val cleanDomain = domainPattern.trim().lowercase(Locale.ROOT)
        if (cleanDomain.isBlank()) return
        val newRule = DnsRule(
            id = UUID.randomUUID().toString(),
            domainPattern = cleanDomain,
            category = category.ifBlank { "自定义" },
            description = description.ifBlank { "用户自定义规则" },
            isEnabled = true
        )
        _uiState.update { state ->
            state.copy(dnsRules = listOf(newRule) + state.dnsRules)
        }
    }

    fun toggleSelectorRule(ruleId: String) {
        _uiState.update { state ->
            state.copy(
                selectorRules = state.selectorRules.map {
                    if (it.id == ruleId) it.copy(isEnabled = !it.isEnabled) else it
                }
            )
        }
    }

    fun addCustomSelectorRule(
        name: String,
        targetPackage: String,
        viewIdKeyword: String,
        textKeyword: String
    ) {
        if (name.isBlank()) return
        val pkg = targetPackage.trim().ifBlank { "com.dragon.read" }
        val viewId = viewIdKeyword.trim().ifBlank { "skip_btn" }
        val textKey = textKeyword.trim().ifBlank { "跳过" }
        val selector = "[id*=\"$viewId\"][text*=\"$textKey\"][clickable=true]"

        val newRule = SkipSelectorRule(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            targetPackage = pkg,
            activityName = ".ui.ad.RewardVideoActivity",
            selectorExpression = selector,
            matchTextKeywords = listOf(textKey),
            matchViewIdKeywords = listOf(viewId),
            actionDelayMs = 80L,
            isEnabled = true,
            hitCount = 0
        )
        _uiState.update { state ->
            state.copy(selectorRules = listOf(newRule) + state.selectorRules)
        }
    }

    fun evaluateDnsQuery(domain: String): Boolean {
        val clean = domain.trim().lowercase(Locale.ROOT)
        val state = _uiState.value
        val matchedRule = state.dnsRules.firstOrNull { rule ->
            rule.isEnabled && (clean == rule.domainPattern || clean.endsWith(".${rule.domainPattern}") || clean.contains(rule.domainPattern))
        }

        val now = currentTimestamp()
        return if (matchedRule != null) {
            val entry = InterceptLogEntry(
                id = UUID.randomUUID().toString(),
                timestamp = now,
                type = InterceptType.DNS_BLOCK,
                target = clean,
                ruleApplied = "命中规则 [${matchedRule.domainPattern}] -> 0.0.0.0",
                savedTimeMs = 1500L,
                blocked = true
            )
            _uiState.update {
                it.copy(
                    logs = (listOf(entry) + it.logs).take(60),
                    totalDnsBlocked = it.totalDnsBlocked + 1
                )
            }
            true
        } else {
            val entry = InterceptLogEntry(
                id = UUID.randomUUID().toString(),
                timestamp = now,
                type = InterceptType.DNS_BLOCK,
                target = clean,
                ruleApplied = "未命中黑名单 -> 正常放行",
                savedTimeMs = 0L,
                blocked = false
            )
            _uiState.update {
                it.copy(logs = (listOf(entry) + it.logs).take(60))
            }
            false
        }
    }

    fun triggerSimulatedAd(adType: SimulatedAdType) {
        val state = _uiState.value
        if (adType == SimulatedAdType.REWARD_UNLOCK && !state.preserveRewardCallback) {
            val blockedByDns = evaluateDnsQuery(adType.adDomain)
            if (blockedByDns) {
                _uiState.update {
                    it.copy(
                        activeSimulatedAd = null,
                        isEpisodeUnlocked = false,
                        lastSandboxStatusMessage = "解锁失败：激励域名 (${adType.adDomain}) 被阻断，缺失服务端回调"
                    )
                }
                return
            }
        }

        _uiState.update {
            it.copy(
                activeSimulatedAd = adType,
                lastSandboxStatusMessage = "负载注入：${adType.title} (${adType.viewId})"
            )
        }
    }

    fun performAutoSkipOnActiveAd(adType: SimulatedAdType) {
        val state = _uiState.value
        val matchedSelector = state.selectorRules.firstOrNull { rule ->
            rule.isEnabled && (
                rule.matchViewIdKeywords.any { adType.viewId.contains(it, ignoreCase = true) } ||
                    rule.matchTextKeywords.any { adType.buttonText.contains(it, ignoreCase = true) }
                )
        }

        if (matchedSelector == null) {
            _uiState.update {
                it.copy(
                    lastSandboxStatusMessage = "未匹配到启用规则，等待手动处理"
                )
            }
            return
        }

        val now = currentTimestamp()
        val logEntry = InterceptLogEntry(
            id = UUID.randomUUID().toString(),
            timestamp = now,
            type = if (adType == SimulatedAdType.REWARD_UNLOCK) InterceptType.REWARD_CALLBACK else InterceptType.UI_AUTO_SKIP,
            target = "${adType.title} (${adType.viewId})",
            ruleApplied = "匹配规则「${matchedSelector.name}」(${matchedSelector.actionDelayMs}ms)",
            savedTimeMs = adType.durationSec * 1000L,
            blocked = true
        )

        _uiState.update { current ->
            val nextEp = if (adType == SimulatedAdType.REWARD_UNLOCK || adType == SimulatedAdType.EPISODE_INTERSTITIAL) {
                current.currentEpisode + 1
            } else {
                current.currentEpisode
            }
            current.copy(
                activeSimulatedAd = null,
                isEpisodeUnlocked = true,
                currentEpisode = nextEp,
                totalAdsSkipped = current.totalAdsSkipped + 1,
                totalSavedSeconds = current.totalSavedSeconds + adType.durationSec,
                selectorRules = current.selectorRules.map { r ->
                    if (r.id == matchedSelector.id) r.copy(hitCount = r.hitCount + 1) else r
                },
                logs = (listOf(logEntry) + current.logs).take(60),
                lastSandboxStatusMessage = "已触发「${matchedSelector.name}」(${matchedSelector.actionDelayMs}ms)，节省 ${adType.durationSec}s"
            )
        }
    }

    fun manualDismissAd() {
        val ad = _uiState.value.activeSimulatedAd ?: return
        _uiState.update {
            it.copy(
                activeSimulatedAd = null,
                isEpisodeUnlocked = true,
                currentEpisode = it.currentEpisode + 1,
                lastSandboxStatusMessage = "已手动关闭：${ad.title}"
            )
        }
    }

    fun clearLogs() {
        _uiState.update { it.copy(logs = emptyList()) }
    }

    fun generateGkdSubscriptionJson(): String {
        val rules = _uiState.value.selectorRules.filter { it.isEnabled }
        val rulesJson = rules.mapIndexed { index, rule ->
            """
            |        {
            |          "key": $index,
            |          "name": "${rule.name}",
            |          "activityIds": ["${rule.targetPackage}${rule.activityName}"],
            |          "actionDelay": ${rule.actionDelayMs},
            |          "matches": [
            |            "${rule.selectorExpression.replace("\"", "\\\"")}"
            |          ]
            |        }
            """.trimMargin()
        }.joinToString(",\n")

        return """
        |{
        |  "id": 952701,
        |  "name": "PureGuard 节点规则订阅",
        |  "version": 4,
        |  "author": "PureGuard",
        |  "checkUpdateUrl": "local://pureguard/subscription.json5",
        |  "apps": [
        |    {
        |      "id": "com.dragon.read",
        |      "name": "红果免费短剧",
        |      "groups": [
        |$rulesJson
        |      ]
        |    }
        |  ]
        |}
        """.trimMargin()
    }

    private fun currentTimestamp(): String {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        return sdf.format(Date())
    }

    companion object {
        fun defaultDnsRules(): List<DnsRule> = listOf(
            DnsRule(
                id = "dns_1",
                domainPattern = "ad.zijieapi.com",
                category = "字节广告",
                description = "开屏与信息流广告请求接口",
                isEnabled = true
            ),
            DnsRule(
                id = "dns_2",
                domainPattern = "pangolin-sdk-toutiao.com",
                category = "穿山甲 SDK",
                description = "插屏与动态模板预加载域名",
                isEnabled = true
            ),
            DnsRule(
                id = "dns_3",
                domainPattern = "mi.gdt.qq.com",
                category = "优量汇 SDK",
                description = "竞价悬浮横幅广告域名",
                isEnabled = true
            ),
            DnsRule(
                id = "dns_4",
                domainPattern = "dig.bdurl.net",
                category = "遥测上报",
                description = "广告曝光频次与行为统计",
                isEnabled = true
            ),
            DnsRule(
                id = "dns_5",
                domainPattern = "mon.snssdk.com",
                category = "激励回调",
                description = "激励视频验证接口（建议交由节点规则处理）",
                isEnabled = false
            )
        )

        fun defaultSelectorRules(): List<SkipSelectorRule> = listOf(
            SkipSelectorRule(
                id = "sel_1",
                name = "开屏跳过规则",
                targetPackage = "com.dragon.read",
                activityName = ".component.splash.SplashActivity",
                selectorExpression = "@ViewGroup[clickable=true] > TextView[text^=\"跳过\"][id*=\"tt_splash_skip_btn\"]",
                matchTextKeywords = listOf("跳过"),
                matchViewIdKeywords = listOf("tt_splash_skip_btn", "splash_skip"),
                actionDelayMs = 60L,
                isEnabled = true,
                hitCount = 9
            ),
            SkipSelectorRule(
                id = "sel_2",
                name = "插屏关闭规则",
                targetPackage = "com.dragon.read",
                activityName = ".ui.ad.InterstitialAdActivity",
                selectorExpression = "ImageView[id*=\"iv_close_interstitial\" || desc*=\"关闭\"][clickable=true]",
                matchTextKeywords = listOf("关闭"),
                matchViewIdKeywords = listOf("iv_close_interstitial", "close_btn"),
                actionDelayMs = 95L,
                isEnabled = true,
                hitCount = 5
            ),
            SkipSelectorRule(
                id = "sel_3",
                name = "激励完播关闭",
                targetPackage = "com.dragon.read",
                activityName = ".activity.TTRewardVideoActivity",
                selectorExpression = "RelativeLayout[id*=\"tt_video_ad_close_layout\"] > TextView[text*=\"跳过\"]",
                matchTextKeywords = listOf("跳过"),
                matchViewIdKeywords = listOf("tt_video_ad_close_layout"),
                actionDelayMs = 110L,
                isEnabled = true,
                hitCount = 3
            ),
            SkipSelectorRule(
                id = "sel_4",
                name = "底部浮层清理",
                targetPackage = "com.dragon.read",
                activityName = ".plugin.shortseries.ShortSeriesPlayerActivity",
                selectorExpression = "ImageView[id*=\"ad_float_banner_close\"][clickable=true]",
                matchTextKeywords = listOf("关闭", "×"),
                matchViewIdKeywords = listOf("ad_float_banner_close"),
                actionDelayMs = 80L,
                isEnabled = true,
                hitCount = 1
            )
        )

        fun defaultInitialLogs(): List<InterceptLogEntry> = listOf(
            InterceptLogEntry(
                id = "log_init_1",
                timestamp = "09:40:12.104",
                type = InterceptType.UI_AUTO_SKIP,
                target = "开屏广告 (com.dragon.read:id/tt_splash_skip_btn)",
                ruleApplied = "匹配规则「开屏跳过规则」(60ms)",
                savedTimeMs = 5000L,
                blocked = true
            ),
            InterceptLogEntry(
                id = "log_init_2",
                timestamp = "09:40:08.842",
                type = InterceptType.DNS_BLOCK,
                target = "pangolin-sdk-toutiao.com",
                ruleApplied = "命中规则 [pangolin-sdk-toutiao.com] -> 0.0.0.0",
                savedTimeMs = 1500L,
                blocked = true
            ),
            InterceptLogEntry(
                id = "log_init_3",
                timestamp = "09:39:51.310",
                type = InterceptType.REWARD_CALLBACK,
                target = "激励视频 (com.dragon.read:id/tt_video_ad_close_layout)",
                ruleApplied = "匹配规则「激励完播关闭」(110ms)",
                savedTimeMs = 15000L,
                blocked = true
            )
        )
    }
}
