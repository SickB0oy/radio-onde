package app.onde.radio

import android.app.Application
import app.onde.radio.alarm.AlarmScheduler
import app.onde.radio.data.LibraryStore
import app.onde.radio.data.RadioBrowserApi
import app.onde.radio.player.PlaybackEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class OndeApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var http: OkHttpClient
        private set
    lateinit var api: RadioBrowserApi
        private set
    lateinit var library: LibraryStore
        private set
    lateinit var engine: PlaybackEngine
        private set

    override fun onCreate() {
        super.onCreate()
        http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        api = RadioBrowserApi(http)
        library = LibraryStore(File(filesDir, "library.json"), appScope)
        engine = PlaybackEngine(this, http, api, library)
        // Le réveil est reprogrammé à chaque démarrage (après une mise à jour de l'appli, par exemple).
        AlarmScheduler.schedule(this, library.data.value.alarm)
        // Adresses à jour pour les favoris (au plus une fois par jour, en arrière-plan).
        appScope.launch { library.refreshStationsIfNeeded(api) }
    }
}
