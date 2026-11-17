package com.sky.siplink.ui.settings

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sky.siplink.core.logic.QrDecoder
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.Executors

/**
 * The camera picture, full screen, looking for a QR code. The camera permission has been given
 * before this is shown. The first code [accepts] takes goes to [onFound]; other codes only
 * bring up a line saying that they are not taken.
 *
 * @param onFailed called when the camera cannot be opened, with what to tell the user
 */
@Composable
fun QrScanner(accepts: (String) -> Boolean, onFound: (String) -> Unit, onClose: () -> Unit, onFailed: (String) -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val preview = remember { PreviewView(context) }
        var refused by remember { mutableStateOf<String?>(null) }
        val found by rememberUpdatedState(onFound)
        val failed by rememberUpdatedState(onFailed)
        val takes by rememberUpdatedState(accepts)

        LaunchedEffect(lifecycleOwner) {
            val analyzer = Executors.newSingleThreadExecutor { Thread(it, "qr-scanner") }
            val main = ContextCompat.getMainExecutor(context)
            var done = false
            val provider = try {
                ProcessCameraProvider.awaitInstance(context)
            } catch (e: Exception) {
                analyzer.shutdown()
                failed(NO_CAMERA)
                return@LaunchedEffect
            }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(analyzer) { image ->
                val text = image.use(::decode) ?: return@setAnalyzer
                main.execute {
                    when {
                        done -> Unit
                        takes(text) -> {
                            done = true
                            found(text)
                        }
                        else -> refused = NOT_OURS
                    }
                }
            }
            try {
                provider.unbindAll()
                val picture = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, picture, analysis)
                awaitCancellation()
            } catch (e: IllegalArgumentException) {
                // No back camera.
                failed(NO_CAMERA)
            } catch (e: IllegalStateException) {
                failed(NO_CAMERA)
            } finally {
                provider.unbindAll()
                analyzer.shutdown()
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(240.dp)
                    .border(2.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(20.dp)),
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(8.dp),
            ) {
                Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = Color.White)
            }
            refused?.let { message ->
                Text(
                    message,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 48.dp),
                )
            }
        }
    }
}

/** The text of a QR code in the brightness plane of the picture, null where there is none. */
private fun decode(image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
    return QrDecoder.decode(bytes, image.width, image.height, plane.rowStride)
}

private const val NOT_OURS = "非远程配置二维码"
private const val NO_CAMERA = "无法打开相机"
