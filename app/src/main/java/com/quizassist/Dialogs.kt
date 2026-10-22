package com.quizassist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 公告弹窗。
 *
 * - normal / important：有「确认」和「不再提醒」，点确认只是本次关闭；
 * - force：只有「我知道了」，点空白处和返回键都不关，必须显式确认。
 */
@Composable
fun AnnouncementDialog(
    announcement: Announcement,
    onConfirm: () -> Unit,
    onNeverRemind: () -> Unit,
    onOpenLink: (String) -> Unit = {},
) {
    val force = announcement.isForce()
    val hasLink = announcement.linkUrl.isNotBlank()
    AlertDialog(
        onDismissRequest = { if (!force) onConfirm() },
        title = {
            Text(
                announcement.title.ifBlank { if (force) "重要通知" else "公告" },
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = androidx.compose.ui.Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(announcement.content)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (hasLink) onOpenLink(announcement.linkUrl)
                    onConfirm()
                },
            ) {
                Text(announcement.buttonText.ifBlank { if (hasLink) "查看详情" else "确认" })
            }
        },
        dismissButton = if (force) {
            null
        } else {
            { TextButton(onClick = onNeverRemind) { Text("不再提醒") } }
        },
    )
}

/** 版本更新弹窗；required = true 时不能取消，只能去更新。 */
@Composable
fun UpdateDialog(
    title: String,
    content: String,
    onUpdate: () -> Unit,
    onCancel: () -> Unit,
    required: Boolean = false,
    altUrl: String = "",
    onOpenAlt: () -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = { if (!required) onCancel() },
        title = { Text(title.ifBlank { "发现新版本" }, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(content)
                if (altUrl.isNotBlank()) {
                    Text(
                        "直链下载速度较慢，优先用上面的网盘。",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.ui.graphics.Color(0xFF6B7280),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onUpdate) { Text("去下载") } },
        // 备用直链放在 dismiss 位：主按钮走网盘，备选走服务器直链。
        // 强制更新时两个都不给取消，但仍然允许换源。
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (altUrl.isNotBlank()) {
                    TextButton(onClick = onOpenAlt) { Text("直链下载（较慢）") }
                }
                if (!required) {
                    TextButton(onClick = onCancel) { Text("取消") }
                }
            }
        },
    )
}
