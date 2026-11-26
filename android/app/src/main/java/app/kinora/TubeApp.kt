package app.kinora

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.kinora.data.Api
import app.kinora.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class TubeApp : Application() {
    lateinit var store: Store
    lateinit var api: Api
    lateinit var smb: app.kinora.data.SmbSource
    lateinit var thumbs: app.kinora.data.Thumbs
    lateinit var feed: app.kinora.data.Feed
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Player-level overrides for this session. null = use your default from Settings.
    var ovStream by mutableStateOf<String?>(null)
    var ovQuality by mutableStateOf<Int?>(null)
    var ovAutoplay by mutableStateOf<Boolean?>(null)

    val stream get() = ovStream ?: store.stream
    val quality get() = ovQuality ?: store.quality
    val autoplay get() = ovAutoplay ?: store.autoplay
    val hasOverrides get() = ovStream != null || ovQuality != null || ovAutoplay != null

    /** The TV interface: on a television (Android TV, Fire TV), or when Settings forces it. */
    val useTv: Boolean get() = when (store.layout) {
        "tv" -> true
        "phone" -> false
        else -> isTelevision
    }

    private val isTelevision: Boolean by lazy {
        val ui = getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        ui?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
            packageManager.hasSystemFeature("amazon.hardware.fire_tv")
    }

    fun resetOverrides() { ovStream = null; ovQuality = null; ovAutoplay = null }

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        api = Api(store)
        smb = app.kinora.data.SmbSource(store)
        api.smb = smb
        thumbs = app.kinora.data.Thumbs(this)
        feed = app.kinora.data.Feed(this)
    }
}

val Context.tubeApp: TubeApp get() = applicationContext as TubeApp
