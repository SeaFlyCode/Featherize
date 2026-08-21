package com.featherize.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.featherize.app.domain.MediaItem
import com.featherize.app.ui.component.MediaCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(items: List<MediaItem>, onCancel: () -> Unit) {
    var showCancelConfirm by remember { mutableStateOf(false) }

    if (showCancelConfirm) {
        AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            title = { Text("Annuler la compression ?") },
            text = { Text("Le fichier en cours sera terminé, les suivants seront annulés.") },
            confirmButton = {
                TextButton(onClick = {
                    showCancelConfirm = false
                    onCancel()
                }) { Text("Annuler la suite") }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirm = false }) { Text("Continuer") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Compression en cours…") },
                actions = {
                    IconButton(onClick = { showCancelConfirm = true }) {
                        Icon(Icons.Filled.Close, contentDescription = "Annuler")
                    }
                },
            )
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
