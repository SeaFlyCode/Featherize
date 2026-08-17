package com.featherize.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.featherize.app.domain.CompressionStatus
import com.featherize.app.domain.MediaItem
import com.featherize.app.domain.MediaType

@Composable
fun MediaCard(
    item: MediaItem,
    onRemove: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (item.type == MediaType.IMAGE) Icons.Filled.Image else Icons.Filled.Videocam,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                )
                val sizeLabel = if (item.resultSizeBytes != null) {
                    "${formatBytes(item.originalSizeBytes)} → ${formatBytes(item.resultSizeBytes)} · ${formatSavings(item.savedRatio ?: 0f)}"
                } else {
                    formatBytes(item.originalSizeBytes)
                }
                Text(
                    text = sizeLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.status == CompressionStatus.RUNNING) {
                    LinearProgressIndicator(
                        progress = { item.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    )
                }
                if (item.errorMessage != null) {
                    Text(
                        text = item.errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            when {
                item.errorMessage != null -> Icon(
                    imageVector = Icons.Filled.Error,
                    contentDescription = "Erreur",
                    tint = MaterialTheme.colorScheme.error,
                )
                item.status == CompressionStatus.DONE || item.status == CompressionStatus.EXPORTED -> Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Terminé",
                    tint = Color(0xFF4CAF50),
                )
                item.status == CompressionStatus.RUNNING -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                item.status == CompressionStatus.PENDING && onRemove != null -> IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Close, contentDescription = "Retirer")
                }
            }
        }
    }
}
