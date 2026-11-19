package app.datame.movil.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings as SystemSettings
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.datame.movil.Config
import app.datame.movil.Phase
import app.datame.movil.Sources
import app.datame.movil.SyncStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(model: MainViewModel) {
    val config by model.config.collectAsStateWithLifecycle()
    val status by model.status.collectAsStateWithLifecycle()
    val volumes by model.volumes.collectAsStateWithLifecycle()
    val buckets by model.buckets.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var mediaAllowed by remember { mutableStateOf(Sources.hasMediaPermission(context)) }
    var confirmUnpair by remember { mutableStateOf(false) }
    var editFolder by remember { mutableStateOf(false) }

    var filesAllowed by remember { mutableStateOf(Sources.hasAllFilesAccess(context)) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        mediaAllowed = Sources.hasMediaPermission(context)
        if (Build.VERSION.SDK_INT < 30) filesAllowed = Sources.hasAllFilesAccess(context)
        model.refreshBuckets(thenSync = true)
    }
    // Android gives the access to all the files in its own settings screen, and says nothing when coming back.
    val allFilesSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        filesAllowed = Sources.hasAllFilesAccess(context)
        if (filesAllowed) model.enableAllFiles()
    }
    val askAllFiles = {
        if (Build.VERSION.SDK_INT >= 30) {
            val own = Intent(SystemSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName))
            runCatching { allFilesSettings.launch(own) }
                .onFailure { allFilesSettings.launch(Intent(SystemSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else {
            permissions.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) model.addTree(uri)
    }

    // The first time: the permissions it needs, all at once.
    LaunchedEffect(Unit) {
        model.refreshVolumes()
        model.refreshBuckets()
        if (!mediaAllowed) {
            val asked = Sources.mediaPermissions().toMutableList()
            if (Build.VERSION.SDK_INT >= 33) asked += Manifest.permission.POST_NOTIFICATIONS
            permissions.launch(asked.toTypedArray())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Logo(28)
                        Spacer(Modifier.size(10.dp))
                        Text("Datame", fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                StatusCard(
                    status, config,
                    onSync = model::syncNow,
                    onPause = { model.setPaused(true) },
                    onResume = { model.setPaused(false) },
                    onRepair = { confirmUnpair = true },
                    onPermission = { if (config.photos && !mediaAllowed) permissions.launch(Sources.mediaPermissions()) else askAllFiles() },
                )
            }

            item {
                Section("Qué enviar") {
                    SwitchRow("Fotos y vídeos", "De las carpetas de la galería que elijas", config.photos) { on -> model.change { it.copy(photos = on) } }
                    if (config.photos) {
                        if (!mediaAllowed) {
                            Notice("Hace falta permiso para ver las fotos y vídeos.", "Dar permiso") { permissions.launch(Sources.mediaPermissions()) }
                        } else {
                            SwitchRow("Todas las carpetas de fotos", "También las de WhatsApp, descargas, capturas…", config.allBuckets) { on -> model.change { it.copy(allBuckets = on) } }
                            if (!config.allBuckets) {
                                for (bucket in buckets) {
                                    Row(
                                        Modifier.fillMaxWidth().clickable {
                                            model.change { c -> c.copy(buckets = if (bucket.id in c.buckets) c.buckets - bucket.id else c.buckets + bucket.id) }
                                        }.padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(checked = bucket.id in config.buckets, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                                        Text(bucket.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${bucket.count}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                if (buckets.isEmpty()) Hint("No hay fotos en este móvil todavía.")
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    SwitchRow("Todo lo demás", "Descargas, documentos, PDF, música… de todo el móvil, con sus carpetas", config.allFiles && filesAllowed) { on ->
                        if (!on) model.change { it.copy(allFiles = false) }
                        else if (filesAllowed) model.enableAllFiles()
                        else askAllFiles()
                    }
                    if (config.allFiles && !filesAllowed) {
                        Notice("Android pide permitir el acceso a todos los archivos.", "Permitir") { askAllFiles() }
                    }
                    if (config.allFiles && filesAllowed) Hint("Van a ${config.folder}/Download/…, ${config.folder}/Music/…, igual que en el móvil.")
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("Otras carpetas", fontWeight = FontWeight.Medium)
                    Hint(if (config.allFiles) "Ya se envía todo el móvil. Aquí puedes añadir carpetas de una tarjeta SD o de otras apps." else "Elige carpetas concretas: todo lo que haya dentro, y lo que llegue.")
                    for (tree in config.trees) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.size(12.dp))
                            Text(tree.label, Modifier.weight(1f))
                            IconButton(onClick = { model.removeTree(tree) }) { Icon(Icons.Outlined.Delete, contentDescription = "Dejar de enviar «${tree.label}»") }
                        }
                    }
                    OutlinedButton(onClick = { pickFolder.launch(null) }) { Text("Añadir una carpeta") }
                }
            }

            item {
                Section("Dónde") {
                    VolumePicker(config, volumes) { id, name -> model.change { it.copy(volume = id, volumeName = name) } }
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth().clickable { editFolder = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Carpeta")
                            Text(config.folder, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Cambiar", color = MaterialTheme.colorScheme.primary)
                    }
                    Hint("Las fotos van por carpeta y año: ${config.folder}/Camera/2026/…")
                }
            }

            item {
                Section("Cuándo") {
                    SwitchRow("Solo con Wi-Fi", "Para no gastar datos del móvil", config.wifiOnly) { on -> model.change { it.copy(wifiOnly = on) } }
                    SwitchRow("Solo mientras carga", "Para no gastar batería", config.chargingOnly) { on -> model.change { it.copy(chargingOnly = on) } }
                    Spacer(Modifier.height(8.dp))
                    Text("Qué fotos y documentos")
                    Spacer(Modifier.height(6.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(selected = config.sinceMillis == 0L, onClick = { model.change { it.copy(sinceMillis = 0) } }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Todos") }
                        SegmentedButton(selected = config.sinceMillis != 0L, onClick = { if (config.sinceMillis == 0L) model.change { it.copy(sinceMillis = System.currentTimeMillis()) } }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Solo los nuevos") }
                    }
                    Hint(if (config.sinceMillis == 0L) "También los que ya había en el móvil." else "Los que aparezcan desde el " + DateUtils.formatDateTime(context, config.sinceMillis, DateUtils.FORMAT_SHOW_DATE) + ".")
                    TextButton(onClick = { openBatterySettings(context) }) { Text("Permitir que funcione en segundo plano") }
                }
            }

            item {
                Section("Este móvil") {
                    Text(config.deviceName.ifBlank { "Móvil" }, fontWeight = FontWeight.Medium)
                    Hint("NAS: " + (config.addresses.firstOrNull()?.removePrefix("https://") ?: "—"))
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { confirmUnpair = true }) { Text("Desemparejar", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("¿Desemparejar este móvil?") },
            text = { Text("Dejará de enviar ficheros. Lo que ya envió se queda en la casa. Para volver, crea otro código en la web.") },
            confirmButton = { TextButton(onClick = { confirmUnpair = false; model.unpair() }) { Text("Desemparejar", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Cancelar") } },
        )
    }
    if (editFolder) {
        var text by remember { mutableStateOf(config.folder) }
        AlertDialog(
            onDismissRequest = { editFolder = false },
            title = { Text("Carpeta en la casa") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Dentro del volumen") }) },
            confirmButton = {
                TextButton(onClick = {
                    val clean = "/" + text.split('/').map { it.trim() }.filter { it.isNotEmpty() && it != "." && it != ".." }.joinToString("/")
                    model.change { it.copy(folder = if (clean == "/") "/Móvil" else clean) }
                    editFolder = false
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { editFolder = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun StatusCard(
    status: SyncStatus,
    config: Config,
    onSync: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRepair: () -> Unit,
    onPermission: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    data class Look(val icon: ImageVector, val title: String, val tint: Color, val container: Color)
    val look = if (config.paused) Look(Icons.Outlined.PauseCircle, "En pausa", colors.onTertiaryContainer, colors.tertiaryContainer) else when (status.phase) {
        Phase.IDLE -> Look(Icons.Outlined.Schedule, "Aún no se ha enviado nada", colors.onSurfaceVariant, colors.surface)
        Phase.CONNECTING -> Look(Icons.Outlined.Search, "Buscando el NAS…", colors.primary, colors.surface)
        Phase.SCANNING -> Look(Icons.Outlined.Search, "Mirando qué hay nuevo…", colors.primary, colors.surface)
        Phase.PAUSED -> Look(Icons.Outlined.PauseCircle, "En pausa", colors.onTertiaryContainer, colors.tertiaryContainer)
        Phase.UPLOADING -> Look(Icons.Outlined.CloudUpload, "Enviando ${status.done + 1} de ${status.total}", colors.primary, colors.primaryContainer)
        Phase.DONE -> if (status.pending == 0 && status.failures == 0) Look(Icons.Outlined.CheckCircle, "Todo está en casa", colors.primary, colors.primaryContainer)
        else Look(Icons.Outlined.Schedule, "Quedan ${status.pending} por enviar", colors.onTertiaryContainer, colors.tertiaryContainer)
        Phase.NO_NAS -> Look(Icons.Outlined.CloudOff, "No encuentro el NAS", colors.onTertiaryContainer, colors.tertiaryContainer)
        Phase.UNAUTHORIZED -> Look(Icons.Outlined.Lock, "Este móvil ya no está autorizado", colors.error, colors.errorContainer)
        Phase.STANDBY -> Look(Icons.Outlined.CloudOff, "El NAS está en espera", colors.onTertiaryContainer, colors.tertiaryContainer)
        Phase.FULL -> Look(Icons.Outlined.ErrorOutline, "No queda espacio", colors.error, colors.errorContainer)
        Phase.NO_PERMISSION -> Look(Icons.Outlined.PhotoLibrary, "Falta un permiso", colors.onTertiaryContainer, colors.tertiaryContainer)
        Phase.ERROR -> Look(Icons.Outlined.ErrorOutline, "Algo no va bien", colors.error, colors.errorContainer)
    }
    Surface(shape = RoundedCornerShape(20.dp), color = look.container) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(look.icon, contentDescription = null, tint = look.tint, modifier = Modifier.size(32.dp))
                Spacer(Modifier.size(12.dp))
                Text(look.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            if (!config.paused && status.phase == Phase.UPLOADING && status.total > 0) {
                LinearProgressIndicator(progress = { status.done.toFloat() / status.total }, modifier = Modifier.fillMaxWidth())
                if (status.currentName.isNotEmpty()) {
                    val bytes = if (status.currentSize > 0) " · " + Formatter.formatShortFileSize(context, status.currentSent) + " de " + Formatter.formatShortFileSize(context, status.currentSize) else ""
                    Text(status.currentName + bytes, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val waiting = if (config.paused) null else waitingFor(context, config)
            when {
                config.paused -> Text(
                    if (status.pending > 0) "No se enviará nada hasta que lo reanudes. Quedan ${status.pending} por enviar." else "No se enviará nada hasta que lo reanudes.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                status.message.isNotEmpty() -> Text(status.message, style = MaterialTheme.typography.bodyMedium)
                status.phase == Phase.NO_NAS -> Text("Se enviará cuando vuelvas a casa.", style = MaterialTheme.typography.bodyMedium)
            }
            if (waiting != null && status.phase != Phase.UPLOADING) Text(waiting, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Text(summary(context, status), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            when {
                config.paused -> Button(onClick = onResume) { Text("Reanudar") }
                status.phase == Phase.UNAUTHORIZED -> Button(onClick = onRepair) { Text("Emparejar otra vez") }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (status.phase == Phase.NO_PERMISSION) Button(onClick = onPermission) { Text("Dar permiso") }
                    else Button(onClick = onSync, enabled = status.phase !in setOf(Phase.UPLOADING, Phase.CONNECTING, Phase.SCANNING)) { Text("Sincronizar ahora") }
                    OutlinedButton(onClick = onPause) { Text("Pausar") }
                }
            }
        }
    }
}

private fun summary(context: Context, status: SyncStatus): String {
    val parts = mutableListOf<String>()
    parts += if (status.sentTotal == 1L) "1 fichero en casa" else "${status.sentTotal} ficheros en casa"
    if (status.lastRun > 0) parts += "última vez " + DateUtils.getRelativeTimeSpanString(status.lastRun, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString().lowercase()
    return parts.joinToString(" · ")
}

/** Why it is not sending right now, when it is the network or the battery. */
private fun waitingFor(context: Context, config: Config): String? {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
    if (capabilities == null) return "Sin conexión."
    if (config.wifiOnly && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return "Esperando a una red Wi-Fi."
    return null
}

private fun openBatterySettings(context: Context) {
    runCatching { context.startActivity(Intent(SystemSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.size(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Notice(text: String, action: String, onAction: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun VolumePicker(config: Config, volumes: List<app.datame.movil.RemoteVolume>, onPick: (String, String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(enabled = volumes.isNotEmpty()) { open = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Volumen")
            Text(config.volumeName.ifBlank { config.volume }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        if (volumes.size > 1) Text("Cambiar", color = MaterialTheme.colorScheme.primary)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (volume in volumes) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(volume.name)
                            if (volume.quota > 0) Text("${volume.used * 100 / volume.quota} % usado", style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    onClick = { open = false; onPick(volume.id, volume.name) },
                )
            }
        }
    }
}
