package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.DnsRule
import com.example.viewmodel.GuardUiState

@Composable
fun DnsRulesScreen(
    uiState: GuardUiState,
    isVpnRunning: Boolean,
    onToggleVpn: () -> Unit,
    onToggleRule: (String) -> Unit,
    onAddRule: (String, String, String) -> Unit,
    onTestDomainQuery: (String) -> Boolean,
    modifier: Modifier = Modifier
) {
    var testDomainInput by remember { mutableStateOf("ad.zijieapi.com") }
    var testResultBanner by remember { mutableStateOf<String?>(null) }
    var newDomainInput by remember { mutableStateOf("") }
    var newDescInput by remember { mutableStateOf("") }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isVpnRunning) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = "VPN 状态",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isVpnRunning) "本地 DNS 隧道：运行中" else "本地 DNS 隧道：未启动",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "通过 VpnService 接管本地虚拟 DNS (10.111.222.2)，业务流量直连",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = onToggleVpn,
                        modifier = Modifier.testTag("dns_screen_vpn_toggle_btn")
                    ) {
                        Text(if (isVpnRunning) "停止" else "启动")
                    }
                }
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.NetworkCheck,
                            contentDescription = "域名解析测试",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "域名解析测试",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = testDomainInput,
                            onValueChange = { testDomainInput = it },
                            label = { Text("测试域名") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("dns_test_domain_input")
                        )
                        FilledTonalButton(
                            onClick = {
                                val blocked = onTestDomainQuery(testDomainInput)
                                testResultBanner = if (blocked) {
                                    "已拦截 [$testDomainInput] -> 0.0.0.0"
                                } else {
                                    "已放行 [$testDomainInput] -> 正常解析"
                                }
                            },
                            modifier = Modifier.testTag("dns_test_query_btn")
                        ) {
                            Text("测试")
                        }
                    }

                    testResultBanner?.let { banner ->
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = banner,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "新增拦截规则",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = newDomainInput,
                            onValueChange = { newDomainInput = it },
                            label = { Text("域名后缀 (如 pglstatp-toutiao.com)") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("add_dns_domain_input")
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newDescInput,
                            onValueChange = { newDescInput = it },
                            label = { Text("备注") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                if (newDomainInput.isNotBlank()) {
                                    onAddRule(newDomainInput, "自定义", newDescInput)
                                    newDomainInput = ""
                                    newDescInput = ""
                                }
                            },
                            modifier = Modifier.testTag("add_dns_rule_submit_btn")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "添加")
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("添加")
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "域名规则库 (${uiState.dnsRules.count { it.isEnabled }}/${uiState.dnsRules.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        items(uiState.dnsRules, key = { it.id }) { rule ->
            DnsRuleItemCard(
                rule = rule,
                onToggle = { onToggleRule(rule.id) }
            )
        }

        item {
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun DnsRuleItemCard(
    rule: DnsRule,
    onToggle: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (rule.isEnabled) Icons.Default.GppBad else Icons.Default.Dns,
                        contentDescription = rule.domainPattern,
                        tint = if (rule.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = rule.domainPattern,
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${rule.category} · ${rule.description}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = rule.isEnabled,
                onCheckedChange = { onToggle() },
                modifier = Modifier.testTag("toggle_dns_${rule.id}")
            )
        }
    }
}
