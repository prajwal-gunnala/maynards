package ai.maynards.origami

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private data class V3(val x: Float, val y: Float, val z: Float)
private data class Face(val points: List<V3>, val material: Color)
private data class FurnitureScene(
    val label: String,
    val size: V3,
    val description: String,
    val parts: List<Face>,
)

private val woods = listOf(Color(0xFFC69A6B), Color(0xFF9B6B43), Color(0xFFD4B18A), Color(0xFFB9875B))
private val metals = Color(0xFF48535B)

private fun box(x: Float, y: Float, z: Float, w: Float, d: Float, h: Float, color: Color): List<Face> {
    val a=V3(x,y,z); val b=V3(x+w,y,z); val c=V3(x+w,y+d,z); val e=V3(x,y+d,z)
    val f=V3(x,y,z+h); val g=V3(x+w,y,z+h); val i=V3(x+w,y+d,z+h); val j=V3(x,y+d,z+h)
    return listOf(Face(listOf(f,g,i,j), color), Face(listOf(a,b,g,f), color), Face(listOf(b,c,i,g), color),
        Face(listOf(c,e,j,i), color), Face(listOf(e,a,f,j), color), Face(listOf(a,e,c,b), color))
}

private fun prismXZ(points: List<Pair<Float, Float>>, y: Float, depth: Float, color: Color): List<Face> {
    val front = points.map { V3(it.first, y, it.second) }
    val back = points.map { V3(it.first, y + depth, it.second) }
    val faces = mutableListOf(Face(front, color.copy(red = (color.red * .88f).coerceIn(0f,1f))), Face(back, color.copy(red = (color.red * .72f).coerceIn(0f,1f))))
    for (i in points.indices) faces += Face(listOf(front[i], front[(i+1)%points.size], back[(i+1)%points.size], back[i]), color)
    return faces
}

