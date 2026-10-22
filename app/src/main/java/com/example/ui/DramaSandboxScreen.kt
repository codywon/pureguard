package com.example.ui

import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.SimulatedAdType
import com.example.viewmodel.GuardUiState
import kotlinx.coroutines.delay

@Composable
fun DramaSandboxScreen(
    uiState: GuardUiState,
    onToggleAutoSkip: (Boolean) -> Unit,
    onTogglePreserveReward: (Boolean) -> Unit,
    onTriggerAd: (SimulatedAdType) -> Unit,
    onAutoSkipExecuted: (SimulatedAdType) -> Unit,
    onManualDismissAd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeAd = uiState.activeSimulatedAd
    var countdown by remember(activeAd) { mutableIntStateOf(activeAd?.durationSec ?: 0) }

    LaunchedEffect(activeAd, uiState.autoSkipEngineEnabled) {
        if (activeAd != null && uiState.autoSkipEngineEnabled) {
            delay(180L)
            onAutoSkipExecuted(activeAd)
        }
    }

    LaunchedEffect(activeAd, uiState.autoSkipEngineEnabled) {
        if (activeAd != null && !uiState.autoSkipEngineEnabled) {
            while (countdown > 0) {
                delay(1000L)
                countdown--
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = "节点匹配引擎",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "UI 节点自动匹配",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "监听视图节点树，匹配目标 ID 与文本后自动执行点击",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.autoSkipEngineEnabled,
                        onCheckedChange = onToggleAutoSkip,
                        modifier = Modifier.testTag("sandbox_autoskip_switch")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "保留激励回调链路",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "放行验证接口，避免直接阻断网络导致剧集解锁异常",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.preserveRewardCallback,
                        onCheckedChange = onTogglePreserveReward,
                        modifier = Modifier.testTag("sandbox_preserve_reward_switch")
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(290.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0F172A),
                            Color(0xFF1E1B4B),
                            Color(0xFF090D16)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(20.dp)
                )
                .testTag("drama_player_viewport")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(50)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "播放中",
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "流媒体测试源 · EP.${uiState.currentEpisode}",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Surface(
                        color = if (uiState.autoSkipEngineEnabled) {
                            Color(0xFF065F46).copy(alpha = 0.85f)
                        } else {
                            Color(0xFF78350F).copy(alpha = 0.85f)
                        },
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            text = if (uiState.autoSkipEngineEnabled) "自动匹配" else "手动模式",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (uiState.isEpisodeUnlocked) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "正常播放",
                            tint = Color(0xFF34D399),
                            modifier = Modifier.size(42.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "视频流正常播放",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "选择下方测试项模拟广告图层注入",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "解锁异常",
                            tint = Color(0xFFFBBF24),
                            modifier = Modifier.size(42.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "EP.${uiState.currentEpisode + 1} 解锁异常 (回调阻断)",
                            color = Color(0xFFFDE68A),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "请启用上方「保留激励回调链路」以恢复正常解锁",
                            color = Color(0xFFCBD5E1),
                            fontSize = 12.sp
                        )
                    }
                }

                Surface(
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "状态: ${uiState.lastSandboxStatusMessage}",
                            color = Color(0xFFA7F3D0),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = activeAd != null,
                enter = fadeIn(spring()) + scaleIn(spring()),
                exit = fadeOut(spring()) + scaleOut(spring()),
                modifier = Modifier.fillMaxSize()
            ) {
                if (activeAd != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.88f))
                            .padding(18.dp)
                    ) {
                        Surface(
                            color = Color(0xFFEF4444),
                            shape = RoundedCornerShape(50),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .clickable { onManualDismissAd() }
                                .testTag("simulated_ad_skip_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${activeAd.buttonText} (${countdown}s)",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "关闭",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                color = Color(0xFF7F1D1D),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "广告视图负载",
                                    color = Color(0xFFFECACA),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            Text(
                                text = activeAd.title,
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = activeAd.subtitle,
                                color = Color(0xFFCBD5E1),
                                fontSize = 13.sp
                            )
                            Surface(
                                color = Color(0xFF1E293B),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = "ViewId: ${activeAd.viewId}",
                                        color = Color(0xFF38BDF8),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = "Domain: ${activeAd.adDomain}",
                                        color = Color(0xFFFBBF24),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Text(
            text = "广告负载注入测试",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AdTriggerButton(
                    adType = SimulatedAdType.SPLASH_COUNTDOWN,
                    onClick = { onTriggerAd(SimulatedAdType.SPLASH_COUNTDOWN) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("trigger_splash_ad_btn")
                )
                AdTriggerButton(
                    adType = SimulatedAdType.EPISODE_INTERSTITIAL,
                    onClick = { onTriggerAd(SimulatedAdType.EPISODE_INTERSTITIAL) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("trigger_interstitial_ad_btn")
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AdTriggerButton(
                    adType = SimulatedAdType.REWARD_UNLOCK,
                    onClick = { onTriggerAd(SimulatedAdType.REWARD_UNLOCK) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("trigger_reward_ad_btn")
                )
                AdTriggerButton(
                    adType = SimulatedAdType.BOTTOM_BANNER,
                    onClick = { onTriggerAd(SimulatedAdType.BOTTOM_BANNER) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("trigger_banner_ad_btn")
                )
            }
        }
    }
}

@Composable
private fun AdTriggerButton(
    adType: SimulatedAdType,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilledTonalButton(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.height(64.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = adType.title,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${adType.title} (${adType.durationSec}s)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = adType.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}
