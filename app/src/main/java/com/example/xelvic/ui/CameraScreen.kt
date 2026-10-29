package com.example.xelvic.ui

import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.NotInterested
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.example.xelvic.camera.CameraController
import com.example.xelvic.camera.FlashMode
import com.example.xelvic.model.FaceTrackingResult
import com.example.xelvic.model.InstalledFilter
import com.example.xelvic.renderer.StickerRenderer
import androidx.compose.material.icons.filled.GridOn
import androidx.camera.view.TransformExperimental
import com.example.xelvic.geometry.FaceMeshUvRegistry
import com.example.xelvic.model.FilterRenderer
import com.example.xelvic.renderer.FaceMeshEffectBlockReason
import com.example.xelvic.renderer.FaceMeshEffectResolution
import com.example.xelvic.renderer.FaceMeshEffectResolver



@OptIn(TransformExperimental::class)
@Composable
fun CameraScreen(
    cameraController: CameraController,
    installedFilters: List<InstalledFilter>,
    selectedFilter: InstalledFilter?,
    poseHolder: com.example.xelvic.perf.TrackingPoseHolder,
    isFaceLocked: Boolean,
    performanceMonitor: com.example.xelvic.perf.PerformanceMonitor,
    isProcessingCapture: Boolean,
    renderer: StickerRenderer,
    onSelectFilter: (InstalledFilter?) -> Unit,
    onOpenImportSheet: () -> Unit,
    onCapturePhoto: () -> Unit
) {
    val flashMode by cameraController.flashMode.collectAsState()
    var showDebugOverlay by remember { mutableStateOf(false) }
    var showGlMeshOverlay by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val isMediaPipeGeometryEnabled by cameraController.isMediaPipeGeometryEnabled.collectAsState()
    val isMediaPipeFaceTracked by cameraController.isMediaPipeFaceTracked.collectAsState()

    val uvRegistry = remember(context) {
        // Intentionally empty until a model-compatible UV layout is verified.
        FaceMeshUvRegistry(context.assets, emptyList())
    }

    val meshEffectResolver = remember(uvRegistry) {
        FaceMeshEffectResolver(uvRegistry)
    }

    var meshEffectResolution by remember {
        mutableStateOf<FaceMeshEffectResolution>(
            FaceMeshEffectResolution.NoSelection
        )
    }

    val isMeshTextureSelected =
        selectedFilter?.definition?.renderer == FilterRenderer.FACE_MESH_TEXTURE

    LaunchedEffect(
        selectedFilter?.id,
        isMeshTextureSelected,
        isMediaPipeGeometryEnabled,
        isMediaPipeFaceTracked
    ) {
        val activeModelSha256 = cameraController.faceGeometryHolder
            .getLatest()
            ?.snapshot
            ?.modelAssetSha256

        meshEffectResolution = meshEffectResolver.resolve(
            filter = selectedFilter,
            activeModelAssetSha256 = activeModelSha256
        )
    }

    val activeMeshEffect =
        (meshEffectResolution as? FaceMeshEffectResolution.Ready)?.effect

    val geometryError by cameraController.geometryError.collectAsState()
    val faceLockedForDisplay =
        if (isMediaPipeGeometryEnabled) isMediaPipeFaceTracked else isFaceLocked

    LaunchedEffect(geometryError) {
        geometryError?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "face_indicator_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("camera_screen_container")
    ) {
        // 1. Camera Live Viewfinder
        CameraPreview(
            cameraController = cameraController,
            modifier = Modifier.fillMaxSize()
        )

        // 2. Real-time 2D Filter Hardware-Accelerated Overlay
        StickerOverlayView(
            poseHolder = poseHolder,
            selectedFilter = selectedFilter,
            showDebugOverlay = showDebugOverlay,
            renderer = renderer,
            performanceMonitor = performanceMonitor,
            isRenderingEnabled = !isMediaPipeGeometryEnabled && !isMeshTextureSelected,
            modifier = Modifier.fillMaxSize(),

        )

        // R1 Canvas diagnostic remains the reference overlay; the Grid toggle swaps to GPU edges.
        FaceGeometryDebugOverlay(
            holder = cameraController.faceGeometryHolder,
            enabled = isMediaPipeGeometryEnabled && !showGlMeshOverlay,
            modifier = Modifier.fillMaxSize()
        )

        FaceMeshGlWireframeOverlay(
            holder = cameraController.faceGeometryHolder,
            enabled = isMediaPipeGeometryEnabled && showGlMeshOverlay,
            modifier = Modifier.fillMaxSize()
        )

        // R6: Mesh-texture overlay integration
        FaceMeshTextureOverlay(
            holder = cameraController.faceGeometryHolder,
            effect = activeMeshEffect,
            enabled = isMediaPipeGeometryEnabled && isMeshTextureSelected,
            modifier = Modifier.fillMaxSize()
        )



        // 3. Top Control Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color(0x990D131A))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Branded Title Badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00E5FF))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "XELVIC",
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp,
                        letterSpacing = 2.sp,
                        color = Color.White
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Flash Mode Toggle
                    IconButton(
                        onClick = { cameraController.cycleFlashMode() },
                        modifier = Modifier.size(40.dp).testTag("flash_toggle_button")
                    ) {
                        val icon = when (flashMode) {
                            FlashMode.OFF -> Icons.Default.FlashOff
                            FlashMode.ON -> Icons.Default.FlashOn
                            FlashMode.AUTO -> Icons.Default.FlashAuto
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = "Flash mode",
                            tint = if (flashMode != FlashMode.OFF) Color(0xFF00E5FF) else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Developer Diagnostics Toggle
                    IconButton(
                        onClick = { showDebugOverlay = !showDebugOverlay },
                        modifier = Modifier.size(40.dp).testTag("debug_overlay_toggle")
                    ) {
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = "Developer Overlay",
                            tint = if (showDebugOverlay) Color(0xFFFFD54F) else Color(0xFF94A3B8),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Isolated R&D face-geometry path (replaces ML Kit for analysis while enabled).
                    IconButton(
                        onClick = {
                            val enableGeometry = !isMediaPipeGeometryEnabled
                            if (!enableGeometry) showGlMeshOverlay = false
                            cameraController.setMediaPipeGeometryEnabled(enableGeometry)
                        },
                        modifier = Modifier.size(40.dp).testTag("mediapipe_geometry_toggle_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Face,
                            contentDescription = "Toggle MediaPipe geometry diagnostics",
                            tint = if (isMediaPipeGeometryEnabled) Color(0xFF00E5FF) else Color(0xFF94A3B8),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // GPU wireframe diagnostics require MediaPipe geometry to be enabled first.
                    IconButton(
                        enabled = isMediaPipeGeometryEnabled,
                        onClick = { showGlMeshOverlay = !showGlMeshOverlay },
                        modifier = Modifier.size(40.dp).testTag("gl_mesh_debug_toggle_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.GridOn,
                            contentDescription = "Toggle GPU face-mesh wireframe",
                            tint = if (showGlMeshOverlay) Color(0xFFFF4FD8) else Color(0xFF94A3B8),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Camera Switch (Front / Rear)
                    IconButton(
                        onClick = { cameraController.toggleCamera() },
                        modifier = Modifier.size(40.dp).testTag("camera_switch_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = "Switch Camera",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Face Tracking Status Indicator
            Surface(
                color = if (faceLockedForDisplay) Color(0xAA064E3B) else Color(0x881E293B),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (faceLockedForDisplay) Color(0xFF10B981) else Color(0xFF475569)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (faceLockedForDisplay) Color(0xFF34D399) else Color(0xFF94A3B8))
                            .alpha(if (faceLockedForDisplay) 1f else pulseAlpha)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when {
                            isMediaPipeGeometryEnabled && isMediaPipeFaceTracked -> "MediaPipe Face Locked"
                            isMediaPipeGeometryEnabled -> "MediaPipe: Searching…"
                            isFaceLocked -> "Face Locked"
                            else -> "Searching Face…"
                        },
                        color = if (faceLockedForDisplay) Color(0xFFD1FAE5) else Color(0xFFCBD5E1),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // 4. Bottom Controls (Filter Selector Tray + Shutter Button)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0xD0000000), Color(0xF5000000))
                    )
                )
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            // Horizontally Scrollable Filter Carousel
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentPadding = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // "None" option
                item {
                    val isSelected = selectedFilter == null
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable { onSelectFilter(null) }
                            .testTag("filter_option_none")
                    ) {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) Color(0xFF263238) else Color(0xFF161C24))
                                .border(
                                    width = if (isSelected) 2.5.dp else 1.dp,
                                    color = if (isSelected) Color(0xFF00E5FF) else Color(0xFF455A64),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.NotInterested,
                                contentDescription = "None",
                                tint = if (isSelected) Color(0xFF00E5FF) else Color(0xFF94A3B8),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "None",
                            color = if (isSelected) Color(0xFF00E5FF) else Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }

                // Dynamic Installed Filter Packages
                items(installedFilters, key = { it.id }) { filter ->
                    val isSelected = filter.id == selectedFilter?.id
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable { onSelectFilter(filter) }
                            .testTag("filter_option_${filter.id}")
                    ) {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF0D131A))
                                .border(
                                    width = if (isSelected) 2.5.dp else 1.dp,
                                    color = if (isSelected) Color(0xFF00E5FF) else Color(0xFF263238),
                                    shape = CircleShape
                                )
                                .padding(if (isSelected) 2.dp else 0.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = filter.thumbnailFile,
                                contentDescription = filter.definition.name,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = filter.definition.name,
                            color = if (isSelected) Color(0xFF00E5FF) else Color(0xFFE2E8F0),
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Import Custom .xfilter Button
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable { onOpenImportSheet() }
                            .testTag("filter_option_import")
                    ) {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF13202E))
                                .border(
                                    width = 1.dp,
                                    color = Color(0xFF0284C7),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Import Filter",
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Import",
                            color = Color(0xFF38BDF8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Shutter Button Row
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                // Large Shutter Button
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(6.dp)
                        .clickable(enabled = !isProcessingCapture) { onCapturePhoto() }
                        .testTag("shutter_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(if (isProcessingCapture) Color(0xFF475569) else Color(0xFF00E5FF))
                    )
                }
            }
        }

        // 5. Processing Scrim Overlay
        AnimatedVisibility(
            visible = isProcessingCapture,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC0A0E12)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        color = Color(0xFF00E5FF),
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Rendering Photo…",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
