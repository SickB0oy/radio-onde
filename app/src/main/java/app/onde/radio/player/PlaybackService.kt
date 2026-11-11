package app.onde.radio.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.onde.radio.OndeApp
import app.onde.radio.ui.MainActivity

/**
 * Service de lecture : garde l'appli en vie en arrière-plan et fournit la notification,
 * les contrôles sur l'écran verrouillé et le Bluetooth (via Media3).
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val engine = (application as OndeApp).engine
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, engine.player)
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

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
        super.onDestroy()
    }
}
