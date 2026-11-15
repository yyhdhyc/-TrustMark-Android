package com.trustmarktool.ui.components

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trustmarktool.model.TaskProgress
import com.trustmarktool.model.TaskState

/**
 * Shared by generation and decoding. Running: spinner/title on the left, cancel
 * on the right, clipped progress and current filename below. Terminal states:
 * no spinner, summary/details/error only.
 */
@Composable
fun TaskProgressCard(
    task: TaskProgress,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = task.state == TaskState.RUNNING
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (task.state) {
                    TaskState.RUNNING -> CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )

                    TaskState.COMPLETED -> Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )

                    TaskState.CANCELLED -> Icon(
                        Icons.Filled.Stop,
                        contentDescription = null,
                    )

                    TaskState.ERROR -> Icon(
                        Icons.Filled.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when (task.state) {
                        TaskState.RUNNING -> task.phase.ifBlank { "正在处理中" }
                        TaskState.COMPLETED -> "处理完成"
                        TaskState.CANCELLED -> "已取消"
                        TaskState.ERROR -> "处理失败"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                if (running) {
                    TextButton(onClick = onCancel) {
                        Text("取消")
                    }
                } else {
                    Text(
                        "${task.completed}/${task.total}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            if (running) {
                ProgressTrack(task = task)
                Text(
                    task.currentFile.ifBlank { "准备中…" },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                task.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                    Text(summary, style = MaterialTheme.typography.bodyMedium)
                }
                task.details?.takeIf { it.isNotBlank() }?.let { details ->
                    Text(
                        details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                task.error?.takeIf { it.isNotBlank() }?.let { error ->
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressTrack(task: TaskProgress) {
    // The outer Box supplies the only rounded silhouette. Clipping both the
    // track and indicator prevents Material3's stop indicator/gap from
    // producing the visible dot or overhang at 100%.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (task.indeterminate) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
                strokeCap = StrokeCap.Butt,
                gapSize = 0.dp,
            )
        } else {
            LinearProgressIndicator(
                progress = { task.fraction.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
                strokeCap = StrokeCap.Butt,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}
