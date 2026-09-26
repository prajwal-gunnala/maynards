package ai.maynards.mesh

import ai.maynards.mesh.engine.Engine
import android.app.Application

/** App-wide singletons: one engine per phone. */
class MeshApp : Application() {
    lateinit var engine: Engine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = Engine(this)
    }
}
