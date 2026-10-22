package com.trustmarktool.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trustmarktool.MainViewModel
import com.trustmarktool.model.MainUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showBatchDialog by remember { mutableStateOf(false) }

    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingPermissions by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingDeniedAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val action = pendingAction
        val deniedAction = pendingDeniedAction
        val requested = pendingPermissions
        pendingAction = null
        pendingDeniedAction = null
        pendingPermissions = emptyList()

        // 通知权限只是体验增强；API <= 28 的旧版存储权限才可能影响保存。
        val required = requested.filterNot { it == Manifest.permission.POST_NOTIFICATIONS }
        val requiredGranted = required.all { permission ->
            result[permission] == true ||
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
        val notificationDenied = requested.contains(Manifest.permission.POST_NOTIFICATIONS) &&
            result[Manifest.permission.POST_NOTIFICATIONS] == false

        if (notificationDenied) {
            viewModel.showMessage("未授予通知权限，任务仍会在前台服务中运行，但通知栏不显示进度")
        }
        if (requiredGranted) {
            action?.invoke()
        } else if (deniedAction != null) {
            viewModel.showMessage("未授予旧版存储权限，改用系统选择器按 URI 授权")
            deniedAction.invoke()
        } else {
            viewModel.showMessage("权限被拒绝，无法执行该操作")
        }
    }

    fun withPermissions(
        permissions: List<String>,
        onDenied: (() -> Unit)? = null,
        action: () -> Unit,
    ) {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            action()
            return
        }
        pendingAction = action
        pendingDeniedAction = onDenied
        pendingPermissions = missing
        permissionLauncher.launch(missing.toTypedArray())
    }

    fun requestTaskPermissions(action: () -> Unit) {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        withPermissions(permissions, action = action)
    }

    val generatePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MainViewModel.MAX_BATCH),
    ) { uris -> if (uris.isNotEmpty()) viewModel.addGenerateImages(uris) }

    val decodePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MainViewModel.MAX_BATCH),
    ) { uris -> if (uris.isNotEmpty()) viewModel.addDecodeImages(uris) }

    val tracePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(viewModel::addTraceImage) }

    fun launchGeneratePicker() {
        generatePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    fun launchDecodePicker() {
        decodePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    fun launchTracePicker() {
        tracePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    fun pickGenerate() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            withPermissions(
                permissions = listOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                onDenied = { launchGeneratePicker() },
                action = { launchGeneratePicker() },
            )
        } else {
            launchGeneratePicker()
        }
    }

    fun pickDecode() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            withPermissions(
                permissions = listOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                onDenied = { launchDecodePicker() },
                action = { launchDecodePicker() },
            )
        } else {
            launchDecodePicker()
        }
    }

    fun pickTrace() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            withPermissions(
                permissions = listOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                onDenied = { launchTracePicker() },
                action = { launchTracePicker() },
            )
        } else {
            launchTracePicker()
        }
    }

    val decodeCsv = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> uri?.let { viewModel.exportDecode(it, json = false) } }

    val decodeJson = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let { viewModel.exportDecode(it, json = true) } }

    val batchCsv = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> uri?.let { viewModel.exportBatch(it, json = false) } }

    val batchJson = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let { viewModel.exportBatch(it, json = true) } }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearMessage()
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column {
                        Text("TrustMark 工具")
                        Text(
                            "端侧批量隐水印 · Q 模型 · 批次溯源",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TabRow(selectedTabIndex = state.tab) {
                Tab(
                    selected = state.tab == 0,
                    onClick = { viewModel.selectTab(0) },
                    text = { Text("生成水印") },
                    icon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                )
                Tab(
                    selected = state.tab == 1,
                    onClick = { viewModel.selectTab(1) },
                    text = { Text("批量解密") },
                    icon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                )
                Tab(
                    selected = state.tab == 2,
                    onClick = { viewModel.selectTab(2) },
                    text = { Text("溯源查询") },
                    icon = { Icon(Icons.Outlined.TravelExplore, contentDescription = null) },
                )
            }

            when (state.tab) {
                0 -> GenerateScreen(
                    state = state,
                    viewModel = viewModel,
                    onPickImages = ::pickGenerate,
                    onStart = { requestTaskPermissions(viewModel::startEncode) },
                    onDownloadModels = viewModel::downloadModels,
                    onManageBatches = { showBatchDialog = true },
                )

                1 -> DecodeScreen(
                    state = state,
                    viewModel = viewModel,
                    onPickImages = ::pickDecode,
                    onStart = { requestTaskPermissions(viewModel::startDecode) },
                    onExportCsv = { decodeCsv.launch(viewModel.decodeExportFileName(json = false)) },
                    onExportJson = { decodeJson.launch(viewModel.decodeExportFileName(json = true)) },
                )

                else -> TraceScreen(
                    state = state,
                    viewModel = viewModel,
                    onPickImage = ::pickTrace,
                    onStart = { requestTaskPermissions(viewModel::startTrace) },
                    onManageBatches = { showBatchDialog = true },
                )
            }
        }
    }

    if (showBatchDialog) {
        BatchManagementDialog(
            batches = state.batches,
            onDismiss = { showBatchDialog = false },
            onCreateBatch = viewModel::createMappedBatch,
            onUpsertMapping = viewModel::upsertMapping,
            onDeleteMapping = viewModel::deleteMapping,
            onDeleteBatch = viewModel::deleteBatch,
            onExportCsv = { batchCsv.launch(viewModel.batchExportFileName(json = false)) },
            onExportJson = { batchJson.launch(viewModel.batchExportFileName(json = true)) },
        )
    }
}

/**
 * Kept to document that READ_MEDIA_IMAGES is only a manifest declaration for
 * legacy OEM pickers; the system Photo Picker grants per-URI access instead.
 */
@Suppress("unused")
private fun MainUiState.mediaPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
