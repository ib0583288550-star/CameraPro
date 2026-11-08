package com.ybapps.camerapro

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        setContent { CameraProScreen() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraProScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { context.getSharedPreferences("camera_pro", Context.MODE_PRIVATE) }
    var hasPermission by remember { mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    ) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var lens by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var cameraSessionKey by remember { mutableIntStateOf(0) }
    var flash by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var exposure by remember { mutableFloatStateOf(0f) }
    var exposureMin by remember { mutableIntStateOf(0) }
    var exposureMax by remember { mutableIntStateOf(0) }
    var focusX by remember { mutableFloatStateOf(0.5f) }
    var focusY by remember { mutableFloatStateOf(0.5f) }
    var showFocusControls by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("מוכן לצילום") }
    var savingToSd by remember { mutableStateOf(prefs.getBoolean("sd", false)) }
    var sdUri by remember { mutableStateOf(prefs.getString("tree", null)) }
    var showSettings by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            sdUri = uri.toString()
            prefs.edit().putString("tree", sdUri).putBoolean("sd", true).apply()
            savingToSd = true
            status = "השמירה בכרטיס הזיכרון הופעלה"
        }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFF9B87FF), background = Color(0xFF090D17),
        surface = Color(0xFF151C2B), onSurface = Color.White
    )) {
        if (!hasPermission) {
            Box(Modifier.fillMaxSize().background(Color(0xFF090D17)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Camera Pro", style = MaterialTheme.typography.headlineLarge, color = Color.White)
                    Text("נדרשת הרשאת מצלמה כדי להתחיל", color = Color.LightGray)
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("מתן הרשאה") }
                }
            }
        } else {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                key(cameraSessionKey) { AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            val providerFuture = ProcessCameraProvider.getInstance(ctx)
                            providerFuture.addListener({
                                val provider = providerFuture.get()
                                val preview = Preview.Builder().build().also { it.setSurfaceProvider(surfaceProvider) }
                                val capture = ImageCapture.Builder()
                                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                    .setFlashMode(if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
                                    .build()
                                try {
                                    provider.unbindAll()
                                    val selector = CameraSelector.Builder().requireLensFacing(lens).build()
                                    val bound = provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
                                    camera = bound
                                    imageCapture = capture
                                    exposureMin = bound.cameraInfo.exposureState.exposureCompensationRange.lower
                                    exposureMax = bound.cameraInfo.exposureState.exposureCompensationRange.upper
                                } catch (_: Exception) { status = "לא ניתן להפעיל את המצלמה" }
                            }, ContextCompat.getMainExecutor(ctx))
                            setOnTouchListener { view, event ->
                                if (event.action == android.view.MotionEvent.ACTION_UP) {
                                    focusX = (event.x / view.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                    focusY = (event.y / view.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                                    showFocusControls = true
                                    val point: MeteringPoint = meteringPointFactory.createPoint(event.x, event.y)
                                    val action = FocusMeteringAction.Builder(point)
                                        .setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                                    camera?.cameraControl?.startFocusAndMetering(action)
                                    status = "מיקוד מתבצע…"
                                }
                                true
                            }
                        }
                    },
                    update = { }
                ) }

                if (showFocusControls) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val density = LocalDensity.current
                        val markerSize = with(density) { 46.dp.roundToPx() }
                        val sliderWidth = with(density) { 48.dp.roundToPx() }
                        val sliderHeight = with(density) { 148.dp.roundToPx() }
                        val gapPx = with(density) { 12.dp.roundToPx() }
                        val xPx = (focusX * constraints.maxWidth).toInt()
                        val yPx = (focusY * constraints.maxHeight).toInt()
                        val markerLeft = (xPx - markerSize / 2).coerceIn(0, (constraints.maxWidth - markerSize).coerceAtLeast(0))
                        val markerTop = (yPx - markerSize / 2).coerceIn(0, (constraints.maxHeight - markerSize).coerceAtLeast(0))
                        val sliderLeft = if (markerLeft + markerSize + gapPx + sliderWidth < constraints.maxWidth)
                            markerLeft + markerSize + gapPx else (markerLeft - sliderWidth - gapPx).coerceAtLeast(0)
                        val sliderTop = (yPx - sliderHeight / 2).coerceIn(0, (constraints.maxHeight - sliderHeight).coerceAtLeast(0))
                        Box(Modifier.offset { IntOffset(markerLeft, markerTop) }.size(46.dp).border(2.dp, Color(0xFFFFD54F), CircleShape))
                        Column(
                            modifier = Modifier.offset { IntOffset(sliderLeft, sliderTop) }
                                .size(width = 48.dp, height = 148.dp)
                                .background(Color(0x990B1020), RoundedCornerShape(24.dp))
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.WbSunny, contentDescription = "בהירות", tint = Color(0xFFFFD54F), modifier = Modifier.size(18.dp))
                            Slider(
                                value = exposure.coerceIn(exposureMin.toFloat(), exposureMax.coerceAtLeast(exposureMin).toFloat()),
                                onValueChange = {
                                    exposure = it
                                    camera?.cameraControl?.setExposureCompensationIndex(it.toInt())
                                },
                                valueRange = exposureMin.toFloat()..exposureMax.coerceAtLeast(exposureMin + 1).toFloat(),
                                modifier = Modifier.width(128.dp).height(40.dp).rotate(270f)
                            )
                        }
                    }
                }

                Column(Modifier.fillMaxSize().padding(18.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("CAMERA PRO", color = Color.White, style = MaterialTheme.typography.titleLarge)
                            Text(if (savingToSd) "שמירה: כרטיס זיכרון" else "שמירה: זיכרון פנימי", color = Color.White.copy(alpha=.75f), style = MaterialTheme.typography.labelMedium)
                        }
                        Row {
                            IconButton(onClick = { flash = !flash }) {
                                Icon(if (flash) Icons.Default.FlashOn else Icons.Default.FlashOff, null, tint = Color.White)
                            }
                            IconButton(onClick = { showSettings = true }) {
                                Icon(Icons.Default.Settings, "הגדרות", tint = Color.White)
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Column(
                        Modifier.fillMaxWidth().background(Color(0xCC101827), RoundedCornerShape(22.dp)).padding(horizontal=18.dp, vertical=12.dp)
                    ) {
                        Text("זום ${"%.1f".format(zoom)}×", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        Slider(value=zoom, onValueChange={ zoom=it; camera?.cameraControl?.setZoomRatio(it) },
                            valueRange=1f..(camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 4f).coerceAtLeast(1f))
                    }

                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(status, color = Color.White, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                                cameraSessionKey++
                                status = "מחליף מצלמה…"
                            }) { Icon(Icons.Default.Cameraswitch, null, tint=Color.White) }
                            IconButton(
                                modifier = Modifier.size(78.dp).background(Color.White, CircleShape),
                                onClick = {
                                    val capture = imageCapture ?: return@IconButton
                                    capture.flashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
                                    val temp = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
                                    capture.takePicture(ImageCapture.OutputFileOptions.Builder(temp).build(),
                                        ContextCompat.getMainExecutor(context),
                                        object : ImageCapture.OnImageSavedCallback {
                                            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                                                try {
                                                    if (savingToSd && sdUri != null) {
                                                        val tree = DocumentFile.fromTreeUri(context, Uri.parse(sdUri!!))
                                                            ?: throw Exception("כרטיס הזיכרון לא זמין")
                                                        val dest = tree.createFile("image/jpeg", "CameraPro_${System.currentTimeMillis()}.jpg")
                                                            ?: throw Exception("לא ניתן ליצור קובץ בכרטיס")
                                                        context.contentResolver.openOutputStream(dest.uri).use { out ->
                                                            FileInputStream(temp).use { input -> input.copyTo(out!!) }
                                                        }
                                                    } else {
                                                        val values = ContentValues().apply {
                                                            put(MediaStore.Images.Media.DISPLAY_NAME, "CameraPro_${System.currentTimeMillis()}.jpg")
                                                            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                                                            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraPro")
                                                        }
                                                        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                                                            ?: throw Exception("לא ניתן לשמור תמונה")
                                                        context.contentResolver.openOutputStream(uri).use { out ->
                                                            FileInputStream(temp).use { input -> input.copyTo(out!!) }
                                                        }
                                                    }
                                                    temp.delete()
                                                    status = "התמונה נשמרה בהצלחה"
                                                } catch (e: Exception) { status = "שגיאה בשמירה: ${e.localizedMessage}" }
                                            }
                                            override fun onError(exception: ImageCaptureException) { status = "שגיאה בצילום: ${exception.message}" }
                                        })
                                }
                            ) { Icon(Icons.Default.CameraAlt, null, tint=Color(0xFF111827), modifier=Modifier.size(36.dp)) }
                            IconButton(onClick = { status = "הצילום נשמר בתיקיית CameraPro" }) {
                                Icon(Icons.Default.PhotoLibrary, null, tint=Color.White)
                            }
                        }
                    }
                }
            }
            if (showSettings) {
                AlertDialog(
                    onDismissRequest = { showSettings = false },
                    title = { Text("הגדרות שמירה") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("בחר היכן לשמור תמונות")
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected=!savingToSd, onClick={ savingToSd=false; prefs.edit().putBoolean("sd",false).apply() })
                                Text("זיכרון פנימי")
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected=savingToSd, onClick={ if (sdUri != null) { savingToSd=true; prefs.edit().putBoolean("sd",true).apply() } else folderPicker.launch(null) })
                                Text("כרטיס זיכרון")
                            }
                            OutlinedButton(onClick={ folderPicker.launch(null) }, modifier=Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("בחירת תיקייה בכרטיס SD")
                            }
                            Text("ניתן לבחור תיקייה בכרטיס פעם אחת; הבחירה תישמר גם לאחר סגירת האפליקציה.", style=MaterialTheme.typography.bodySmall)
                        }
                    },
                    confirmButton={ TextButton(onClick={ showSettings=false }) { Text("סיום") } }
                )
            }
        }
    }
}
