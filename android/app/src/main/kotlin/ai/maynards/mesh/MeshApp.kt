package ai.maynards.mesh

import ai.maynards.mesh.brain.Runner
import ai.maynards.mesh.brain.Shelf
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

    override fun onCreate() {
        super.onCreate()
        engine = Engine(this)
    }
}
