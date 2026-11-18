package app.datame.movil

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** The pieces the screen and the worker share. */
class DatameApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var state: SyncState
        private set
    lateinit var log: UploadLog
        private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        state = SyncState(this)
        log = UploadLog(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_SYNC, "Envío a la casa", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Mientras se envían fotos y documentos al NAS"
                setShowBadge(false)
            },
        )
        if (settings.config.value.paired) SyncScheduler.schedule(this)
    }

    companion object {
        const val CHANNEL_SYNC = "sync"
        fun of(context: Context) = context.applicationContext as DatameApp
    }
}
