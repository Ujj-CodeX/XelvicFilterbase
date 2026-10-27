package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme
import com.example.xelvic.camera.CameraController
import com.example.xelvic.ui.CameraScreen
import com.example.xelvic.ui.FilterImportSheet
import com.example.xelvic.ui.MainViewModel
import com.example.xelvic.ui.PhotoReviewScreen

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme(darkTheme = true) {
                XelvicApp(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun XelvicApp(viewModel: MainViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // Camera Permission State
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (!isGranted) {
            Toast.makeText(context, "Camera permission is required to run Xelvic", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // SAF Document Picker for .xfilter packages
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importFilter(uri)
        }
    }

    // CameraController instance
    val cameraController = remember(lifecycleOwner) {
        CameraController(context, lifecycleOwner, viewModel.faceAnalyzer)
    }

    DisposableEffect(cameraController) {
        onDispose {
            cameraController.release()
        }
    }

    // State Collection
    val installedFilters by viewModel.filterRepository.installedFilters.collectAsState()
    val selectedFilter by viewModel.filterRepository.selectedFilter.collectAsState()
    val isFaceLocked by viewModel.isFaceLocked.collectAsState()
    val isProcessingCapture by viewModel.isProcessingCapture.collectAsState()
    val reviewState by viewModel.reviewState.collectAsState()
    val importError by viewModel.importErrorMessage.collectAsState()

    var showImportSheet by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0A0E12)
    ) {
        if (!hasCameraPermission) {
            // Permission Request View
            CameraPermissionRequiredScreen(
                onRequestPermission = {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            )
        } else {
            // Main Flow: Review Screen vs Live Camera Screen
            val currentReview = reviewState
            if (currentReview != null) {
                PhotoReviewScreen(
                    bitmap = currentReview.bitmap,
                    filter = currentReview.filter,
                    warningMessage = currentReview.warningMessage,
                    onRetake = { viewModel.retakePhoto() }
                )
            } else {
                CameraScreen(
                    cameraController = cameraController,
                    installedFilters = installedFilters,
                    selectedFilter = selectedFilter,
                    poseHolder = viewModel.poseHolder,
                    isFaceLocked = isFaceLocked,
                    performanceMonitor = viewModel.performanceMonitor,
                    isProcessingCapture = isProcessingCapture,
                    renderer = viewModel.stickerRenderer,
                    onSelectFilter = { viewModel.filterRepository.selectFilter(it) },
                    onOpenImportSheet = { showImportSheet = true },
                    onCapturePhoto = { viewModel.capturePhoto(cameraController) }
                )
            }
        }

        // Filter Package Manager Bottom Sheet
        if (showImportSheet) {
            FilterImportSheet(
                installedFilters = installedFilters,
                selectedFilter = selectedFilter,
                onSelectFilter = { viewModel.filterRepository.selectFilter(it) },
                onLaunchFilePicker = {
                    filePickerLauncher.launch(arrayOf("*/*"))
                },
                onDeleteFilter = { viewModel.filterRepository.deleteFilter(it) },
                onDismiss = { showImportSheet = false }
            )
        }

        // Import Error Dialog
        importError?.let { errorMsg ->
            AlertDialog(
                onDismissRequest = { viewModel.clearImportError() },
                title = { Text(text = "Filter Package Error", fontWeight = FontWeight.Bold) },
                text = { Text(text = errorMsg) },
                confirmButton = {
                    TextButton(onClick = { viewModel.clearImportError() }) {
                        Text(text = "OK", color = Color(0xFF00E5FF))
                    }
                },
                containerColor = Color(0xFF1E293B),
                titleContentColor = Color.White,
                textContentColor = Color(0xFFCBD5E1)
            )
        }
    }
}

@Composable
fun CameraPermissionRequiredScreen(
    onRequestPermission: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0E12))
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF16202A)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    tint = Color(0xFF00E5FF),
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Camera Access Required",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Xelvic needs camera access to render real-time face-tracked .xfilter packages and capture photos.",
                fontSize = 14.sp,
                color = Color(0xFF94A3B8),
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = onRequestPermission,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("grant_camera_permission_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00E5FF),
                    contentColor = Color(0xFF0A0E12)
                )
            ) {
                Text(
                    text = "Grant Camera Permission",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
