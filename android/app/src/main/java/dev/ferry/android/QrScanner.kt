package dev.ferry.android

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Camera preview that calls [onTicket] once, with the first Ferry pairing QR it sees. */
@Composable
fun QrScanner(onTicket: (String) -> Unit, onError: (String) -> Unit, modifier: Modifier = Modifier) {
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val done = remember { AtomicBoolean(false) }
    val provider = remember { arrayOfNulls<ProcessCameraProvider>(1) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val view = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                try {
                    val p = future.get().also { provider[0] = it }
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    val reader = MultiFormatReader().apply {
                        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
                    }
                    analysis.setAnalyzer(executor) { image ->
                        image.use {
                            // Y plane only: luminance is all ZXing needs, and QR decoding is rotation-free.
                            val plane = it.planes[0]
                            val bytes = ByteArray(plane.buffer.remaining()).also { b -> plane.buffer.get(b) }
                            val source = PlanarYUVLuminanceSource(
                                bytes, plane.rowStride, it.height, 0, 0, it.width, it.height, false,
                            )
                            val text = try {
                                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
                            } catch (_: ReaderException) {
                                null
                            } finally {
                                reader.reset()
                            }
                            val ticket = Tickets.extract(text)
                            if (ticket != null && done.compareAndSet(false, true)) view.post { onTicket(ticket) }
                        }
                    }
                    p.unbindAll()
                    if (done.get()) return@addListener // left the screen (or found one) before the camera was ready
                    p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (e: Exception) {
                    Log.w(TAG, "camera", e)
                    onError("Couldn't start the camera. Try again, or paste the pairing link instead.")
                }
            }, ContextCompat.getMainExecutor(ctx))
            view
        },
    )

    DisposableEffect(Unit) {
        onDispose {
            done.set(true)
            provider[0]?.unbindAll()
            executor.shutdown()
        }
    }
}
