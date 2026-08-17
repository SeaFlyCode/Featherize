package com.featherize.app.ui.screen

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.featherize.app.domain.CompressionPreset
import com.featherize.app.domain.GalleryMedia
import com.featherize.app.domain.MediaType
import com.featherize.app.domain.MediaTypeFilter
import com.featherize.app.domain.SizeFilter
import com.featherize.app.domain.SortOption
import com.featherize.app.ui.component.PresetSelector
import com.featherize.app.ui.component.formatBytes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    media: List<GalleryMedia>,
    selected: Set<Uri>,
    preset: CompressionPreset,
    permissionGranted: Boolean,
    isLoading: Boolean,
    typeFilter: MediaTypeFilter,
    sizeFilter: SizeFilter,
    sortOption: SortOption,
    manageStorageGranted: Boolean,
    onPresetChange: (CompressionPreset) -> Unit,
    onToggle: (Uri) -> Unit,
    onRequestPermission: () -> Unit,
    onTypeFilterChange: (MediaTypeFilter) -> Unit,
    onSizeFilterChange: (SizeFilter) -> Unit,
    onSortOptionChange: (SortOption) -> Unit,
    onOpenAllFilesAccessSettings: () -> Unit,
    onStart: () -> Unit,
) {
    var showAllFilesDialog by remember { mutableStateOf(false) }
    var showStartConfirmDialog by remember { mutableStateOf(false) }

    if (showAllFilesDialog) {
        AllFilesAccessDialog(
            granted = manageStorageGranted,
            onOpenSettings = {
                showAllFilesDialog = false
                onOpenAllFilesAccessSettings()
            },
            onDismiss = { showAllFilesDialog = false },
        )
    }

    if (showStartConfirmDialog) {
        StartConfirmDialog(
            count = selected.size,
            preset = preset,
            onConfirm = {
                showStartConfirmDialog = false
                onStart()
            },
            onDismiss = { showStartConfirmDialog = false },
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(if (selected.isEmpty()) "Featherize" else "${selected.size} sélectionné(s)")
                    },
                    actions = {
                        if (permissionGranted) {
                            IconButton(onClick = { showAllFilesDialog = true }) {
                                Icon(
                                    imageVector = if (manageStorageGranted) Icons.Filled.LockOpen else Icons.Filled.Lock,
                                    contentDescription = "Accès aux fichiers",
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                if (permissionGranted) {
                    FilterBar(
                        typeFilter = typeFilter,
                        sizeFilter = sizeFilter,
                        sortOption = sortOption,
                        onTypeChange = onTypeFilterChange,
                        onSizeChange = onSizeFilterChange,
                        onSortChange = onSortOptionChange,
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                !permissionGranted -> PermissionState(onRequestPermission, Modifier.fillMaxSize())
                isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                media.isEmpty() -> EmptyGalleryState(Modifier.fillMaxSize())
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(2.dp, 2.dp, 2.dp, if (selected.isEmpty()) 2.dp else 160.dp),
                ) {
                    items(media, key = { it.uri }) { item ->
                        GalleryTile(
                            item = item,
                            isSelected = item.uri in selected,
                            onClick = { onToggle(item.uri) },
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = selected.isNotEmpty(),
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 8.dp,
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Force de compression",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        PresetSelector(selected = preset, onSelect = onPresetChange, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { showStartConfirmDialog = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Compresser ${selected.size} fichier(s)")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBar(
    typeFilter: MediaTypeFilter,
    sizeFilter: SizeFilter,
    sortOption: SortOption,
    onTypeChange: (MediaTypeFilter) -> Unit,
    onSizeChange: (SizeFilter) -> Unit,
    onSortChange: (SortOption) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MediaTypeFilter.entries.forEach { filter ->
            FilterChip(
                selected = typeFilter == filter,
                onClick = { onTypeChange(filter) },
                label = { Text(filter.label) },
            )
        }

        var sizeMenuExpanded by remember { mutableStateOf(false) }
        Box {
            FilterChip(
                selected = sizeFilter != SizeFilter.ALL,
                onClick = { sizeMenuExpanded = true },
                label = { Text(sizeFilter.label) },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            )
            DropdownMenu(expanded = sizeMenuExpanded, onDismissRequest = { sizeMenuExpanded = false }) {
                SizeFilter.entries.forEach { filter ->
                    DropdownMenuItem(
                        text = { Text(filter.label) },
                        onClick = {
                            onSizeChange(filter)
                            sizeMenuExpanded = false
                        },
                    )
                }
            }
        }

        var sortMenuExpanded by remember { mutableStateOf(false) }
        Box {
            FilterChip(
                selected = sortOption != SortOption.DATE_NEWEST,
                onClick = { sortMenuExpanded = true },
                label = { Text("Trier : ${sortOption.label}") },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            )
            DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = { sortMenuExpanded = false }) {
                SortOption.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            onSortChange(option)
                            sortMenuExpanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun GalleryTile(item: GalleryMedia, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick),
    ) {
        val context = LocalContext.current
        // Cache key includes the modification time so a replaced file (same Uri, new bytes)
        // doesn't keep showing Coil's stale cached thumbnail.
        val cacheKey = "${item.uri}-${item.dateModifiedSeconds}"
        AsyncImage(
            model = remember(cacheKey) {
                ImageRequest.Builder(context)
                    .data(item.uri)
                    .memoryCacheKey(cacheKey)
                    .diskCacheKey(cacheKey)
                    .build()
            },
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        if (item.type == MediaType.VIDEO) {
            Icon(
                imageVector = Icons.Filled.PlayCircle,
                contentDescription = "Vidéo",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .size(20.dp),
            )
        }

        Text(
            text = formatBytes(item.sizeBytes),
            color = Color.White,
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )

        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
            )
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "Sélectionné",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(MaterialTheme.colorScheme.background, CircleShape),
            )
        }
    }
}

@Composable
private fun PermissionState(onRequestPermission: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Featherize a besoin d'accéder à tes photos et vidéos pour les compresser",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRequestPermission) {
                Text("Autoriser l'accès")
            }
        }
    }
}

@Composable
private fun EmptyGalleryState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.PhotoLibrary,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Aucun média ne correspond à ce filtre",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun StartConfirmDialog(
    count: Int,
    preset: CompressionPreset,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.PlayCircle, contentDescription = null) },
        title = { Text("Lancer la compression ?") },
        text = {
            Text("$count fichier(s) vont être compressés avec le preset \"${preset.label}\".")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Compresser") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}

@Composable
private fun AllFilesAccessDialog(
    granted: Boolean,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (granted) Icons.Filled.LockOpen else Icons.Filled.Lock, contentDescription = null) },
        title = { Text(if (granted) "Accès complet activé" else "Éviter les popups répétées") },
        text = {
            Text(
                if (granted) {
                    "Featherize peut remplacer tes photos et vidéos sans demander de confirmation à chaque fois."
                } else {
                    "Par défaut, Android demande une confirmation pour \"Remplacer l'original\". " +
                        "Active l'accès complet aux fichiers dans les réglages système pour ne " +
                        "plus jamais voir cette demande."
                },
            )
        },
        confirmButton = {
            if (!granted) {
                TextButton(onClick = onOpenSettings) { Text("Ouvrir les réglages") }
            } else {
                TextButton(onClick = onDismiss) { Text("OK") }
            }
        },
        dismissButton = {
            if (!granted) {
                TextButton(onClick = onDismiss) { Text("Plus tard") }
            }
        },
    )
}
