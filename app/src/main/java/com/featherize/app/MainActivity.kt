package com.featherize.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        // The app is always dark (FeatherizeTheme has no light variant), so the system-bar icons
        // must be forced light regardless of the device's own light/dark setting — SystemBarStyle
        // .auto() would otherwise pick dark icons on a light-system device, invisible over our
        // permanently dark background.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )

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
                            exportMode = uiState.exportMode,
                            permissionGranted = uiState.permissionGranted,
                            isLoading = uiState.isLoadingGallery,
                            typeFilter = uiState.typeFilter,
                            sizeFilter = uiState.sizeFilter,
                            sortOption = uiState.sortOption,
                            manageStorageGranted = uiState.manageStorageGranted,
                            batteryOptimizationIgnored = uiState.batteryOptimizationIgnored,
                            onPresetChange = viewModel::setPreset,
                            onExportModeChange = viewModel::setExportMode,
                            onToggle = viewModel::toggleSelection,
                            onRequestPermission = { permissionLauncher.launch(mediaPermissions) },
                            onTypeFilterChange = viewModel::setTypeFilter,
                            onSizeFilterChange = viewModel::setSizeFilter,
                            onSortOptionChange = viewModel::setSortOption,
                            onOpenAllFilesAccessSettings = { openAllFilesAccessSettings() },
                            onOpenBatteryOptimizationSettings = { openBatteryOptimizationSettings() },
                            onStart = viewModel::startCompression,
                        )
                    }
                    ScreenState.PROGRESS -> {
                        // No BackHandler: compression runs in CompressionService independent of
                        // this Activity, so leaving the app (or pressing back) is safe — it just
                        // keeps going in the background with its own notification.
                        ProgressScreen(items = uiState.items, onCancel = viewModel::cancelCompression)
                    }
                    ScreenState.RESULT -> {
                        BackHandler(enabled = true) { viewModel.reset() }
                        ResultScreen(
                            items = uiState.items,
                            exportMode = uiState.exportMode,
                            isExporting = uiState.isExporting,
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
        viewModel.refreshBatteryOptimizationStatus()
    }

    private fun openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    @SuppressLint("BatteryLife")
    private fun openBatteryOptimizationSettings() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }
}
