package ai.maynards.origami

import ai.maynards.origami.core.GlbWriter
import ai.maynards.origami.core.Mesh3D
import ai.maynards.origami.core.OrganizerDesign
import ai.maynards.origami.core.PanelPlane
import android.graphics.Bitmap
import android.view.Choreographer
import android.view.SurfaceView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.IndirectLight
import com.google.android.filament.Skybox
import com.google.android.filament.View
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.sin

/**
 * The assembled design rendered with Filament (physically based): plywood with a wood-grain texture, soft
 * daylight, sun shadows on a floor, ambient occlusion. Drag to orbit, pinch to zoom; explode slides each part
 * out along its own direction. The geometry is the same Mesh3D the tests check.
 */
@Composable
fun RealisticViewer(design: OrganizerDesign, exploded: Boolean, modifier: Modifier = Modifier) {
    val glb = remember(design) { GlbWriter.write(Mesh3D.build(design), WoodTexture.png()) }
    val explode by animateFloatAsState(if (exploded) 1f else 0f, tween(700), label = "explode3d")
    val holder = remember { arrayOfNulls<FilamentScene>(1) }
    AndroidView(
        factory = { c -> SurfaceView(c).also { sv -> holder[0] = FilamentScene(sv) } },
        modifier = modifier,
        update = { holder[0]?.load(glb) },
    )
    LaunchedEffect(explode, glb) { holder[0]?.explode(design, explode) }
    DisposableEffect(Unit) { onDispose { holder[0]?.destroy(); holder[0] = null } }
}

private class FilamentScene(sv: SurfaceView) : Choreographer.FrameCallback {
    companion object { init { Utils.init() } }

    private val choreographer = Choreographer.getInstance()
    private val viewer = ModelViewer(sv)
    private var loaded: ByteArray? = null
    private var alive = true

    init {
        sv.setOnTouchListener { _, e -> viewer.onTouchEvent(e); true }
        val engine = viewer.engine
        viewer.scene.skybox = Skybox.Builder().color(0.96f, 0.95f, 0.93f, 1f).build(engine)
        // soft daylight from every side (one spherical-harmonics band), plus the viewer's sun for shadows
        viewer.scene.indirectLight = IndirectLight.Builder().irradiance(1, floatArrayOf(0.95f, 0.93f, 0.9f)).intensity(28_000f).build(engine)
        val lm = engine.lightManager
        val sun = lm.getInstance(viewer.light)
        lm.setShadowCaster(sun, true)
        lm.setDirection(sun, 0.45f, -1f, -0.55f)
        lm.setIntensity(sun, 85_000f)
        viewer.view.setShadowingEnabled(true)
        viewer.view.ambientOcclusionOptions = View.AmbientOcclusionOptions().apply { enabled = true; radius = 0.25f; intensity = 1.2f }
        viewer.view.antiAliasing = View.AntiAliasing.FXAA
        choreographer.postFrameCallback(this)
    }

    fun load(glb: ByteArray) {
        if (loaded === glb) return
        loaded = glb
        viewer.destroyModel()
        viewer.loadModelGlb(ByteBuffer.wrap(glb))
        viewer.transformToUnitCube()
        val rm = viewer.engine.renderableManager
        viewer.asset?.entities?.forEach { e ->
            if (rm.hasComponent(e)) rm.getInstance(e).let { i -> rm.setCastShadows(i, true); rm.setReceiveShadows(i, true) }
        }
    }

    /** Moves each part's node out along its plane's direction; 0 = assembled, 1 = fully apart. */
    fun explode(design: OrganizerDesign, amount: Float) {
        val asset = viewer.asset ?: return
        val tm = viewer.engine.transformManager
        val gap = 0.06f * amount                  // metres in model space before the unit-cube fit
        for (part in design.parts) {
            val e = asset.getFirstEntityByName(part.id)
            if (e == 0 || !tm.hasComponent(e)) continue
            // explode direction in Origami axes (x right, y depth, z up) → glTF (x, z, -y)
            val (x, y, z) = when (part.plane) {
                PanelPlane.FLOOR -> Triple(0f, 0f, -0.6f)
                PanelPlane.TOP -> Triple(0f, 0f, 1f)
                PanelPlane.FRONT -> Triple(0f, 1f, 0f)
                PanelPlane.BACK -> Triple(0f, -1f, 0f)
                PanelPlane.LEFT_SIDE -> Triple(-1f, 0f, 0f)
                PanelPlane.RIGHT_SIDE -> Triple(1f, 0f, 0f)
                PanelPlane.DIVIDER -> Triple(0f, 0f, 1f)
            }
            val m = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }
            m[12] = x * gap; m[13] = z * gap; m[14] = -y * gap
            tm.setTransform(tm.getInstance(e), m)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!alive) return
        choreographer.postFrameCallback(this)
        viewer.render(frameTimeNanos)
    }

    fun destroy() {
        alive = false
        choreographer.removeFrameCallback(this)
        viewer.destroyModel()
    }
}

/** Plywood face veneer drawn in code: no downloaded texture, nothing to license, works offline. */
object WoodTexture {
    private var cached: ByteArray? = null

    fun png(size: Int = 512): ByteArray = cached ?: run {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            val u = x / size.toDouble(); val v = y / size.toDouble()
            // long grain along u, gently wavy, with fine fibres and a few darker growth lines (tileable: whole periods)
            val wave = sin(2 * Math.PI * (u * 2 + 0.15 * sin(2 * Math.PI * v * 3)))
            val rings = sin(2 * Math.PI * (v * 14 + 0.35 * wave))
            val fibre = sin(2 * Math.PI * (v * 97 + u * 3)) * 0.5 + sin(2 * Math.PI * (v * 211 + u * 7)) * 0.25
            val t = 0.5 + 0.22 * rings + 0.06 * fibre - 0.08 * abs(rings * rings * rings)
            val r = (205 * t + 40).coerceIn(0.0, 255.0).toInt()
            val g = (158 * t + 30).coerceIn(0.0, 255.0).toInt()
            val b = (104 * t + 18).coerceIn(0.0, 255.0).toInt()
            px[y * size + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        bmp.setPixels(px, 0, size, 0, 0, size, size)
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray().also { cached = it }
    }
}
