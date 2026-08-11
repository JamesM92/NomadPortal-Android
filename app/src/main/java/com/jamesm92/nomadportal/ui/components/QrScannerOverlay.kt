package com.jamesm92.nomadportal.ui.components

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.jamesm92.nomadportal.permissions.hasCameraPermission

/**
 * Full-screen camera overlay for the "scan a QR code to fill in an
 * address" flow — drawn on top of everything the same way
 * [com.jamesm92.nomadportal.ui.calling.CallOverlay] is, not a
 * NavHost destination, so it can be dropped into
 * [AddByAddressDialog] without touching that dialog's own call sites.
 *
 * [onResult] fires once, with a normalized hex destination hash (and a
 * public key hex string alongside it, when the scanned code was in this
 * app's own [buildIdentityQrPayload] `lxma://` shape rather than a bare
 * address), the moment a QR code decodes to something that passes
 * [normalizeScannedText]'s own validation — codes that don't decode to a
 * plausible address are silently ignored (the preview just keeps
 * scanning), not treated as an error, since a camera pointed at the real
 * world sees plenty of non-address QR codes before finding the right
 * one.
 */
@Composable
fun QrScannerOverlay(onResult: (destinationHash: String, publicKeyHex: String?) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(hasCameraPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            QrCameraPreview(onDecoded = onResult)
            Text(
                text = "Point your camera at a QR code",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp),
            )
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            ) {
                Text(
                    "Camera permission is needed to scan a QR code. You can " +
                        "still type an address by hand instead.",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        IconButton(
            onClick = onCancel,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        ) {
            Icon(imageVector = Icons.Filled.Close, contentDescription = "Cancel", tint = Color.White)
        }
        TextButton(
            onClick = onCancel,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp).fillMaxWidth(),
        ) {
            Text("Type address instead", color = Color.White)
        }
    }
}

/**
 * Owns the actual CameraX pipeline: a [Preview] use case feeding
 * [PreviewView] (what's on screen) plus an [ImageAnalysis] use case
 * whose analyzer decodes each frame via ZXing — two independent use
 * cases bound to the same lifecycle/selector, the standard CameraX shape
 * for "show a preview AND process frames," not a screenshot-the-preview
 * hack.
 */
@Composable
private fun QrCameraPreview(onDecoded: (destinationHash: String, publicKeyHex: String?) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Guards against onDecoded firing more than once — analysis frames
    // keep arriving (and could keep decoding successfully) for the brief
    // window between a successful decode and the caller actually
    // dismissing this overlay in response.
    var decoded by remember { mutableStateOf(false) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                    if (!decoded) {
                        val text = decodeQrFromImageProxy(imageProxy)
                        val normalized = text?.let(::normalizeScannedText)
                        if (normalized != null) {
                            decoded = true
                            onDecoded(normalized.destinationHash, normalized.publicKeyHex)
                        }
                    }
                    imageProxy.close()
                }
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                } catch (e: Exception) {
                    // Camera genuinely unavailable (in use elsewhere, hardware
                    // fault, emulator quirk) — preview just stays black;
                    // "Type address instead" below is always the fallback.
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

/** `ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888` is the default output
 * format — plane 0 is the Y (luminance) plane, exactly what ZXing's own
 * [PlanarYUVLuminanceSource] wants, no color conversion needed since QR
 * decoding only cares about light/dark, not color. Returns null on any
 * decode failure (the overwhelmingly common case — most frames don't
 * contain a decodable code at all), never throws. */
private fun decodeQrFromImageProxy(imageProxy: ImageProxy): String? = try {
    val buffer = imageProxy.planes[0].buffer
    val data = ByteArray(buffer.remaining())
    buffer.get(data)
    val source = PlanarYUVLuminanceSource(
        data, imageProxy.width, imageProxy.height, 0, 0, imageProxy.width, imageProxy.height, false,
    )
    val bitmap = BinaryBitmap(HybridBinarizer(source))
    val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }
    reader.decode(bitmap).text
} catch (e: NotFoundException) {
    null // No QR code in this frame — expected on almost every frame.
} catch (e: Exception) {
    null
}

/**
 * Parses a scanned code's raw text into a destination hash (and public
 * key, when present) — tries [parseIdentityQrPayload]'s real `lxma://`
 * shape first (this app's own generator, and interop-compatible with
 * Columba's identity-sharing QR codes — confirmed against its source),
 * then falls back to treating the whole thing as a bare hex address
 * (a code from something else entirely, or this app's own pre-`lxma://`
 * QR codes from before this format existed). Either way applies the
 * exact same hex validation [AddByAddressDialog]'s own text field
 * already does — an invalid or unreachable address still just fails the
 * normal way once used, same philosophy as manual entry, not a special
 * QR-only error path.
 */
private fun normalizeScannedText(raw: String): ScannedIdentity? {
    parseIdentityQrPayload(raw)?.let { return it }
    val candidate = raw.trim().substringAfterLast('/').substringAfterLast(':').lowercase()
    val isValidHex = candidate.isNotEmpty() && candidate.length % 2 == 0 &&
        candidate.all { it in "0123456789abcdef" }
    return if (isValidHex) ScannedIdentity(candidate, publicKeyHex = null) else null
}
