package ai.maynards.origami

import android.net.Uri
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.toColorInt
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.maynards.origami.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers.IO
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private val Ink = Color(0xFF26352C)
private val Muted = Color(0xFF718076)
private val Leaf = Color(0xFF557A5A)
private val LeafLight = Color(0xFFE5EEE2)
private val Paper = Color(0xFFF7F5EF)
private val Card = Color(0xFFFFFEFA)
private val Line = Color(0xFFE5E2D9)
private val Amber = Color(0xFFB98539)
private val Selected = Color(0xFFDF9A3A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OrigamiTab() }
    }
}

private enum class Screen { CREATE, DESIGN, LAYOUT, EXPORT, GALLERY }
private enum class ExportKind { SVG, PROJECT }
private val stockOptions = listOf(StockSheet(600, 400), StockSheet(600, 600), StockSheet(900, 600), StockSheet(1220, 2440))
private data class ViewPart(val part: Part, val outline: List<Point3>, val cutouts: List<List<Point3>>, val depth: Double)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun OrigamiTab(
    aiEndpoint: String = OrigamiAIBridge.DEFAULT_ENDPOINT,
    aiApiKey: String = "",
    prepareAi: (suspend (needsVision: Boolean) -> String)? = null,
) {
    var screen by remember { mutableStateOf(Screen.CREATE) }
    var spec by remember { mutableStateOf(OrganizerSpec()) }
    var design by remember { mutableStateOf<OrganizerDesign?>(null) }
    var layout by remember { mutableStateOf<LayoutPlan?>(null) }
    var selectedStock by remember { mutableStateOf(stockOptions.first()) }
    var selectedPart by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(1f) }
    var exploded by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var aiDescription by remember { mutableStateOf("") }
    var referencePhoto by remember { mutableStateOf<Uri?>(null) }
    var photoPreview by remember { mutableStateOf<ImageBitmap?>(null) }
    var notice by remember { mutableStateOf("") }
    var exportKind by remember { mutableStateOf(ExportKind.SVG) }
    var kerfMm by remember { mutableStateOf(0.0) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val assemblyLayout = remember(design, layout) {
        val d = design; val l = layout
        if (d == null) null else if (l?.fits == true) l else SheetLayoutPlanner.previewLayout(d)
    }

    fun export(uri: Uri?) {
        if (uri == null) return
        val d = design ?: return
        val l = layout ?: return
        val content = runCatching {
            when (exportKind) {
                ExportKind.SVG -> SvgExporter.render(d, l, kerfMm)
                ExportKind.PROJECT -> ProjectJsonExporter.render(d, l)
            }
        }.getOrElse { notice = "Export failed: ${it.message ?: "invalid layout"}"; return }
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                ?: error("Could not open the selected file")
            notice = if (exportKind == ExportKind.SVG) "SVG saved" else "Project JSON saved"
        }.onFailure { notice = "Export failed: ${it.message ?: "unknown error"}" }
    }

    val svgPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/svg+xml")) { export(it) }
    val jsonPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { export(it) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { referencePhoto = it }
    LaunchedEffect(referencePhoto) {
        photoPreview = referencePhoto?.let { uri -> runCatching { withContext(IO) { decodePhoto(context, uri, 480).asImageBitmap() } }.getOrNull() }
    }
    LaunchedEffect(notice) {
        if (notice.isNotBlank()) {
            snackbar.showSnackbar(notice)
            notice = ""
        }
    }

    MaterialTheme(colorScheme = lightColorScheme(
        primary = Leaf, onPrimary = Color.White, background = Paper, surface = Card,
        onSurface = Ink, secondary = Amber, outlineVariant = Line,
    )) {
        Scaffold(
            containerColor = Paper,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                if (screen != Screen.CREATE) {
                    TopAppBar(
                        title = { Text("ORIGAMI LAB", fontSize = 12.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold) },
                        navigationIcon = { IconButton(onClick = { screen = Screen.CREATE }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to dimensions") } },
                        actions = { Text("V0.1", modifier = Modifier.padding(end = 18.dp), color = Muted, fontSize = 11.sp) },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper),
                    )
                }
            },
            bottomBar = {
                if (screen in setOf(Screen.DESIGN, Screen.LAYOUT, Screen.EXPORT) && design != null) {
                    NavigationBar(containerColor = Card, tonalElevation = 0.dp) {
                        NavigationBarItem(screen == Screen.DESIGN, { screen = Screen.DESIGN },
                            icon = { Text("3D", fontSize = 12.sp, fontWeight = FontWeight.Bold) }, label = { Text("Model") })
                        NavigationBarItem(screen == Screen.LAYOUT, { screen = Screen.LAYOUT },
                            icon = { Text("2D", fontSize = 12.sp, fontWeight = FontWeight.Bold) }, label = { Text("Flat pattern") })
                        NavigationBarItem(screen == Screen.EXPORT, { screen = Screen.EXPORT },
                            icon = { Icon(Icons.Default.Edit, null) }, label = { Text("Export") })
                    }
                }
            },
        ) { insets ->
            when (screen) {
                Screen.CREATE -> CreateScreen(
                    Modifier.padding(insets), spec, advanced, generating, { advanced = it },
                    onExplore = { screen = Screen.GALLERY },
                    onSpec = { spec = it },
                    onGenerate = {
                        coroutineScope.launch {
                            generating = true
                            val result = runCatching {
                                withContext(Dispatchers.Default) {
                                    val generated = OrganizerGenerator.generate(spec)
                                    generated to SheetLayoutPlanner.bestLayout(generated, selectedStock)
                                }
                            }
                            result.onSuccess { (generated, packed) ->
                                design = generated; layout = packed; selectedPart = null
                                progress = 1f; exploded = false; screen = Screen.DESIGN
                            }.onFailure { notice = it.message ?: "Could not generate this design" }
                            generating = false
                        }
                    },
                    aiDescription = aiDescription,
                    onAIDescription = { aiDescription = it },
                    referencePhoto = referencePhoto,
                    photoPreview = photoPreview,
                    onPickPhoto = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onClearPhoto = { referencePhoto = null },
                    onAIGenerate = { description, photo ->
                        coroutineScope.launch {
                            generating = true
                            var selectedModel = ""
                            val result = runCatching {
                                // MeshAI can select/start a compatible local vision model before the
                                // image-bearing request reaches llama-server. Origami geometry stays deterministic.
                                selectedModel = prepareAi?.invoke(photo != null).orEmpty()
                                val proposal = withContext(Dispatchers.IO) {
                                    val image = photo?.let { encodePhotoDataUrl(context, it) }
                                    OrigamiAIBridge(endpoint = aiEndpoint, apiKey = aiApiKey).propose(description, spec, image)
                                }
                                val generatedAndPacked = withContext(Dispatchers.Default) {
                                    val generated = OrganizerGenerator.generate(proposal)
                                    generated to SheetLayoutPlanner.bestLayout(generated, selectedStock)
                                }
                                Triple(proposal, generatedAndPacked.first, generatedAndPacked.second)
                            }
                            result.onSuccess { (proposal, generated, packed) ->
                                spec = proposal
                                design = generated
                                layout = packed
                                selectedPart = null
                                progress = 1f
                                exploded = false
                                screen = Screen.DESIGN
                                notice = "MeshAI${selectedModel.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()} generated the design. Confirm dimensions before fabrication."
                            }.onFailure {
                                notice = "MeshAI unavailable or returned an invalid design: ${it.message ?: "request failed"}"
                            }
                            generating = false
                        }
                    },
                )
                Screen.DESIGN -> design?.let { d -> layout?.let { l ->
                    DesignScreen(Modifier.padding(insets), d, assemblyLayout ?: l, progress, { progress = it }, exploded,
                        onExplode = { if (exploded) exploded = false else { progress = 1f; exploded = true } },
                        selectedPart, { selectedPart = it }, onLayout = { screen = Screen.LAYOUT })
                } }
                Screen.LAYOUT -> design?.let { d -> layout?.let { l ->
                    LayoutScreen(Modifier.padding(insets), d, l, selectedPart, { selectedPart = it },
                        onStock = { stock ->
                            selectedStock = stock
                            coroutineScope.launch { layout = withContext(Dispatchers.Default) { SheetLayoutPlanner.bestLayout(d, stock) } }
                        },
                        onExport = { screen = Screen.EXPORT })
                } }
                Screen.EXPORT -> design?.let { d -> layout?.let { l ->
                    ExportScreen(Modifier.padding(insets), d, l,
                        onSvg = { exportKind = ExportKind.SVG; if (l.fits) svgPicker.launch("organizer-cut-pattern.svg") else notice = l.error ?: "Layout does not fit" },
                        onProject = { exportKind = ExportKind.PROJECT; jsonPicker.launch("organizer-project.json") },
                        onLayout = { screen = Screen.LAYOUT }, kerfMm = kerfMm, onKerf = { kerfMm = it })
                } }
                Screen.GALLERY -> DesignGallery(Modifier.padding(insets))
            }
        }
    }
}

