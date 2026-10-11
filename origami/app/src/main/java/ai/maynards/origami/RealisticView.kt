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
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.android.TextureHelper
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
fun RealisticViewer(design: OrganizerDesign, exploded: Boolean, modifier: Modifier = Modifier,
                    focus: ai.maynards.origami.core.Point3? = null) {
    val glb = remember(design) { GlbWriter.write(Mesh3D.build(design), WoodTexture.png()) }
    val explode by animateFloatAsState(if (exploded) 1f else 0f, tween(700), label = "explode3d")
    val holder = remember { arrayOfNulls<FilamentScene>(1) }
    AndroidView(
        factory = { c -> SurfaceView(c).also { sv -> holder[0] = FilamentScene(sv) } },
        modifier = modifier,
        update = { holder[0]?.load(glb) },
    )
    LaunchedEffect(explode, glb) { holder[0]?.explode(design, explode) }
    LaunchedEffect(focus, glb) { holder[0]?.focus(focus) }
    DisposableEffect(Unit) { onDispose { holder[0]?.destroy(); holder[0] = null } }
}

private class FilamentScene(sv: SurfaceView) : Choreographer.FrameCallback {
    companion object { init { Utils.init() } }

    private val choreographer = Choreographer.getInstance()
    // start at a three-quarter view from above, so depth and joints show; the model sits at the default (0, 0, -4)
    private val viewer = ModelViewer(sv, com.google.android.filament.Engine.create(),
        com.google.android.filament.android.UiHelper(com.google.android.filament.android.UiHelper.ContextErrorPolicy.DONT_CHECK),
        com.google.android.filament.utils.Manipulator.Builder()
            .targetPosition(0f, -0.05f, -4f).orbitHomePosition(1.95f, 1.3f, -4f + 2.95f)
            .viewport(maxOf(sv.width, 1), maxOf(sv.height, 1)).zoomSpeed(0.05f)
            .build(com.google.android.filament.utils.Manipulator.Mode.ORBIT))
    private var loaded: ByteArray? = null
    private var alive = true
    /** The wood grain, uploaded once: this Filament build's glTF loader cannot decode PNG itself. */
    private val wood: Texture by lazy {
        val bmp = WoodTexture.bitmap()
        Texture.Builder().width(bmp.width).height(bmp.height).levels(10).sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.SRGB8_A8)
            .usage(Texture.Usage.SAMPLEABLE or Texture.Usage.UPLOADABLE or Texture.Usage.GEN_MIPMAPPABLE)
            .build(viewer.engine).also { t ->
                TextureHelper.setBitmap(viewer.engine, t, 0, bmp)
                t.generateMipmaps(viewer.engine)
            }
    }

    init {
        sv.setOnTouchListener { _, e -> viewer.onTouchEvent(e); true }
        viewer.cameraFocalLength = 38f
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
        val sampler = TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.REPEAT)
        viewer.asset?.instance?.materialInstances?.forEach { mi ->
            if (mi.material.hasParameter("baseColorMap")) mi.setParameter("baseColorMap", wood, sampler)
        }
        val rm = viewer.engine.renderableManager
        viewer.asset?.entities?.forEach { e ->
            if (rm.hasComponent(e)) rm.getInstance(e).let { i -> rm.setCastShadows(i, true); rm.setReceiveShadows(i, true) }
        }
    }

    /**
     * Brings one point of the assembly (mm, Origami axes) to the orbit centre, magnified; null = the whole object.
     * The camera's bookmarks cannot be edited from Kotlin, so the model moves instead of the camera.
     */
    fun focus(p: ai.maynards.origami.core.Point3?) {
        val asset = viewer.asset ?: return
        if (p == null) { viewer.transformToUnitCube(); return }
        val box = asset.boundingBox
        val half = box.halfExtent
        val fit = 2f / (2f * maxOf(half[0], half[1], half[2]))      // what transformToUnitCube uses
        val s = fit * ZOOM
        val gx = (p.x / 1000).toFloat(); val gy = (p.z / 1000).toFloat(); val gz = (-p.y / 1000).toFloat()
        val m = FloatArray(16).also { it[0] = s; it[5] = s; it[10] = s; it[15] = 1f }
        m[12] = 0f - s * gx; m[13] = 0f - s * gy; m[14] = -4f - s * gz     // the joint lands on (0, 0, -4), the orbit target
        val tm = viewer.engine.transformManager
        tm.setTransform(tm.getInstance(asset.root), m)
    }

    private val ZOOM = 5f

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
                PanelPlane.SHELF -> Triple(0f, 1f, 0.2f)
                PanelPlane.LOOSE -> Triple(0f, 0f, 0f)
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
    private var cachedBitmap: Bitmap? = null

    /** A 4×4 placeholder for the glTF file: it only marks the material as textured; the grain is bound directly. */
    fun png(): ByteArray = cached ?: ByteArrayOutputStream().also {
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFC8A070.toInt()) }.compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray().also { cached = it }

    fun bitmap(size: Int = 512): Bitmap = cachedBitmap ?: run {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            val u = x / size.toDouble(); val v = y / size.toDouble()
            // birch plywood face veneer: pale, fine, almost straight grain along u, low contrast (tileable: whole periods)
            val drift = 0.04 * sin(2 * Math.PI * (u * 2 + 0.3 * sin(2 * Math.PI * v)))
            val rings = sin(2 * Math.PI * (v * 22 + drift))
            val fibre = sin(2 * Math.PI * (v * 131 + u * 2)) * 0.5 + sin(2 * Math.PI * (v * 263 + u * 5)) * 0.3
            val streak = sin(2 * Math.PI * (v * 5 + 0.1 * sin(2 * Math.PI * u)))
            val t = 1.0 - 0.07 * abs(rings * rings * rings * rings * rings) - 0.025 * fibre - 0.035 * streak
            val r = (228 * t).coerceIn(0.0, 255.0).toInt()
            val g = (199 * t).coerceIn(0.0, 255.0).toInt()
            val b = (156 * t).coerceIn(0.0, 255.0).toInt()
            px[y * size + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        bmp.setPixels(px, 0, size, 0, 0, size, size)
        bmp.also { cachedBitmap = it }
    }
}