private fun scene(index: Int): FurnitureScene = when (index) {
    0 -> {
        val p = mutableListOf<Face>()
        p += box(0f,0f,730f,1120f,650f,32f,woods[0])
        for (x in listOf(36f, 1048f)) for (y in listOf(36f, 582f)) p += box(x,y,0f,36f,36f,735f,woods[1])
        p += box(80f,45f,615f,960f,32f,110f,woods[1]); p += box(80f,573f,615f,960f,32f,110f,woods[1])
        p += box(45f,90f,615f,32f,470f,110f,woods[1]); p += box(1043f,90f,615f,32f,470f,110f,woods[1])
        p += box(440f,8f,650f,230f,12f,74f,woods[2]); p += box(540f,1f,680f,40f,9f,8f,metals)
        FurnitureScene("Study table", V3(1120f,650f,762f), "Solid top · four legs · apron rails · shallow drawer", p)
    }
    1 -> {
        val p = mutableListOf<Face>()
        p += box(0f,0f,330f,1400f,2000f,65f,woods[1])
        p += box(70f,90f,395f,1260f,1820f,190f,Color(0xFFE7DED0))
        p += box(65f,90f,585f,1270f,1300f,42f,Color(0xFF7893A2))
        p += box(100f,1470f,628f,480f,340f,110f,Color(0xFFF7F2E9)); p += box(620f,1470f,628f,480f,340f,110f,Color(0xFFF7F2E9))
        p += box(0f,1880f,0f,1400f,55f,880f,woods[0])
        p += box(0f,0f,0f,1400f,65f,360f,woods[1])
        for (x in listOf(0f, 1340f)) for (y in listOf(0f, 1935f)) p += box(x,y,0f,60f,60f,330f,woods[2])
        FurnitureScene("Platform bed", V3(1400f,2000f,880f), "Raised platform · upholstered mattress · paired pillows · headboard", p)
    }
    2 -> {
        val p = mutableListOf<Face>()
        p += box(0f,0f,0f,38f,360f,1800f,woods[1]); p += box(762f,0f,0f,38f,360f,1800f,woods[1])
        for (z in listOf(0f, 430f, 860f, 1290f, 1762f)) p += box(38f,0f,z,724f,360f,38f,woods[0])
        val bookColors = listOf(Color(0xFF718696),Color(0xFFB66E56),Color(0xFF7A8A62),Color(0xFFD0AE72),Color(0xFF807394))
        for (shelf in 0..3) {
            val base = 42f + shelf*430f
            for (i in 0..5) { val bw=74f+(i%3)*12f; val bh=260f+(i%4)*30f; p += box(68f+i*105f,70f,base,bw,210f,bh,bookColors[(i+shelf)%bookColors.size]) }
        }
        FurnitureScene("Bookcase", V3(800f,360f,1800f), "Five shelves · side panels · individually visible books", p)
    }
    3 -> {
        val p = mutableListOf<Face>()
        val body = listOf(210f to 220f, 330f to 100f, 470f to 120f, 590f to 260f, 545f to 470f, 470f to 590f, 330f to 610f, 255f to 480f)
        p += prismXZ(body, 0f, 72f, Color(0xFFBA7950))
        p += box(369f,14f,510f,72f,46f,560f,woods[1]); p += box(350f,8f,1030f,110f,56f,125f,woods[0])
        p += box(349f,6f,490f,112f,62f,550f,Color(0xFF493A35))
        for (i in 0..13) p += box(350f,0f,535f+i*37f,112f,7f,4f,Color(0xFFD6C6AE))
        for (i in 0..5) p += box(382f+i*9f,1f,235f,2f,2f,805f,Color(0xFFDDD6C8))
        for (x in listOf(320f, 470f)) p += box(x,0f,1155f,22f,80f,18f,metals)
        FurnitureScene("Electric guitar", V3(650f,120f,1273f), "Carved body silhouette · fretboard · frets · six strings", p)
    }
    else -> {
        val p = mutableListOf<Face>()
        p += box(0f,0f,0f,1800f,120f,1800f,Color(0xFFE0D2B8)); p += box(0f,1680f,0f,1800f,120f,1800f,Color(0xFFE0D2B8))
        p += box(0f,120f,0f,120f,1560f,1800f,Color(0xFFE0D2B8)); p += box(1680f,120f,0f,120f,1560f,1800f,Color(0xFFE0D2B8))
        p += prismXZ(listOf(0f to 1800f, 900f to 2380f, 1800f to 1800f), 0f, 1800f, Color(0xFF8C5543))
        p += box(140f,0f,0f,1520f,1800f,80f,Color(0xFF9C7557))
        p += box(760f,0f,0f,300f,34f,1450f,Color(0xFF795A43))
        p += box(235f,5f,950f,300f,40f,360f,Color(0xFFB4D1D5)); p += box(1265f,5f,950f,300f,40f,360f,Color(0xFFB4D1D5))
        p += box(140f,1800f,0f,1520f,80f,80f,Color(0xFF9C7557))
        FurnitureScene("Cabin shell", V3(1800f,1800f,2380f), "Wall frame · door · windows · gable roof", p)
    }
}

