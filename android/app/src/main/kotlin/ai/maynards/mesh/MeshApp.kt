package ai.maynards.mesh

import ai.maynards.mesh.brain.Runner
import ai.maynards.mesh.brain.Shelf
import ai.maynards.mesh.brain.Stats
import ai.maynards.mesh.engine.Engine
import ai.maynards.mesh.mesh.MeshClient
import ai.maynards.mesh.mesh.MeshHost
import android.app.Application

/** App-wide singletons: one engine per phone, plus the Host or Helper side of the mesh. */
class MeshApp : Application() {
    lateinit var engine: Engine
        private set
    val host: MeshHost by lazy { MeshHost(this) }
    val client: MeshClient by lazy { MeshClient(this, engine) }
    val shelf: Shelf by lazy { Shelf(this) }
    val runner: Runner by lazy { Runner(host, engine, shelf) }
    val stats: Stats by lazy { Stats(this) }

    /** Test switch: pretend the Host has at most this much memory for models (0 = off). */
    var hostCapBytes: Long = 0

    /** Scripted runs: start this model (part of its file name) as soon as the Host is up. */
    var autoRun: String? = null

    override fun onCreate() {
        super.onCreate()
        engine = Engine(this)
    }
}