private fun decodePhoto(context: android.content.Context, uri: Uri, maxSide: Int): Bitmap {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val width = info.size.width
        val height = info.size.height
        val scale = minOf(1f, maxSide.toFloat() / maxOf(width, height))
        decoder.setTargetSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
    }
}

private fun encodePhotoDataUrl(context: android.content.Context, uri: Uri): String {
    val bitmap = decodePhoto(context, uri, 1024)
    return try {
        val bytes = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 82, bytes)) { "Could not encode the selected photo" }
        "data:image/jpeg;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    } finally {
        bitmap.recycle()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateScreen(
    modifier: Modifier,
    spec: OrganizerSpec,
    advanced: Boolean,
    generating: Boolean,
    onAdvanced: (Boolean) -> Unit,
    onExplore: () -> Unit,
    onSpec: (OrganizerSpec) -> Unit,
    onGenerate: () -> Unit,
    aiDescription: String,
    onAIDescription: (String) -> Unit,
    referencePhoto: Uri?,
    photoPreview: ImageBitmap?,
    onPickPhoto: () -> Unit,
    onClearPhoto: () -> Unit,
    onAIGenerate: (String, Uri?) -> Unit,
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ORIGAMI-INSPIRED FABRICATION", fontSize = 10.sp, letterSpacing = 1.6.sp, color = Leaf, fontWeight = FontWeight.Bold)
                    Text("Make it from a flat sheet.", fontSize = 29.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, color = Ink)
                }
                Box(Modifier.size(48.dp).clip(CircleShape).background(LeafLight), contentAlignment = Alignment.Center) {
                    Text("↗", fontSize = 25.sp, color = Leaf, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("Photograph your space or enter confirmed dimensions. MeshAI suggests supported organizer settings; Origami builds the geometry and cutting pattern.", color = Muted, fontSize = 14.sp)
            TextButton(onClick = onExplore, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 5.dp)) {
                Text("Explore table, bed, shelf, guitar and building previews →", color = Leaf, fontSize = 12.sp)
            }
        }
        item {
            SectionHeader("YOUR OBJECT")
            CardBlock {
                // the catalogue: pick a thing, then adjust its sizes
                var itemId by rememberSaveable { mutableStateOf(Catalogue.items.firstOrNull { it.spec.template == spec.template }?.id ?: "organizer") }
                Catalogue.groups.forEach { group ->
                    Text(group.uppercase(Locale.getDefault()), fontSize = 10.sp, letterSpacing = 1.sp, color = Muted, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Catalogue.items.filter { it.group == group }.forEach { item ->
                            FilterChip(selected = itemId == item.id, onClick = { itemId = item.id; onSpec(item.spec) },
                                label = { Text(item.name) })
                        }
                    }
                }
                val item = Catalogue.items.firstOrNull { it.id == itemId }
                Spacer(Modifier.height(10.dp))
                Text(item?.name ?: spec.template.label, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("${spec.template.label} generator · not fabrication-tested" + (item?.note?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                    color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(13.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Width", spec.widthMm.toString(), "mm", { it.toIntOrNull()?.let { value -> onSpec(spec.copy(widthMm = value)) } }, Modifier.weight(1f))
                    NumberField("Depth", spec.depthMm.toString(), "mm", { it.toIntOrNull()?.let { value -> onSpec(spec.copy(depthMm = value)) } }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Height", spec.heightMm.toString(), "mm", { it.toIntOrNull()?.let { value -> onSpec(spec.copy(heightMm = value)) } }, Modifier.weight(1f))
                    NumberField("Material", spec.materialThicknessMm.toString(), "mm thick", { it.toIntOrNull()?.let { value -> onSpec(spec.copy(materialThicknessMm = value)) } }, Modifier.weight(1f))
                }
                if (spec.template == Template.ORGANIZER || spec.template == Template.BOOKSHELF) {
                    Spacer(Modifier.height(12.dp))
                    Text(if (spec.template == Template.BOOKSHELF) "Shelves" else "Compartments", fontSize = 12.sp, color = Muted, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 7.dp)) {
                        (if (spec.template == Template.BOOKSHELF) 2..6 else 1..4).forEach { count ->
                            FilterChip(selected = spec.compartments == count, onClick = { onSpec(spec.copy(compartments = count)) },
                                label = { Text("$count") }, modifier = Modifier.weight(1f))
                        }
                    }
                } else {
                    Spacer(Modifier.height(10.dp))
                    Text("Not fabrication-tested yet · check fit on a scrap first", fontSize = 11.sp, color = Muted)
                }
            }
        }
        item {
            CardBlock {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Joint fit", fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 14.sp)
                        Text("Slot = material thickness + clearance", color = Muted, fontSize = 11.sp)
                    }
                    Text("Normal · ${spec.clearanceMm} mm", color = Leaf, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                TextButton(onClick = { onAdvanced(!advanced) }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (advanced) "Advanced ▴" else "Advanced ▾", color = Muted, fontSize = 12.sp)
                }
                if (advanced) {
                    Text("Joint clearance", fontSize = 12.sp, color = Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        listOf("Snug" to 0.1, "Normal" to 0.2, "Loose" to 0.4).forEach { (label, value) ->
                            FilterChip(selected = spec.clearanceMm == value, onClick = { onSpec(spec.copy(clearanceMm = value)) },
                                label = { Text(label) }, modifier = Modifier.weight(1f))
                        }
                    }
                    Text("Cut-sheet: 600 × 400 mm · margin 5 mm · part gap 2 mm", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
                }
            }
        }
        item {
            SectionHeader("MESHAI · LOCAL MODEL")
            CardBlock {
                Text("Describe an adjustment", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = aiDescription,
                    onValueChange = onAIDescription,
                    enabled = !generating,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    placeholder = { Text("For example: make three sections and use a snug fit") },
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onPickPhoto, enabled = !generating) {
                        Text(if (referencePhoto == null) "Add a reference photo" else "Change photo")
                    }
                    if (referencePhoto != null) TextButton(onClick = onClearPhoto, enabled = !generating) { Text("Remove") }
                }
                if (photoPreview != null) {
                    Image(
                        bitmap = photoPreview,
                        contentDescription = "Selected design reference photo",
                        modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(12.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        val request = aiDescription.ifBlank {
                            "Use the reference photo as context and choose suitable supported organizer settings."
                        }
                        onAIGenerate(request, referencePhoto)
                    },
                    enabled = !generating && (aiDescription.isNotBlank() || referencePhoto != null),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (generating) "Asking local MeshAI…" else "Ask local MeshAI")
                }
                Text(if (referencePhoto == null) "MeshAI chooses supported settings, then Origami builds the geometry and opens the 3D result."
                    else "The photo and request go to the local MeshAI model. Origami then opens the generated 3D result. Confirm dimensions before fabrication.",
                    color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Text("QUICK REQUESTS", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "More sections" to "Use four sections for pens and small tools",
                        "Snug fit" to "Use a snug tab and slot fit",
                        "Phone space" to "Make one wider section for a phone",
                    ).forEach { (label, prompt) ->
                        AssistChip(onClick = { onAIDescription(prompt) }, label = { Text(label, fontSize = 10.sp) }, enabled = !generating)
                    }
                }
            }
        }
        item {
            Button(onClick = onGenerate, enabled = !generating, modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(16.dp)) {
                if (generating) {
                    CircularProgressIndicator(modifier = Modifier.size(21.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("GENERATING…", fontSize = 14.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                } else Text("GENERATE DESIGN", fontSize = 14.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
            }
            Text("One object · tab + slot joints · SVG + project JSON", modifier = Modifier.fillMaxWidth().padding(top = 9.dp), color = Muted, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun DesignScreen(
    modifier: Modifier, design: OrganizerDesign, layout: LayoutPlan,
    progress: Float, onProgress: (Float) -> Unit, exploded: Boolean, onExplode: () -> Unit,
    selected: String?, onSelect: (String?) -> Unit, onLayout: () -> Unit,
) {
    val spec = design.spec
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("GENERATED", fontSize = 10.sp, letterSpacing = 1.5.sp, color = Leaf, fontWeight = FontWeight.Bold)
                    Text("Screwless ${spec.template.label.lowercase(Locale.getDefault())}", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Ink)
                }
                Icon(Icons.Default.Check, null, tint = Leaf)
            }
            Text("${spec.widthMm} × ${spec.depthMm} × ${spec.heightMm} mm", color = Muted, fontSize = 12.sp)
        }
        item {
            CardBlock {
                var realistic by remember { mutableStateOf(true) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    FilterChip(selected = realistic, onClick = { realistic = true }, label = { Text("Realistic") })
                    FilterChip(selected = !realistic, onClick = { realistic = false }, label = { Text("Lines · fold") })
                }
                Box(Modifier.fillMaxWidth().height(if (realistic) 360.dp else 310.dp).clip(RoundedCornerShape(15.dp)).background(Color(0xFFF0F1EB))) {
                    if (realistic) RealisticViewer(design, exploded, Modifier.fillMaxSize())
                    else AssemblyViewer(design, layout, progress, exploded, selected, onSelect, Modifier.fillMaxSize())
                    Text(if (realistic) "DRAG TO ORBIT · PINCH TO ZOOM" else "DRAG TO ROTATE", Modifier.align(Alignment.TopStart).padding(12.dp), color = Muted, fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                    if (selected != null) {
                        val name = design.part(selected)?.name ?: selected
                        Text(name.uppercase(Locale.getDefault()), Modifier.align(Alignment.BottomCenter).padding(10.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.88f)).padding(horizontal = 11.dp, vertical = 6.dp), color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(9.dp))
                if (!realistic) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("FLAT", fontSize = 10.sp, color = Muted, fontWeight = FontWeight.Bold)
                    Slider(value = progress, onValueChange = { onProgress(it); if (it < 0.9f) onSelect(null) }, modifier = Modifier.weight(1f).padding(horizontal = 9.dp))
                    Text("ASSEMBLED", fontSize = 10.sp, color = Leaf, fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${(progress * 100).toInt()}%", Modifier.weight(1f), color = Muted, fontSize = 11.sp)
                    OutlinedButton(onClick = onExplode, shape = RoundedCornerShape(11.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(if (exploded) "ASSEMBLE" else "EXPLODE", fontSize = 10.sp, letterSpacing = 0.7.sp)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill("${design.parts.size} PARTS")
                StatPill("${design.joints.size} JOINTS")
                StatPill("${spec.materialThicknessMm} mm MATERIAL")
                StatPill("NO SCREWS")
            }
        }
        item { StrengthCard(design) }
        item {
            Text("PARTS · TAP TO HIGHLIGHT", fontSize = 10.sp, color = Muted, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(7.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                design.parts.forEach { part ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(if (selected == part.id) LeafLight else Card).border(1.dp, Line, RoundedCornerShape(11.dp)).clickable { onSelect(if (selected == part.id) null else part.id) }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(parseColor(part.colorHex)))
                        Spacer(Modifier.width(9.dp))
                        Text(part.name, Modifier.weight(1f), color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("${"%.0f".format(part.widthMm)} × ${"%.0f".format(part.heightMm)} mm", color = Muted, fontSize = 10.sp)
                    }
                }
            }
        }
        item {
            Button(onClick = onLayout, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Text("VIEW CUTTING LAYOUT", fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
            }
        }
    }
}

@Composable
private fun LayoutScreen(modifier: Modifier, design: OrganizerDesign, layout: LayoutPlan, selected: String?, onSelect: (String?) -> Unit, onStock: (StockSheet) -> Unit, onExport: () -> Unit) {
    var stockMenu by remember { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            Text("FLAT PATTERN", fontSize = 10.sp, letterSpacing = 1.6.sp, color = Leaf, fontWeight = FontWeight.Bold)
            Text("Cut from one sheet.", fontSize = 25.sp, color = Ink, fontWeight = FontWeight.Bold)
            Text("${layout.stock.widthMm} × ${layout.stock.heightMm} mm stock · ${layout.stock.marginMm} mm margin · ${layout.stock.gapMm} mm part gap", color = Muted, fontSize = 12.sp)
            Box {
                TextButton(onClick = { stockMenu = true }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Change stock sheet ▾", color = Leaf, fontWeight = FontWeight.Bold) }
                DropdownMenu(expanded = stockMenu, onDismissRequest = { stockMenu = false }) {
                    stockOptions.forEach { option ->
                        DropdownMenuItem(text = { Text("${option.widthMm} × ${option.heightMm} mm") }, onClick = { stockMenu = false; onStock(option) })
                    }
                }
            }
        }
        item {
            CardBlock {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (layout.fits) "BEST LAYOUT FOUND" else "SHEET TOO SMALL", color = if (layout.fits) Leaf else Amber, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.7.sp)
                        Text(layout.layoutName, color = Muted, fontSize = 11.sp)
                    }
                    Text("${layout.utilizationPercent.toInt()}%", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
                Text("MATERIAL UTILIZATION", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
                Spacer(Modifier.height(11.dp))
                if (layout.fits) {
                    SheetPatternView(design, layout, selected, onSelect, Modifier.fillMaxWidth().height(310.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFFF0F1EB)))
                    Spacer(Modifier.height(9.dp))
                    Text("Tap a part to highlight it in the 3D model. Rotated parts are arranged at 90°.", color = Muted, fontSize = 11.sp)
                } else {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Info, null, tint = Amber, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(layout.error ?: "Increase sheet size to fit the generated parts.", color = Muted, fontSize = 13.sp)
                    }
                    Text("Part stock area: ${"%.0f".format(design.totalMaterialAreaMm2 / 100.0)} cm² · Sheet area: ${layout.stock.widthMm * layout.stock.heightMm / 100} cm²", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
        item {
            Text("${design.parts.size} rectangular panels · 90° rotation · row/column shelf packing", color = Muted, fontSize = 11.sp)
            OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Text("EXPORT SVG + PROJECT JSON")
            }
            Text("This is a practical shelf layout, not a proven global optimum. Confirm fit and joint tolerance with a test cut.", color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun ExportScreen(modifier: Modifier, design: OrganizerDesign, layout: LayoutPlan, onSvg: () -> Unit, onProject: () -> Unit, onLayout: () -> Unit,
                         kerfMm: Double = 0.0, onKerf: (Double) -> Unit = {}) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("EXPORT", fontSize = 10.sp, letterSpacing = 1.6.sp, color = Leaf, fontWeight = FontWeight.Bold)
            Text("Ready to fabricate.", fontSize = 28.sp, color = Ink, fontWeight = FontWeight.Bold)
            Text("Export the same panels and joints used by the 3D assembly.", color = Muted, fontSize = 14.sp)
        }
        item {
            CardBlock {
                Text("SVG · cutting pattern", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.height(5.dp))
                Text("1:1 millimetre viewBox · red hairline cut paths · tabs and slots", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Text("Laser kerf · outlines grow, slots shrink by half", fontSize = 12.sp, color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    listOf(0.0, 0.1, 0.15, 0.2).forEach { k ->
                        FilterChip(selected = kerfMm == k, onClick = { onKerf(k) }, label = { Text(if (k == 0.0) "None" else "$k mm") })
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = onSvg, enabled = layout.fits, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) { Text("SAVE SVG") }
                if (!layout.fits) Text("SVG is disabled until all parts fit on the stock sheet.", color = Amber, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
            }
        }
        item {
            CardBlock {
                Text("Project JSON", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.height(5.dp))
                Text("Includes dimensions, panel outlines, cutouts, flat positions, and joint relationships.", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onProject, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) { Text("SAVE PROJECT JSON") }
            }
        }
        item {
            CardBlock {
                Text("Manufacturing check", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("Slot fit is estimated from material thickness + ${design.spec.clearanceMm} mm clearance. Sheet layout reserves a ${layout.stock.marginMm} mm edge margin and ${layout.stock.gapMm} mm between parts. Test-cut one joint in your actual material before cutting the full set.", color = Muted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(2.dp))
            TextButton(onClick = onLayout, modifier = Modifier.fillMaxWidth()) { Text("Back to cutting layout") }
        }
    }
}

@Composable
private fun AssemblyViewer(design: OrganizerDesign, layout: LayoutPlan, progress: Float, exploded: Boolean, selected: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    var yaw by remember { mutableFloatStateOf(-0.62f) }
    var pitch by remember { mutableFloatStateOf(0.42f) }
    val explodeAmount by animateFloatAsState(if (exploded) 1f else 0f, label = "explode")
    val poses = remember(design, layout, progress, explodeAmount, yaw, pitch) {
        val spec = design.spec
        val cx = spec.widthMm / 2.0; val cy = spec.depthMm / 2.0; val cz = spec.heightMm / 2.0
        design.parts.map { part ->
            val placement = layout.placement(part.id)
            val mixed = part.outline.map { point ->
                val flat = if (placement != null) {
                    val q = part.sheetPoint(point, placement)
                    Point3(q.x, q.y, 0.0)
                } else Point3(point.x - part.minX, point.y - part.minY, 0.0)
                mapToView(part, point, flat, spec, progress, explodeAmount, cx, cy, cz, yaw, pitch)
            }
            val cutouts = part.cutouts.map { cutout ->
                listOf(
                    Point2(cutout.x, cutout.y), Point2(cutout.x + cutout.width, cutout.y),
                    Point2(cutout.x + cutout.width, cutout.y + cutout.height), Point2(cutout.x, cutout.y + cutout.height),
                ).map { point ->
                    val placement = layout.placement(part.id)
                    val q = if (placement != null) part.sheetPoint(point, placement) else Point2(point.x - part.minX, point.y - part.minY)
                    mapToView(part, point, Point3(q.x, q.y, 0.0), spec, progress, explodeAmount, cx, cy, cz, yaw, pitch)
                }
            }
            ViewPart(part, mixed, cutouts, mixed.map { it.z }.average())
        }.sortedBy { it.depth }
    }
    val hitRegions = remember { mutableListOf<Pair<String, List<Offset>>>() }
    Canvas(
        modifier
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    yaw += drag.x * 0.009f
                    pitch = (pitch + drag.y * 0.008f).coerceIn(-1.1f, 1.1f)
                }
            }
            .pointerInput(poses) {
                detectTapGestures { point ->
                    onSelect(hitRegions.asReversed().firstOrNull { pointInPolygon(point, it.second) }?.first)
                }
            },
    ) {
        val all = poses.flatMap { it.outline }
        if (all.isEmpty()) return@Canvas
        val minX = all.minOf { it.x }; val maxX = all.maxOf { it.x }
        val minY = all.minOf { it.y }; val maxY = all.maxOf { it.y }
        val spanX = max(1.0, maxX - minX); val spanY = max(1.0, maxY - minY)
        val scale = min(size.width / spanX, size.height / spanY) * 0.82
        val offsetX = (size.width - spanX * scale) / 2 - minX * scale
        val offsetY = (size.height - spanY * scale) / 2 - minY * scale
        hitRegions.clear()
        for (viewPart in poses) {
            val part = viewPart.part
            val pts = viewPart.outline.map { Offset((it.x * scale + offsetX).toFloat(), (it.y * scale + offsetY).toFloat()) }
            if (pts.size < 3) continue
            val path = Path().apply { moveTo(pts.first().x, pts.first().y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
            hitRegions += part.id to pts
            drawPath(path, if (part.id == selected) Color(0xFFF4D18F) else parseColor(part.colorHex))
            drawPath(path, if (part.id == selected) Selected else Color(0xFF516256), style = Stroke(width = if (part.id == selected) 3.dp.toPx() else 1.2.dp.toPx()))
            viewPart.cutouts.forEach { hole ->
                if (hole.size >= 3) {
                    val slotPts = hole.map { Offset((it.x * scale + offsetX).toFloat(), (it.y * scale + offsetY).toFloat()) }
                    val slotPath = Path().apply { moveTo(slotPts.first().x, slotPts.first().y); slotPts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
                    drawPath(slotPath, Color(0xFF805B3B))
                    drawPath(slotPath, Color(0xFF5E412C), style = Stroke(width = 0.7.dp.toPx()))
                }
            }
        }
    }
}

@Composable
private fun SheetPatternView(design: OrganizerDesign, layout: LayoutPlan, selected: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    val hitRegions = remember { mutableListOf<Pair<String, List<Offset>>>() }
    Canvas(modifier.pointerInput(layout, design) {
        detectTapGestures { point -> onSelect(hitRegions.asReversed().firstOrNull { pointInPolygon(point, it.second) }?.first) }
    }) {
        val scale = min(size.width / layout.stock.widthMm, size.height / layout.stock.heightMm)
        val sheetW = layout.stock.widthMm * scale; val sheetH = layout.stock.heightMm * scale
        val ox = (size.width - sheetW) / 2; val oy = (size.height - sheetH) / 2
        drawRoundRect(Color.White, Offset(ox, oy), Size(sheetW, sheetH), cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
        drawRoundRect(color = Color(0xFFBBC5B9), topLeft = Offset(ox, oy), size = Size(sheetW, sheetH), cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()), style = Stroke(width = 1.dp.toPx()))
        hitRegions.clear()
        for (part in design.parts) {
            val placement = layout.placement(part.id) ?: continue
            val points = part.outline.map { part.sheetPoint(it, placement) }
            val screenPoints = points.map { Offset((ox + it.x * scale).toFloat(), (oy + it.y * scale).toFloat()) }
            val path = Path().apply { moveTo(screenPoints.first().x, screenPoints.first().y); screenPoints.drop(1).forEach { lineTo(it.x, it.y) }; close() }
            hitRegions += part.id to screenPoints
            drawPath(path, if (part.id == selected) Color(0xFFFFE4B9) else parseColor(part.colorHex))
            drawPath(path, if (part.id == selected) Selected else Color(0xFF66786A), style = Stroke(if (part.id == selected) 2.5.dp.toPx() else 0.85.dp.toPx()))
            val labelX = screenPoints.map { it.x }.average().toFloat()
            val labelY = screenPoints.map { it.y }.average().toFloat()
            drawCircle(Color(0xFF304238), radius = 2.dp.toPx(), center = Offset(labelX, labelY))
            val slots = part.cutouts.map { part.sheetCutout(it, placement) }
            slots.forEach { slot ->
                drawRect(Color(0xFFB84838), Offset((ox + slot.x * scale).toFloat(), (oy + slot.y * scale).toFloat()), Size((slot.width * scale).toFloat(), (slot.height * scale).toFloat()))
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, unit: String, onValue: (String) -> Unit, modifier: Modifier = Modifier) {
    var edited by remember { mutableStateOf(value) }
    LaunchedEffect(value) { if (value != edited) edited = value }
    OutlinedTextField(value = edited, onValueChange = { edited = it.filter(Char::isDigit).take(4); onValue(edited) }, label = { Text(label, fontSize = 12.sp) },
        suffix = { Text(unit, color = Muted, fontSize = 10.sp) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier, singleLine = true, shape = RoundedCornerShape(12.dp))
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, modifier = Modifier.padding(bottom = 7.dp), fontSize = 10.sp, color = Leaf, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun CardBlock(content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Card), shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
        Column(Modifier.fillMaxWidth().padding(15.dp), content = content)
    }
}

@Composable
private fun StatPill(text: String) {
    Box(Modifier.clip(RoundedCornerShape(9.dp)).background(LeafLight).padding(horizontal = 8.dp, vertical = 7.dp)) {
        Text(text, fontSize = 9.sp, color = Leaf, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp)
    }
}

private fun parseColor(hex: String): Color = runCatching { Color(hex.toColorInt()) }.getOrDefault(LeafLight)

private fun mapToView(
    part: Part,
    point: Point2,
    flat: Point3,
    spec: OrganizerSpec,
    progress: Float,
    explodeAmount: Float,
    centerX: Double,
    centerY: Double,
    centerZ: Double,
    yaw: Float,
    pitch: Float,
): Point3 {
    val assembled = part.assembledPoint(point, spec)
    val explodeDir = when (part.plane) {
        PanelPlane.FLOOR -> Point3(0.0, 0.0, -0.15)
        PanelPlane.FRONT -> Point3(0.0, 1.0, 0.1)
        PanelPlane.BACK -> Point3(0.0, -1.0, 0.1)
        PanelPlane.LEFT_SIDE -> Point3(-1.0, 0.0, 0.1)
        PanelPlane.RIGHT_SIDE -> Point3(1.0, 0.0, 0.1)
        PanelPlane.DIVIDER -> Point3(0.0, 0.0, 1.0)
        PanelPlane.TOP -> Point3(0.0, 0.0, 1.0)
        PanelPlane.SHELF -> Point3(0.0, 1.0, 0.2)
    }
    val p = progress.toDouble()
    val e = explodeAmount.toDouble() * 55.0 * p
    val x = flat.x + (assembled.x - flat.x) * p + explodeDir.x * e
    val y = flat.y + (assembled.y - flat.y) * p + explodeDir.y * e
    val z = flat.z + (assembled.z - flat.z) * p + explodeDir.z * e
    val dx = x - centerX; val dy = y - centerY; val dz = z - centerZ
    val cyaw = cos(yaw); val syaw = sin(yaw)
    val x1 = dx * cyaw - dy * syaw
    val y1 = dx * syaw + dy * cyaw
    val cp = cos(pitch); val sp = sin(pitch)
    return Point3(x1, y1 * cp - dz * sp, y1 * sp + dz * cp)
}

private fun pointInPolygon(point: Offset, polygon: List<Offset>): Boolean {
    if (polygon.size < 3) return false
    var inside = false
    var j = polygon.lastIndex
    for (i in polygon.indices) {
        val a = polygon[i]
        val b = polygon[j]
        val crosses = (a.y > point.y) != (b.y > point.y) &&
            point.x < (b.x - a.x) * (point.y - a.y) / ((b.y - a.y).takeIf { it != 0f } ?: 0.0001f) + a.x
        if (crosses) inside = !inside
        j = i
    }
    return inside
}

/** Will it hold: each spanning board's sag and stress for a material and load (handbook values, a guide). */
@Composable
private fun StrengthCard(design: OrganizerDesign) {
    var material by remember { mutableStateOf(Structure.Material.PLYWOOD) }
    val default = Structure.defaultLoad(design.spec)
    val loads = listOf("Light · 5 kg" to Structure.Load(5.0), "Default · ${"%.0f".format(default.kg)} kg" to default,
        "Person · 90 kg centre" to Structure.Load(90.0, pointLoad = true))
    var loadIndex by remember(design) { mutableStateOf(1) }
    val checks = remember(design, material, loadIndex) { Structure.check(design, material, loads[loadIndex].second) }
    CardBlock {
        Text("WILL IT HOLD?", fontSize = 10.sp, letterSpacing = 1.5.sp, color = Leaf, fontWeight = FontWeight.Bold)
        if (checks.isEmpty()) { Text("It rests on its base: no board spans a gap.", color = Muted, fontSize = 13.sp); return@CardBlock }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Structure.Material.entries.forEach { m -> FilterChip(selected = material == m, onClick = { material = m }, label = { Text(m.label) }) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            loads.forEachIndexed { i, (label, _) -> FilterChip(selected = loadIndex == i, onClick = { loadIndex = i }, label = { Text(label) }) }
        }
        checks.forEach { c ->
            val (mark, color) = when (c.verdict) {
                Structure.Verdict.OK -> "✓" to Leaf
                Structure.Verdict.SAGS -> "◐" to Amber
                Structure.Verdict.FAILS -> "✗" to Color(0xFFB91C1C)
            }
            Text("$mark ${c.partName} · sags %.1f mm (looks flat under %.1f) · %.1f of %.1f MPa".format(Locale.US, c.sagMm, c.limitMm, c.stressMpa, c.allowedMpa),
                color = color, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            if (c.advice.isNotBlank()) Text("  → ${c.advice}", color = Muted, fontSize = 12.sp)
        }
        Text("Span ${"%.0f".format(checks.first().spanMm)} mm · handbook ${material.label.lowercase(Locale.US)} values with a ×${Structure.SAFETY.toInt()} safety factor · a guide, not a rating",
            color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
    }
}
