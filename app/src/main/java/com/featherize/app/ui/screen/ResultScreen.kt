package com.featherize.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.featherize.app.data.ExportMode
import com.featherize.app.domain.CompressionStatus
import com.featherize.app.domain.MediaItem
import com.featherize.app.ui.component.MediaCard
import com.featherize.app.ui.component.formatBytes
import com.featherize.app.ui.component.formatSavings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    items: List<MediaItem>,
    exportMode: ExportMode,
    isExporting: Boolean,
    onExport: () -> Unit,
    onDone: () -> Unit,
) {
    val successItems = items.filter { it.status == CompressionStatus.DONE }
    val totalOriginal = items.sumOf { it.originalSizeBytes }
    val totalResult = successItems.sumOf { it.resultSizeBytes ?: 0L }
    val ratio = if (totalOriginal > 0) 1f - (totalResult.toFloat() / totalOriginal) else 0f

    Scaffold(
        topBar = { TopAppBar(title = { Text("Résultat") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "${formatSavings(ratio)} au total",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${formatBytes(totalOriginal)} → ${formatBytes(totalResult)} (${successItems.size}/${items.size} fichiers)",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            Text(
                if (exportMode == ExportMode.REPLACE) "Mode : remplacer l'original" else "Mode : nouvelle copie",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.uri }) { item ->
                    MediaCard(item = item)
                }
            }

            val alreadyExported = items.any { it.status == CompressionStatus.EXPORTED }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onDone, modifier = Modifier.weight(1f)) {
                    Text("Nouvelle compression")
                }
                Button(onClick = onExport, enabled = !isExporting, modifier = Modifier.weight(1f)) {
                    Text(if (alreadyExported) "Exporté ✓" else if (isExporting) "Export…" else "Exporter")
                }
            }
        }
    }
}
