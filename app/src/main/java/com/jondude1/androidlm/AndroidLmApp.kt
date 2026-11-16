package com.jondude1.androidlm

import android.app.Application
import android.content.Context
import com.jondude1.androidlm.chat.ChatStore
import com.jondude1.androidlm.device.DeviceProfile
import com.jondude1.androidlm.device.DeviceProfiler
import com.jondude1.androidlm.device.Sensors
import com.jondude1.androidlm.engine.CrashGuard
import com.jondude1.androidlm.engine.LlmEngine
import com.jondude1.androidlm.engine.RunConfig
import com.jondude1.androidlm.models.DownloadManager
import com.jondude1.androidlm.models.HfClient
import com.jondude1.androidlm.models.LocalModel
import com.jondude1.androidlm.models.ModelStore
import com.jondude1.androidlm.server.LocalApiServer
import com.jondude1.androidlm.settings.AppSettings
import com.jondude1.androidlm.tuner.Planner
import com.jondude1.androidlm.tuner.TuneStore
import com.jondude1.androidlm.tuner.Tuner
import com.jondude1.androidlm.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AndroidLmApp : Application() {
    lateinit var c: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        c = AppContainer(this)
    }
}

val Context.container: AppContainer get() = (applicationContext as AndroidLmApp).c

/** Wires everything together. One instance for the life of the process; the UI and the HTTP server share it. */
class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = AppSettings(app)
    val crashGuard = CrashGuard(app)
    val engine = LlmEngine(app, crashGuard)
    val sensors = Sensors(app)
    val profile: DeviceProfile = DeviceProfiler.read(app)
    val tuneStore = TuneStore(app, profile.fingerprint)
    val planner = Planner(profile, sensors, tuneStore, settings)
    val tuner = Tuner(engine, profile, planner, tuneStore, sensors)
    val models = ModelStore(app, settings)
    val hf = HfClient
    val downloads = DownloadManager(settings, models, scope)
    val chats = ChatStore(app)
    val updater = Updater(app, settings)
    val server = LocalApiServer(engine, models, settings, { id -> ensureLoaded(id) }, scope)

    /** Set when the previous run died inside a native step. Shown once on the Device screen. */
    @Volatile var lastCrash: String? = null

    private val loadLock = Mutex()

    init {
        crashGuard.consumeLeftover()?.let { cr ->
            if (cr.deviceName != "CPU") {
                tuneStore.markBad(cr.modelId, cr.deviceName)
                lastCrash = "The graphics backend \"${cr.deviceName}\" crashed while running ${cr.kind}; it will not be used for this model again. Running on the CPU instead."
            } else {
                lastCrash = "The app stopped unexpectedly during ${cr.kind}. The model may be too big for this phone."
            }
        }
        scope.launch { models.refresh() }
    }

    /** Loads [model] with the best known configuration, falling back to the CPU if a GPU backend fails. */
    suspend fun loadModel(model: LocalModel, forced: RunConfig? = null): Result<RunConfig> = loadLock.withLock {
        val st = engine.state.value
        if (st.modelId == model.id && st.status == com.jondude1.androidlm.engine.EngineStatus.Ready && forced == null) {
            return@withLock Result.success(st.runConfig!!)
        }
        val devices = engine.ensureInit().devices
        val plan = forced ?: planner.planFor(model, devices)
        var r = engine.load(model, plan)
        if (r.isFailure && plan.load.device >= 0) {
            tuneStore.markBad(model.id, plan.deviceName)
            r = engine.load(model, planner.defaultConfig(model))
        }
        if (r.isSuccess) settings.update { copy(lastModelId = model.id) }
        r
    }

    /** For the HTTP server: the requested model, else the loaded one, else the last used, else the first on the phone. */
    suspend fun ensureLoaded(id: String?): Result<LocalModel> {
        val list = models.models.value.filter { it.complete }
        val st = engine.state.value
        val target = list.firstOrNull { it.id == id }
            ?: (if (st.status == com.jondude1.androidlm.engine.EngineStatus.Ready) list.firstOrNull { it.id == st.modelId } else null)
            ?: list.firstOrNull { it.id == settings.value.lastModelId }
            ?: list.firstOrNull()
            ?: return Result.failure(IllegalStateException("No models on this phone yet"))
        return loadModel(target).map { target }
    }
}
