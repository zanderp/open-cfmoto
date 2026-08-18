// SPDX-License-Identifier: AGPL-3.0-or-later
// Scan — CameraX + ML Kit QR scanner as a Compose screen, styled to the cockpit mockup: corner
// reticle, zoom, scan-from-photo, manual Wi-Fi entry. A valid dash QR pairs the bike and returns.
package dev.zanderp.opencfmoto.ui.scan

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.BikeMemory
import dev.zanderp.opencfmoto.ManualWifiPairing
import dev.zanderp.opencfmoto.QrData
import dev.zanderp.opencfmoto.ui.components.GhostButton
import dev.zanderp.opencfmoto.ui.components.MonoLabel
import dev.zanderp.opencfmoto.ui.theme.LocalCockpitColors
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun ScanScreen(nav: NavController) {
    val c = LocalCockpitColors.current
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(Manifest.permission.CAMERA) }

    val handled = remember { AtomicBoolean(false) }
    val scanner = remember { BarcodeScanning.getClient() }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var zoom by remember { mutableStateOf(1f) }
    DisposableEffect(Unit) { onDispose { executor.shutdown(); runCatching { scanner.close() } } }

    fun onQr(raw: String) {
        val qr = QrData.parse(raw)
        if (qr != null) {
            BikeMemory.save(ctx, raw, qr)
            nav.popBackStack()
        } else {
            handled.set(false)
        }
    }
    fun setZoom(r: Float) {
        zoom = r
        val cam = camera ?: return
        val st = cam.cameraInfo.zoomState.value ?: return
        cam.cameraControl.setZoomRatio(r.coerceIn(st.minZoomRatio, st.maxZoomRatio))
    }

    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null && handled.compareAndSet(false, true)) {
            runCatching {
                scanner.process(InputImage.fromFilePath(ctx, uri))
                    .addOnSuccessListener { bs ->
                        val qr = bs.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue
                        if (qr != null) onQr(qr) else handled.set(false)
                    }
                    .addOnFailureListener { handled.set(false) }
            }.onFailure { handled.set(false) }
        }
    }

    Box(Modifier.fillMaxSize().background(c.ground)) {
        if (granted) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    val pv = PreviewView(c)
                    val future = ProcessCameraProvider.getInstance(c)
                    future.addListener({
                        val provider = future.get()
                        val preview = Preview.Builder().build().also { it.surfaceProvider = pv.surfaceProvider }
                        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                        analysis.setAnalyzer(executor) { proxy -> analyzeProxy(proxy, scanner, handled) { raw -> onQr(raw) } }
                        runCatching {
                            provider.unbindAll()
                            camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                        }
                    }, ContextCompat.getMainExecutor(c))
                    pv
                },
            )
            Box(Modifier.fillMaxSize().padding(horizontal = 52.dp).padding(top = 120.dp, bottom = 220.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                    Canvas(Modifier.fillMaxSize()) {
                        val len = 34.dp.toPx(); val sw = 5.dp.toPx(); val w = size.width; val h = size.height
                        drawLine(c.ignition, Offset(0f, 0f), Offset(len, 0f), sw, StrokeCap.Round)
                        drawLine(c.ignition, Offset(0f, 0f), Offset(0f, len), sw, StrokeCap.Round)
                        drawLine(c.ignition, Offset(w, 0f), Offset(w - len, 0f), sw, StrokeCap.Round)
                        drawLine(c.ignition, Offset(w, 0f), Offset(w, len), sw, StrokeCap.Round)
                        drawLine(c.ignition, Offset(0f, h), Offset(len, h), sw, StrokeCap.Round)
                        drawLine(c.ignition, Offset(0f, h), Offset(0f, h - len), sw, StrokeCap.Round)
                        drawLine(c.ignition, Offset(w, h), Offset(w - len, h), sw, StrokeCap.Round)
                        drawLine(c.ignition, Offset(w, h), Offset(w, h - len), sw, StrokeCap.Round)
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.ovk_scan_perm_title), color = c.ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.ovk_scan_perm_body), color = c.inkDim, fontSize = 13.sp)
            }
        }

        // Top bar
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(c.surface1).border(1.dp, c.line, RoundedCornerShape(10.dp)).clickable { nav.popBackStack() },
                contentAlignment = Alignment.Center,
            ) { Text("✕", color = c.inkDim, fontSize = 15.sp) }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(R.string.ovk_tile_scan), color = c.ink, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                MonoLabel(stringResource(R.string.ovk_scan_subtitle), color = c.inkDim)
            }
        }

        // Bottom controls
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.ovk_scan_aim), color = c.ink, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZoomPill("1×", zoom == 1f) { setZoom(1f) }
                ZoomPill("2×", zoom == 2f) { setZoom(2f) }
                ZoomPill("3×", zoom == 3f) { setZoom(3f) }
                ZoomPill("◲ " + stringResource(R.string.ovk_scan_photo), false) { handled.set(false); photoLauncher.launch("image/*") }
            }
            GhostButton(stringResource(R.string.ovk_scan_manual_wifi), {
                (ctx as? AppCompatActivity)?.let { act ->
                    ManualWifiPairing.show(act) { raw, _ ->
                        if (handled.compareAndSet(false, true)) onQr(raw)
                    }
                }
            }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ZoomPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    val fg = if (selected) c.ignition else c.inkDim
    val bg = if (selected) c.ignition.copy(alpha = 0.14f) else c.surface1
    val bd = if (selected) c.ignition.copy(alpha = 0.4f) else c.line
    Box(
        Modifier.clip(RoundedCornerShape(999.dp)).background(bg).border(1.dp, bd, RoundedCornerShape(999.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(label, color = fg, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
}

@SuppressLint("UnsafeOptInUsageError")
private fun analyzeProxy(proxy: ImageProxy, scanner: BarcodeScanner, handled: AtomicBoolean, onQr: (String) -> Unit) {
    if (handled.get()) { proxy.close(); return }
    val media = proxy.image
    if (media == null) { proxy.close(); return }
    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
    scanner.process(image)
        .addOnSuccessListener { barcodes ->
            val qr = barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue
            if (qr != null && handled.compareAndSet(false, true)) onQr(qr)
        }
        .addOnCompleteListener { proxy.close() }
}
