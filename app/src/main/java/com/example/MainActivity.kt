package com.example

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.accessibility.PureGuardAccessibilityService
import com.example.ui.DashboardScreen
import com.example.ui.DnsRulesScreen
import com.example.ui.DramaSandboxScreen
import com.example.ui.GkdBuilderScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.GuardViewModel
import com.example.vpn.LocalDnsVpnService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                PureGuardApp()
            }
        }
    }
}

private enum class GuardNavTab(
    val title: String,
    val icon: ImageVector,
    val tag: String
) {
    DASHBOARD("总览", Icons.Default.Shield, "nav_tab_dashboard"),
    SANDBOX("沙盒测试", Icons.Default.PlayCircleOutline, "nav_tab_sandbox"),
    DNS_RULES("DNS 过滤", Icons.Default.Dns, "nav_tab_dns"),
    GKD_BUILDER("规则配置", Icons.Default.Code, "nav_tab_gkd")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PureGuardApp(
    viewModel: GuardViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isVpnRunning by LocalDnsVpnService.isRunning.collectAsStateWithLifecycle()
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }

    // 页面冷启动时初次检测
    LaunchedEffect(Unit) {
        viewModel.updateAccessibilityStatus(context)
    }

    // 核心修复：当从系统设置页面授权返回应用时（ON_RESUME），立即自动重检授权状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.updateAccessibilityStatus(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    if (selectedTabIndex != 0) {
        BackHandler {
            selectedTabIndex = 0
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val startIntent = Intent(context, LocalDnsVpnService::class.java).apply {
                action = LocalDnsVpnService.ACTION_START
            }
            context.startService(startIntent)
        }
    }

    val handleToggleVpn: () -> Unit = {
        if (isVpnRunning) {
            val stopIntent = Intent(context, LocalDnsVpnService::class.java).apply {
                action = LocalDnsVpnService.ACTION_STOP
            }
            context.startService(stopIntent)
        } else {
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
                vpnPermissionLauncher.launch(prepareIntent)
            } else {
                val startIntent = Intent(context, LocalDnsVpnService::class.java).apply {
                    action = LocalDnsVpnService.ACTION_START
                }
                context.startService(startIntent)
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = "PureGuard",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "PureGuard",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                },
                actions = {
                    Surface(
                        color = if (uiState.autoSkipEngineEnabled) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Text(
                            text = if (uiState.autoSkipEngineEnabled) "引擎已启用" else "引擎已暂停",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (uiState.autoSkipEngineEnabled) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar {
                GuardNavTab.entries.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.title
                            )
                        },
                        label = { Text(tab.title) },
                        modifier = Modifier.testTag(tab.tag)
                    )
                }
            }
        }
    ) { innerPadding ->
        when (GuardNavTab.entries[selectedTabIndex]) {
            GuardNavTab.DASHBOARD -> {
                DashboardScreen(
                    uiState = uiState,
                    isVpnRunning = isVpnRunning,
                    onToggleVpn = handleToggleVpn,
                    onOpenAccessibilitySettings = {
                        PureGuardAccessibilityService.openAccessibilitySettings(context)
                    },
                    onOpenAppDetailsSettings = {
                        PureGuardAccessibilityService.openAppDetailsSettings(context)
                    },
                    onRefreshAccessibility = {
                        viewModel.updateAccessibilityStatus(context)
                    },
                    onJumpToSandbox = { selectedTabIndex = GuardNavTab.SANDBOX.ordinal },
                    onClearLogs = viewModel::clearLogs,
                    modifier = Modifier.padding(innerPadding)
                )
            }
            GuardNavTab.SANDBOX -> {
                DramaSandboxScreen(
                    uiState = uiState,
                    onToggleAutoSkip = viewModel::toggleAutoSkipEngine,
                    onTogglePreserveReward = viewModel::togglePreserveRewardCallback,
                    onToggleFastSkip = viewModel::toggleFastSkipBlackScreen,
                    onTriggerAd = viewModel::triggerSimulatedAd,
                    onAutoSkipExecuted = viewModel::performAutoSkipOnActiveAd,
                    onCountdownFinished = viewModel::onCountdownFinished,
                    onManualDismissAd = viewModel::manualDismissAd,
                    modifier = Modifier.padding(innerPadding)
                )
            }
            GuardNavTab.DNS_RULES -> {
                DnsRulesScreen(
                    uiState = uiState,
                    isVpnRunning = isVpnRunning,
                    onToggleVpn = handleToggleVpn,
                    onToggleRule = viewModel::toggleDnsRule,
                    onAddRule = viewModel::addCustomDnsRule,
                    onTestDomainQuery = viewModel::evaluateDnsQuery,
                    modifier = Modifier.padding(innerPadding)
                )
            }
            GuardNavTab.GKD_BUILDER -> {
                GkdBuilderScreen(
                    uiState = uiState,
                    onToggleSelectorRule = viewModel::toggleSelectorRule,
                    onAddSelectorRule = viewModel::addCustomSelectorRule,
                    onGenerateJson = viewModel::generateGkdSubscriptionJson,
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }
}
