package com.featherize.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.featherize.app.domain.MediaItem
import com.featherize.app.ui.component.MediaCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(items: List<MediaItem>) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Compression en cours…") })
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                val done = items.count { it.progress >= 1f }
                Text(
                    "$done / ${items.size} terminé(s)",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            items(items, key = { it.uri }) { item ->
                MediaCard(item = item)
            }
        }
    }
}
