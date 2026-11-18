package app.datame.movil.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.datame.movil.Bucket
import app.datame.movil.Config
import app.datame.movil.DatameApp
import app.datame.movil.NasClient
import app.datame.movil.PairingCode
import app.datame.movil.RemoteVolume
import app.datame.movil.Sources
import app.datame.movil.SyncScheduler
import app.datame.movil.TreeFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = DatameApp.of(application)
    val config: StateFlow<Config> = app.settings.config
    val status = app.state.status

    private val _pairing = MutableStateFlow<PairingState>(PairingState.Idle)
    val pairing: StateFlow<PairingState> = _pairing.asStateFlow()

    private val _volumes = MutableStateFlow<List<RemoteVolume>>(emptyList())
    val volumes: StateFlow<List<RemoteVolume>> = _volumes.asStateFlow()

    private val _buckets = MutableStateFlow<List<Bucket>>(emptyList())
    val buckets: StateFlow<List<Bucket>> = _buckets.asStateFlow()

    sealed class PairingState {
        data object Idle : PairingState()
        data object Working : PairingState()
        data class Failed(val message: String) : PairingState()
    }

    /** Checks the code against the NAS and keeps it. */
    fun pair(text: String) {
        val code = try {
            PairingCode.parse(text)
        } catch (e: IllegalArgumentException) {
            _pairing.value = PairingState.Failed(e.message ?: "Código no válido")
            return
        }
        _pairing.value = PairingState.Working
        viewModelScope.launch {
            try {
                val (address, me) = withContext(Dispatchers.IO) { NasClient(code.pin, code.token).connect(code.addresses) }
                val volume = me.volumes.firstOrNull { it.id == code.volume } ?: me.volumes.firstOrNull()
                    ?: throw IOException("No tienes ningún volumen en el NAS. Pide a quien lo administra que te dé uno.")
                app.log.clear()
                app.state.clear()
                app.settings.token = code.token
                app.settings.update {
                    Config(
                        paired = true,
                        addresses = listOf(address) + code.addresses.filter { a -> a != address },
                        pin = code.pin,
                        deviceName = code.name,
                        volume = volume.id,
                        volumeName = volume.name,
                        folder = code.folder,
                    )
                }
                _volumes.value = me.volumes
                _pairing.value = PairingState.Idle
                SyncScheduler.schedule(getApplication())
            } catch (e: IOException) {
                _pairing.value = PairingState.Failed(e.message ?: "No se pudo conectar con el NAS.")
            }
        }
    }

    fun clearPairingError() {
        _pairing.value = PairingState.Idle
    }

    /** The volumes the person can send to, asked again to the NAS. */
    fun refreshVolumes() {
        val config = config.value
        val token = app.settings.token ?: return
        if (!config.paired) return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { NasClient(config.pin, token).connect(config.addresses) } }
                .onSuccess { (_, me) -> _volumes.value = me.volumes }
        }
    }

    /**
     * The folders of photos; the camera is chosen by default the first time. With [thenSync], it sends after
     * choosing: right after the permission is given, sending earlier would find no folder chosen and send nothing.
     */
    fun refreshBuckets(thenSync: Boolean = false) {
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { Sources.buckets(getApplication()) }
            _buckets.value = found
            val config = config.value
            if (config.paired && config.buckets.isEmpty() && !config.allBuckets && found.isNotEmpty()) {
                val camera = found.filter { it.name.equals("Camera", true) || it.name.equals("Cámara", true) }.map { it.id }.toSet()
                if (camera.isNotEmpty()) change { it.copy(buckets = camera) }
            }
            if (thenSync) syncNow()
        }
    }

    fun change(update: (Config) -> Config) {
        app.settings.update(update)
        SyncScheduler.schedule(getApplication())
    }

    fun addTree(uri: Uri) {
        val context = getApplication<Application>()
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val label = DocumentFile.fromTreeUri(context, uri)?.name ?: "Carpeta"
        change { config ->
            if (config.trees.any { it.uri == uri.toString() }) config
            else config.copy(trees = config.trees + TreeFolder(uri.toString(), label))
        }
    }

    fun removeTree(tree: TreeFolder) {
        val context = getApplication<Application>()
        runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(tree.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        change { it.copy(trees = it.trees.filter { t -> t.uri != tree.uri }) }
    }

    fun syncNow() = SyncScheduler.now(getApplication())

    fun unpair() {
        val context = getApplication<Application>()
        for (tree in config.value.trees) {
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(tree.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        app.settings.unpair()
        app.state.clear()
        app.log.clear()
        SyncScheduler.schedule(context)
    }
}
