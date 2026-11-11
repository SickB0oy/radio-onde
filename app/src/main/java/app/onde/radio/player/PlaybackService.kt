package app.onde.radio.player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import app.onde.radio.OndeApp
import app.onde.radio.data.Genres
import app.onde.radio.data.Station
import app.onde.radio.ui.MainActivity
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Service de lecture : garde l'appli en vie en arrière-plan, fournit la notification,
 * l'écran verrouillé, le Bluetooth, et le catalogue de stations pour Android Auto.
 *
 * Arborescence proposée à la voiture :
 *   Favoris · Récentes · Tendances · Styles › (un dossier par style) › stations
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as OndeApp

    /** Stations déjà montrées à la voiture, pour retrouver celle qu'on choisit. */
    private val known = ConcurrentHashMap<String, Station>()
    private var session: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, StationPlayer(app.engine.player), LibraryCallback())
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Le lecteur appartient à PlaybackEngine : on ne libère que la session.
        session?.release()
        session = null
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Choix d'une station

    /**
     * Quand la voiture (ou le Bluetooth) demande de lire une station, on passe par
     * PlaybackEngine au lieu de donner l'adresse brute au lecteur : pause du direct, titres, etc.
     */
    private inner class StationPlayer(player: Player) : ForwardingPlayer(player) {
        override fun setMediaItem(mediaItem: MediaItem) = playItems(listOf(mediaItem), 0)
        override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) = playItems(listOf(mediaItem), 0)
        override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) = playItems(listOf(mediaItem), 0)
        override fun setMediaItems(mediaItems: MutableList<MediaItem>) = playItems(mediaItems, 0)
        override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) = playItems(mediaItems, 0)
        override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) =
            playItems(mediaItems, startIndex)

        private fun playItems(items: List<MediaItem>, index: Int) {
            val item = items.getOrNull(index) ?: items.firstOrNull() ?: return
            val uuid = stationUuid(item.mediaId)
            if (uuid == null) {
                super.setMediaItems(items.toMutableList(), index.coerceAtLeast(0), 0)
                return
            }
            scope.launch {
                val station = known[uuid]
                    ?: app.library.data.value.let { d -> (d.favorites + d.recent).firstOrNull { it.uuid == uuid } }
                    ?: runCatching { app.api.byUuids(listOf(uuid)).firstOrNull() }.getOrNull()
                station?.let { app.engine.play(it) }
            }
        }
    }

    // ---------------------------------------------------------------- Catalogue pour Android Auto

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, "Radio Onde"), params))

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val station = stationUuid(mediaId)?.let { known[it] }
            return Futures.immediateFuture(
                if (station != null) LibraryResult.ofItem(stationItem(station), null)
                else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val items: List<MediaItem> = runCatching { children(parentId) }.getOrDefault(emptyList())
            LibraryResult.ofItemList(items, params)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            scope.launch {
                val count = runCatching { app.api.search(query, 30) }.getOrDefault(emptyList())
                    .onEach { known[it.uuid] = it }.size
                session.notifySearchResultChanged(browser, query, count, params)
            }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val stations = runCatching { app.api.search(query, 30) }.getOrDefault(emptyList())
            LibraryResult.ofItemList(stations.map(::stationItem), params)
        }

        /** Les éléments choisis dans la voiture n'ont pas d'adresse : on leur en donne une factice. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(
                mediaItems.map { item ->
                    if (item.localConfiguration != null) item
                    else item.buildUpon().setUri(Uri.parse("onde://${item.mediaId}")).build()
                }.toMutableList()
            )

        /** « Reprendre la lecture » depuis la voiture ou le Bluetooth : la dernière station écoutée. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val last = app.library.data.value.recent.firstOrNull()
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException("Aucune station récente"))
            val item = stationItem(last).buildUpon().setUri(Uri.parse("onde://${STATION_PREFIX}${last.uuid}")).build()
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(listOf(item), 0, 0))
        }
    }

    private suspend fun children(parentId: String): List<MediaItem> {
        val data = app.library.data.value
        val country = data.countryCode ?: Locale.getDefault().country.takeIf { it.length == 2 } ?: "FR"
        return when {
            parentId == ROOT -> listOf(
                folder(FAVORITES, "Favoris"),
                folder(RECENT, "Récentes"),
                folder(TRENDS, "Tendances"),
                folder(GENRES, "Styles"),
            )
            parentId == FAVORITES -> data.favorites.map(::stationItem)
            parentId == RECENT -> data.recent.map(::stationItem)
            parentId == TRENDS -> app.api.trending(country, 30).map(::stationItem)
            parentId == GENRES -> Genres.ALL.map { folder(GENRE_PREFIX + it.key, it.label) }
            parentId.startsWith(GENRE_PREFIX) -> {
                val genre = Genres.find(parentId.removePrefix(GENRE_PREFIX)) ?: return emptyList()
                app.api.byGenre(genre.tags, country, 30).map(::stationItem)
            }
            else -> emptyList()
        }
    }

    private fun folder(id: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
                    .build()
            )
            .build()

    private fun stationItem(station: Station): MediaItem {
        known[station.uuid] = station
        return MediaItem.Builder()
            .setMediaId(STATION_PREFIX + station.uuid)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(station.displayName)
                    .setSubtitle(station.subtitle)
                    .setArtist(station.subtitle)
                    .setStation(station.displayName)
                    .setArtworkUri(station.favicon.takeIf { it.startsWith("http") }?.let(Uri::parse))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                    .build()
            )
            .build()
    }

    private fun stationUuid(mediaId: String): String? =
        mediaId.takeIf { it.startsWith(STATION_PREFIX) }?.removePrefix(STATION_PREFIX)

    companion object {
        private const val ROOT = "root"
        private const val FAVORITES = "favoris"
        private const val RECENT = "recentes"
        private const val TRENDS = "tendances"
        private const val GENRES = "styles"
        private const val GENRE_PREFIX = "style:"
        private const val STATION_PREFIX = "station:"
    }
}
