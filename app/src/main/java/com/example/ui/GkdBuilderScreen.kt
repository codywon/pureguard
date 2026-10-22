package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.SkipSelectorRule
import com.example.viewmodel.GuardUiState

@Composable
fun GkdBuilderScreen(
    uiState: GuardUiState,
    onToggleSelectorRule: (String) -> Unit,
    onAddSelectorRule: (String, String, String, String) -> Unit,
    onGenerateJson: () -> String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var ruleName by remember { mutableStateOf("") }
    var targetPackage by remember { mutableStateOf("com.dragon.read") }
    var viewIdKey by remember { mutableStateOf("tt_splash_skip_btn") }
    var textKey by remember { mutableStateOf("跳过") }
    var showExportPreview by remember { mutableStateOf(false) }

    val exportedJson = remember(uiState.selectorRules, showExportPreview) {
        onGenerateJson()
    }

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
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = "规则导出",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "GKD 订阅规则导出",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "基于 ViewId、Text 与 Clickable 属性构建选择器，支持导出标准 JSON 订阅配置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("GKD_Subscription", exportedJson))
                                Toast.makeText(context, "已复制订阅 JSON", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.testTag("copy_gkd_json_btn")
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "复制 JSON", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("复制 JSON")
                        }
                        FilledTonalButton(
                            onClick = { showExportPreview = !showExportPreview },
                            modifier = Modifier.testTag("toggle_json_preview_btn")
                        ) {
                            Text(if (showExportPreview) "收起源码" else "预览源码")
                        }
                    }
                }
            }
        }

        if (showExportPreview) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF0F172A))
                        .padding(14.dp)
                        .testTag("gkd_json_preview_box")
                ) {
                    Text(
                        text = exportedJson,
                        color = Color(0xFFA7F3D0),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "新增规则",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "新增选择器规则",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = ruleName,
                            onValueChange = { ruleName = it },
                            label = { Text("规则名称") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("gkd_rule_name_input")
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = targetPackage,
                            onValueChange = { targetPackage = it },
                            label = { Text("应用包名") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = textKey,
                            onValueChange = { textKey = it },
                            label = { Text("匹配文本") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = viewIdKey,
                            onValueChange = { viewIdKey = it },
                            label = { Text("节点 ID") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                if (ruleName.isNotBlank()) {
                                    onAddSelectorRule(ruleName, targetPackage, viewIdKey, textKey)
                                    ruleName = ""
                                }
                            },
                            modifier = Modifier.testTag("add_gkd_rule_btn")
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
                text = "选择器规则库 (${uiState.selectorRules.count { it.isEnabled }}/${uiState.selectorRules.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }

        items(uiState.selectorRules, key = { it.id }) { rule ->
            SelectorRuleCard(
                rule = rule,
                onToggle = { onToggleSelectorRule(rule.id) }
            )
        }

        item {
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SelectorRuleCard(
    rule: SkipSelectorRule,
    onToggle: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = rule.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${rule.targetPackage} · 命中 ${rule.hitCount} 次",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = rule.isEnabled,
                    onCheckedChange = { onToggle() },
                    modifier = Modifier.testTag("toggle_selector_${rule.id}")
                )
            }

            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = rule.selectorExpression,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}
