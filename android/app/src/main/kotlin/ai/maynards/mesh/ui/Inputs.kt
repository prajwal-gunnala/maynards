package ai.maynards.mesh.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream

/** Speech to text on the phone itself (Android's on-device recogniser when there is one). */
class Voice internal constructor(val listening: Boolean, val start: () -> Unit, val stop: () -> Unit)

@Composable
fun rememberVoice(onText: (String) -> Unit): Voice {
    val ctx = LocalContext.current
    var listening by remember { mutableStateOf(false) }
    val recognizer = remember {
        if (SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        else SpeechRecognizer.createSpeechRecognizer(ctx)
    }
    DisposableEffect(recognizer) {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) {
                listening = false
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onText)
            }
            override fun onPartialResults(b: Bundle?) {
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onText)
            }
            override fun onError(error: Int) { listening = false }
            override fun onEndOfSpeech() { listening = false }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rms: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        onDispose { recognizer.destroy() }
    }
    fun listen() {
        listening = true
        recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        })
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) listen() }
    return Voice(
        listening,
        start = {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
            else permission.launch(Manifest.permission.RECORD_AUDIO)
        },
        stop = { recognizer.stopListening(); listening = false },
    )
}

/** Takes a photo with the camera and hands it back as a small JPEG data URL for the vision model. */
@Composable
fun rememberCamera(onPhoto: (Bitmap, String) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val shoot = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
        bmp?.let { onPhoto(it, dataUrl(it)) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) shoot.launch(null) }
    return {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) shoot.launch(null)
        else permission.launch(Manifest.permission.CAMERA)
    }
}

/** Picks a photo from the gallery and hands it back shrunk to at most 768 px (fast for the vision model). */
@Composable
fun rememberGallery(onPhoto: (Bitmap, String) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Decode downsampled and off the main thread. A full-size camera shot is ~200 MB as
        // ARGB_8888; while the engine holds several GB that throws OutOfMemoryError, runCatching
        // swallows it, and the photo silently never appears.
        scope.launch {
            val small = withContext(Dispatchers.IO) {
                runCatching {
                    val src = android.graphics.ImageDecoder.createSource(ctx.contentResolver, uri)
                    android.graphics.ImageDecoder.decodeBitmap(src) { d, info, _ ->
                        val longest = maxOf(info.size.width, info.size.height)
                        d.setTargetSampleSize(maxOf(1, longest / 768))
                        d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                        d.isMutableRequired = false
                    }
                }.getOrNull()?.let { shrink(it, 768) }
            }
            if (small == null) {
                android.widget.Toast.makeText(ctx, "Could not read that photo", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            val url = withContext(Dispatchers.IO) { dataUrl(small) }
            onPhoto(small, url)
        }
    }
    return { pick.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

fun shrink(b: Bitmap, max: Int): Bitmap {
    val k = max.toFloat() / maxOf(b.width, b.height)
    return if (k >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true)
}

fun dataUrl(bmp: Bitmap): String {
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
    return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}
