package app.datame.movil

import android.content.Context
import android.provider.MediaStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * When the phone sends: as soon as a photo or video appears, every hour in case
 * something was missed (documents have no signal), and when the person asks.
 * Always with the network and battery the person chose.
 */
object SyncScheduler {
    private const val PERIODIC = "periodic"
    private const val NEW_MEDIA = "new-media"
    private const val NOW = "now"
    const val KEY_REASON = "reason"

    private fun constraints(config: Config, watchMedia: Boolean = false) = Constraints.Builder()
        .setRequiredNetworkType(if (config.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(config.chargingOnly)
        .setRequiresBatteryNotLow(true)
        .apply {
            if (watchMedia) {
                // New photos and videos wake it up, a few seconds later, so a burst goes together.
                addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
                setTriggerContentUpdateDelay(10, TimeUnit.SECONDS)
                setTriggerContentMaxDelay(2, TimeUnit.MINUTES)
            }
        }
        .build()

    /** Sets everything up again: after pairing, or when the settings change. */
    fun schedule(context: Context) {
        val config = DatameApp.of(context).settings.config.value
        val work = WorkManager.getInstance(context)
        if (!config.paired) {
            work.cancelAllWork()
            return
        }
        work.enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints(config))
                .setInputData(workDataOf(KEY_REASON to PERIODIC))
                .build(),
        )
        watchMedia(context)
    }

    /**
     * The worker that waits for new media runs once: it asks for the next one when
     * it ends, after itself, so the one running is not cancelled.
     */
    fun watchMedia(context: Context, fromWorker: Boolean = false) {
        val config = DatameApp.of(context).settings.config.value
        if (!config.paired || !config.photos) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            NEW_MEDIA,
            if (fromWorker) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints(config, watchMedia = true))
                .setInputData(workDataOf(KEY_REASON to NEW_MEDIA))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build(),
        )
    }

    /** «Sincronizar ahora»: right away, with any network if the person is looking at the screen. */
    fun now(context: Context) {
        val config = DatameApp.of(context).settings.config.value
        if (!config.paired) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (config.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(KEY_REASON to NOW))
                .build(),
        )
    }

    fun isNewMedia(reason: String?) = reason == NEW_MEDIA
}
