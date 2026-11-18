package app.datame.movil.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@Composable
fun PairScreen(model: MainViewModel) {
    val pairing by model.pairing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pasting by remember { mutableStateOf(false) }
    val scan = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { model.pair(it) }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))
            Logo()
            Spacer(Modifier.height(24.dp))
            Text("Datame", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Tus fotos, vídeos y documentos, en tu casa. Este móvil los enviará solo al NAS cuando estés en tu red.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Para empezar", fontWeight = FontWeight.SemiBold)
                    Step(1, "En un ordenador, abre la web del NAS.")
                    Step(2, "Ve a «Conexiones» y pulsa «Añadir un móvil».")
                    Step(3, "Escanea aquí el código que aparece.")
                }
            }
            Spacer(Modifier.height(28.dp))
            when (val state = pairing) {
                MainViewModel.PairingState.Working -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Conectando con el NAS…")
                }
                else -> {
                    Button(
                        onClick = {
                            scan.launch(
                                ScanOptions()
                                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    .setPrompt("Apunta al código de la web del NAS")
                                    .setBeepEnabled(false)
                                    .setOrientationLocked(false),
                            )
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.size(10.dp))
                        Text("Escanear el código del NAS")
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = { pasting = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Icons.Outlined.ContentPaste, contentDescription = null)
                        Spacer(Modifier.size(10.dp))
                        Text("Pegar el código")
                    }
                    if (state is MainViewModel.PairingState.Failed) {
                        Spacer(Modifier.height(16.dp))
                        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                            Text(state.message, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }

    if (pasting) {
        val clipboard = remember { clipboardText(context) }
        var text by remember { mutableStateOf(if (clipboard.startsWith("datame-movil:")) clipboard else "") }
        AlertDialog(
            onDismissRequest = { pasting = false },
            title = { Text("Pegar el código") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Código de la web del NAS") },
                    placeholder = { Text("datame-movil:…") },
                    minLines = 3,
                    maxLines = 6,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pasting = false
                    model.pair(text)
                }, enabled = text.isNotBlank()) { Text("Emparejar") }
            },
            dismissButton = { TextButton(onClick = { pasting = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun Step(number: Int, text: String) {
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(24.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.size(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun Logo(size: Int = 72) {
    Box(
        Modifier.size(size.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape((size / 4).dp)),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size((size * 0.42).dp).background(MaterialTheme.colorScheme.onPrimary, RoundedCornerShape((size / 10).dp)))
    }
}

private fun clipboardText(context: Context): String =
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim() ?: ""
