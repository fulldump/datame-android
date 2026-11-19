package app.datame.movil

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.datame.movil.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

/**
 * One pass: finds the NAS, looks at what the phone has, and sends what the house
 * does not have yet, the newest first. Nothing is ever deleted from the house:
 * it is a copy, not a mirror.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = DatameApp.of(applicationContext)
        try {
            // Two passes at once would send the same files twice.
            if (!running.tryLock()) return Result.success()
            try {
                withContext(Dispatchers.IO) { sync(app) }
            } finally {
                running.unlock()
            }
        } finally {
            if (SyncScheduler.isNewMedia(inputData.getString(SyncScheduler.KEY_REASON))) {
                SyncScheduler.watchMedia(applicationContext, fromWorker = true)
            }
        }
        return Result.success()
    }

    private suspend fun sync(app: DatameApp) {
        val config = app.settings.config.value
        val token = app.settings.token
        val state = app.state
        if (!config.paired) return
        if (config.paused) {
            state.update { it.copy(phase = Phase.PAUSED, message = "", currentName = "") }
            return
        }
        if (token == null) {
            state.update { it.copy(phase = Phase.UNAUTHORIZED, message = "Este móvil tiene que volver a emparejarse.") }
            return
        }
        val started = System.currentTimeMillis()
        state.update { it.copy(phase = Phase.CONNECTING, message = "", lastRun = started, done = 0, total = 0, currentName = "", failures = 0) }

        val client = NasClient(config.pin, token)
        val me = try {
            val (address, me) = client.connect(config.addresses)
            app.settings.preferAddress(address)
            me
        } catch (e: NasClient.UnauthorizedException) {
            state.update { it.copy(phase = Phase.UNAUTHORIZED, message = e.message ?: "") }
            return
        } catch (e: IOException) {
            state.update { it.copy(phase = Phase.NO_NAS, message = e.message ?: "No encuentro el NAS.") }
            return
        }
        if (!me.active) {
            state.update { it.copy(phase = Phase.STANDBY, message = "Este NAS está en espera: otro de la casa está escribiendo.") }
            return
        }
        val volume = me.volumes.firstOrNull { it.id == config.volume }
            ?: run {
                state.update { it.copy(phase = Phase.ERROR, message = "El volumen elegido ya no está disponible para ti. Elige otro en la configuración.") }
                return
            }
        if (config.volumeName != volume.name) app.settings.update { it.copy(volumeName = volume.name) }

        state.update { it.copy(phase = Phase.SCANNING) }
        val mediaAllowed = Sources.hasMediaPermission(applicationContext)
        val filesAllowed = Sources.hasAllFilesAccess(applicationContext)
        val files = buildList {
            addAll(Sources.media(applicationContext, config))
            for (tree in config.trees) addAll(Sources.tree(applicationContext, tree, config))
            addAll(Sources.files(applicationContext, config))
        }
        val sent = app.log.all()
        val todo = files.filter { file ->
            val previous = sent[file.key]
            previous == null || previous.size != file.size || previous.modifiedSeconds != file.modifiedSeconds
        }
        state.update { it.copy(phase = Phase.UPLOADING, total = todo.size, done = 0, pending = todo.size) }
        if (todo.isNotEmpty()) startForeground(0, todo.size, "")

        var done = 0
        var failures = 0
        var skipped = 0 // no longer wanted: the person unchecked its folder meanwhile
        var inARow = 0
        var lastReport = 0L
        for (file in todo) {
            if (isStopped) break
            // The settings may change while it sends: they are looked at again before each file.
            val now = app.settings.config.value
            if (now.paused) {
                state.update { it.copy(phase = Phase.PAUSED, message = "", done = done, pending = todo.size - done - skipped, currentName = "", failures = failures) }
                return
            }
            if (!Sources.stillWanted(file, now)) {
                skipped++
                continue
            }
            val previous = sent[file.key]
            var path = previous?.path ?: join(config.folder, file.remoteDir, file.name)
            var replace = previous != null // a file that changed on the phone: its new version, over the old one
            var attempts = 0
            state.update { it.copy(currentName = file.name, currentSent = 0, currentSize = file.size) }
            while (true) {
                val result = try {
                    client.upload(
                        volume = volume.id,
                        path = path,
                        size = file.size,
                        modifiedSeconds = file.modifiedSeconds,
                        mime = file.mime,
                        replace = replace,
                        open = { applicationContext.contentResolver.openInputStream(file.uri) ?: throw FileNotFoundException(file.name) },
                        progress = { bytes ->
                            val now = System.currentTimeMillis()
                            if (now - lastReport > 700) {
                                lastReport = now
                                state.update { it.copy(currentSent = bytes) }
                            }
                        },
                        cancelled = { isStopped || app.settings.config.value.paused },
                    )
                } catch (e: FileNotFoundException) {
                    null // deleted while waiting its turn
                } catch (e: SecurityException) {
                    null // the permission for its folder was taken away
                }
                when (result) {
                    null -> break
                    UploadResult.Cancelled -> {
                        val paused = app.settings.config.value.paused
                        state.update { it.copy(phase = if (paused) Phase.PAUSED else Phase.DONE, done = done, pending = todo.size - done - skipped, currentName = "", failures = failures) }
                        return
                    }
                    UploadResult.Sent -> {
                        app.log.record(file.key, file.size, file.modifiedSeconds, path)
                        done++
                        inARow = 0
                        break
                    }
                    is UploadResult.Exists -> {
                        if (result.size == file.size || attempts > 20) {
                            // Already in the house, from before this phone kept count.
                            app.log.record(file.key, file.size, file.modifiedSeconds, path)
                            done++
                            break
                        }
                        // Another file with that name: this one goes next to it.
                        attempts++
                        path = numbered(path, attempts + 1)
                        replace = false
                    }
                    UploadResult.Unauthorized -> {
                        state.update { it.copy(phase = Phase.UNAUTHORIZED, message = "Este móvil ya no está autorizado. Vuelve a emparejarlo desde la web del NAS.") }
                        return
                    }
                    UploadResult.Standby -> {
                        state.update { it.copy(phase = Phase.STANDBY, message = "El NAS ha pasado a espera.") }
                        return
                    }
                    UploadResult.Full -> {
                        state.update { it.copy(phase = Phase.FULL, message = "El volumen «${volume.name}» está lleno.", pending = todo.size - done - skipped) }
                        return
                    }
                    is UploadResult.Failed -> {
                        failures++
                        inARow++
                        break
                    }
                }
            }
            if (inARow >= 3) {
                // The NAS stopped answering: away from home, or turned off. Next time.
                state.update { it.copy(phase = Phase.NO_NAS, message = "Se perdió la conexión con el NAS.", done = done, pending = todo.size - done - skipped, failures = failures) }
                return
            }
            state.update { it.copy(done = done, pending = todo.size - done - skipped, failures = failures, sentTotal = app.log.count()) }
            if (todo.size > 1) startForeground(done, todo.size, file.name)
        }

        val pending = todo.size - done - skipped
        state.update {
            it.copy(
                phase = if ((config.photos && !mediaAllowed) || (config.allFiles && !filesAllowed)) Phase.NO_PERMISSION else Phase.DONE,
                message = if (failures > 0) "$failures no se pudieron enviar: se intentará otra vez." else "",
                done = done,
                pending = pending,
                currentName = "",
                failures = failures,
                lastSuccess = if (pending == 0 && failures == 0) System.currentTimeMillis() else it.lastSuccess,
                sentTotal = app.log.count(),
            )
        }
    }

    // ---- The notification while sending ----

    // «Sincronizar ahora» asks to run at once; older Androids then need the notification first.
    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(NOTIFICATION_ID, notification(0, 0, ""), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private var lastForeground = 0L

    private suspend fun startForeground(done: Int, total: Int, name: String) {
        val now = System.currentTimeMillis()
        if (done != 0 && now - lastForeground < 2000) return
        lastForeground = now
        try {
            setForeground(ForegroundInfo(NOTIFICATION_ID, notification(done, total, name), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        } catch (e: Exception) {
            // Android may not allow it from the background: it goes on without, a while.
        }
    }

    private fun notification(done: Int, total: Int, name: String): Notification {
        val open = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(applicationContext, DatameApp.CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (total == 0) "Enviando a la casa" else "Enviando a la casa: $done de $total")
            .setContentText(name.ifEmpty { "Preparando…" })
            .setProgress(total, done, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private val running = Mutex()
        private const val NOTIFICATION_ID = 1

        /** «/Móvil/Ana» + «Camera/2026» + «IMG_1.jpg», with one slash between each. */
        fun join(folder: String, dir: String, name: String): String =
            ("/" + listOf(folder, dir, name).joinToString("/") { it.trim('/') }.split('/').filter { it.isNotEmpty() }.joinToString("/"))

        /** «IMG_1.jpg» → «IMG_1 (2).jpg». */
        fun numbered(path: String, n: Int): String {
            val slash = path.lastIndexOf('/')
            val dir = path.substring(0, slash + 1)
            val name = path.substring(slash + 1).replace(Regex(" \\(\\d+\\)(?=\\.[^.]*$|$)"), "")
            val dot = name.lastIndexOf('.')
            return if (dot > 0) "$dir${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$dir$name ($n)"
        }
    }
}
