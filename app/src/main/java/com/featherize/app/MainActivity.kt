package com.featherize.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.featherize.app.ui.screen.GalleryScreen
import com.featherize.app.ui.screen.ProgressScreen
import com.featherize.app.ui.screen.ResultScreen
import com.featherize.app.ui.theme.FeatherizeTheme
import com.featherize.app.viewmodel.CompressionViewModel
import com.featherize.app.viewmodel.ScreenState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: CompressionViewModel by viewModels()

    private val mediaPermissions: Array<String>
        get() = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            else ->
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val readGranted = results.entries.any {
                it.key != Manifest.permission.POST_NOTIFICATIONS && it.value
            }
            viewModel.onPermissionResult(readGranted)
        }

        val writePermissionLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            viewModel.onWritePermissionResult(result.resultCode == RESULT_OK)
        }
        lifecycleScope.launch {
            viewModel.writePermissionRequest.collect { request ->
                writePermissionLauncher.launch(request)
            }
        }

        val alreadyGranted = mediaPermissions.any {
            it != Manifest.permission.POST_NOTIFICATIONS &&
                checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (alreadyGranted) {
            viewModel.onPermissionResult(true)
        } else {
            permissionLauncher.launch(mediaPermissions)
        }

        setContent {
            FeatherizeTheme {
                val uiState by viewModel.uiState.collectAsState()

                when (uiState.screen) {
                    ScreenState.PICKING -> {
                        BackHandler(enabled = uiState.selectedUris.isNotEmpty()) { viewModel.clearSelection() }
                        GalleryScreen(
                            media = uiState.filteredGalleryMedia,
                            selected = uiState.selectedUris,
                            preset = uiState.preset,
                            permissionGranted = uiState.permissionGranted,
                            isLoading = uiState.isLoadingGallery,
                            typeFilter = uiState.typeFilter,
                            sizeFilter = uiState.sizeFilter,
                            sortOption = uiState.sortOption,
                            manageStorageGranted = uiState.manageStorageGranted,
                            onPresetChange = viewModel::setPreset,
                            onToggle = viewModel::toggleSelection,
                            onRequestPermission = { permissionLauncher.launch(mediaPermissions) },
                            onTypeFilterChange = viewModel::setTypeFilter,
                            onSizeFilterChange = viewModel::setSizeFilter,
                            onSortOptionChange = viewModel::setSortOption,
                            onOpenAllFilesAccessSettings = { openAllFilesAccessSettings() },
                            onStart = viewModel::startCompression,
                        )
                    }
                    ScreenState.PROGRESS -> {
                        // No BackHandler: compression runs in CompressionService independent of
                        // this Activity, so leaving the app (or pressing back) is safe — it just
                        // keeps going in the background with its own notification.
                        ProgressScreen(items = uiState.items)
                    }
                    ScreenState.RESULT -> {
                        BackHandler(enabled = true) { viewModel.reset() }
                        ResultScreen(
                            items = uiState.items,
                            exportMode = uiState.exportMode,
                            onExportModeChange = viewModel::setExportMode,
                            onExport = viewModel::exportResults,
                            onDone = viewModel::reset,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshManageStorageStatus()
    }

    private fun openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }
}
