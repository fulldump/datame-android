package app.datame.movil

import android.util.Base64
import org.json.JSONObject

/**
 * What the QR of the NAS holds: where the house is, the fingerprint of its key
 * to recognize it, and the token of this phone. The web makes it in
 * «Conexiones → Añadir un móvil»; see node/devices.go in Datame.
 */
data class PairingCode(
    val addresses: List<String>,
    val pin: String,
    val token: String,
    val name: String,
    val volume: String,
    val folder: String,
) {
    companion object {
        private const val PREFIX = "datame-movil:"

        /** Reads a code, scanned or pasted. Throws IllegalArgumentException with a message for people. */
        fun parse(text: String): PairingCode {
            val encoded = text.trim().removePrefix(PREFIX).takeIf { text.trim().startsWith(PREFIX) }
                ?: throw IllegalArgumentException("Esto no es un código de Datame. Créalo en la web del NAS: Conexiones → Añadir un móvil.")
            val json = try {
                JSONObject(String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)))
            } catch (e: Exception) {
                throw IllegalArgumentException("El código está incompleto. Vuelve a copiarlo entero.")
            }
            if (json.optInt("v") != 1) throw IllegalArgumentException("Este código es de otra versión de Datame. Actualiza la app.")
            val addresses = json.optJSONArray("a")?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty()
            val pin = json.optString("p")
            val token = json.optString("t")
            if (addresses.isEmpty() || pin.length != 64 || !token.startsWith("dm1_")) {
                throw IllegalArgumentException("El código está incompleto. Vuelve a copiarlo entero.")
            }
            return PairingCode(
                addresses = addresses,
                pin = pin.lowercase(),
                token = token,
                name = json.optString("n"),
                volume = json.optString("vol"),
                folder = json.optString("dir").ifBlank { "/Móvil" },
            )
        }
    }
}