@Composable
internal fun DesignGallery(modifier: Modifier = Modifier) {
    var selected by remember { mutableStateOf(0) }
    val item = remember(selected) { scene(selected) }
    Column(modifier.fillMaxSize().background(Color(0xFFF7F5EF)).padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("DESIGN EXPLORER", color = Color(0xFF557A5A), fontSize = 10.sp, letterSpacing = 1.7.sp)
        Text("From furniture to instruments", color = Color(0xFF26352C), style = MaterialTheme.typography.headlineSmall)
        Text("Drag the model to inspect its shape and parts.", color = Color(0xFF718076), fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("Table", "Bed", "Shelf", "Guitar", "Cabin").forEachIndexed { index, label ->
                AssistChip(onClick = { selected = index }, label = { Text(label, fontSize = 10.sp) }, modifier = Modifier.weight(1f))
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp)) {
            Box(Modifier.fillMaxWidth().height(350.dp).padding(8.dp).background(Color(0xFFF1F2EC), RoundedCornerShape(16.dp))) {
                Scene3D(item, Modifier.fillMaxSize())
                Text("DRAG TO ROTATE", Modifier.align(Alignment.TopStart).padding(12.dp), color = Color(0xFF718076), fontSize = 9.sp, letterSpacing = 1.sp)
            }
        }
        Text(item.label, color = Color(0xFF26352C), style = MaterialTheme.typography.titleLarge)
        Text("${item.size.x.toInt()} × ${item.size.y.toInt()} × ${item.size.z.toInt()} mm", color = Color(0xFF557A5A), fontSize = 12.sp)
        Text(item.description, color = Color(0xFF718076), fontSize = 13.sp)
        Spacer(Modifier.height(2.dp))
        Text("VISUAL CONCEPT · manufacturing geometry is not enabled for this sample yet", color = Color(0xFF9B6B43), fontSize = 10.sp)
        Text("The organizer is the current validated CAD template. Tables, beds, shelves, instruments and building designs need their own checked joints, cut layouts and material rules before they can export fabrication files.", color = Color(0xFF718076), fontSize = 11.sp)
    }
}

@Composable
private fun Scene3D(scene: FurnitureScene, modifier: Modifier = Modifier) {
    var yaw by remember(scene.label) { mutableFloatStateOf(-0.65f) }
    var pitch by remember(scene.label) { mutableFloatStateOf(0.28f) }
    val allFaces = remember(scene) { scene.parts.flatMap { it.points }.let { points ->
        if (points.isEmpty()) listOf(V3(0f,0f,0f), V3(1f,1f,1f)) else points
    } }
    Canvas(modifier.pointerInput(scene.label) {
        detectDragGestures { change, drag -> change.consume(); yaw += drag.x*.008f; pitch=(pitch+drag.y*.006f).coerceIn(-.7f,.7f) }
    }) {
        val cy=cos(yaw); val sy=sin(yaw); val cp=cos(pitch); val sp=sin(pitch)
        fun rotate(v: V3): V3 { val x=v.x-scene.size.x/2; val y=v.y-scene.size.y/2; val z=v.z-scene.size.z/2; val x1=x*cy-y*sy; val depth=x*sy+y*cy; return V3(x1,z*cp-depth*sp,depth*cp+z*sp) }
        val transformed = scene.parts.map { face -> face to face.points.map(::rotate) }
        val projected = transformed.flatMap { it.second }
        val minX=projected.minOf { it.x }; val maxX=projected.maxOf { it.x }; val minY=projected.minOf { it.y }; val maxY=projected.maxOf { it.y }
        val scale=min(size.width/(maxX-minX).coerceAtLeast(1f),size.height/(maxY-minY).coerceAtLeast(1f))*.78f
        val ox=(size.width-(maxX-minX)*scale)/2-minX*scale; val oy=(size.height-(maxY-minY)*scale)/2-minY*scale
        for ((face,points) in transformed.sortedBy { it.second.map(V3::z).average() }) {
            if(points.size<3) continue
            val path=Path().apply { moveTo(ox+points[0].x*scale,oy-points[0].y*scale); points.drop(1).forEach { lineTo(ox+it.x*scale,oy-it.y*scale) }; close() }
            val shade=(.76f+(points.map(V3::z).average().toFloat()*.00016f)).coerceIn(.56f,1f)
            val material=face.material
            drawPath(path,Color((material.red*shade).coerceIn(0f,1f),(material.green*shade).coerceIn(0f,1f),(material.blue*shade).coerceIn(0f,1f),1f))
            drawPath(path,Color(0xFF4B4C46),style=androidx.compose.ui.graphics.drawscope.Stroke(width=1.dp.toPx()))
        }
    }
}
