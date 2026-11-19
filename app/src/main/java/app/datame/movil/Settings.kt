package app.datame.movil

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A folder chosen with the system picker: documents, downloads, anything. */
data class TreeFolder(val uri: String, val label: String)

/** Everything the person decides, and the house this phone is paired with. */
data class Config(
    val paired: Boolean = false,
    val addresses: List<String> = emptyList(),
    val pin: String = "",
    val deviceName: String = "",
    val volume: String = "",
    val volumeName: String = "",
    val folder: String = "/Móvil",
    val photos: Boolean = true,
    val allBuckets: Boolean = false,
    val buckets: Set<String> = emptySet(), // bucket ids of MediaStore: Camera, Screenshots…
    val trees: List<TreeFolder> = emptyList(),
    val allFiles: Boolean = false, // everything else of the phone: downloads, documents, music…
    val paused: Boolean = false,
    val sinceMillis: Long = 0, // only what appeared after this; 0 is everything
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
)

/**
 * Settings, in SharedPreferences, and the token, encrypted with a key of the
 * Android Keystore that never leaves the phone.
 */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(read())
    val config: StateFlow<Config> = _config.asStateFlow()

    private fun read(): Config = Config(
        paired = prefs.getBoolean("paired", false),
        addresses = prefs.getString("addresses", null)?.let { json -> JSONArray(json).let { a -> (0 until a.length()).map { a.getString(it) } } }.orEmpty(),
        pin = prefs.getString("pin", "") ?: "",
        deviceName = prefs.getString("deviceName", "") ?: "",
        volume = prefs.getString("volume", "") ?: "",
        volumeName = prefs.getString("volumeName", "") ?: "",
        folder = prefs.getString("folder", "/Móvil") ?: "/Móvil",
        photos = prefs.getBoolean("photos", true),
        allBuckets = prefs.getBoolean("allBuckets", false),
        buckets = prefs.getStringSet("buckets", emptySet()).orEmpty(),
        trees = prefs.getString("trees", null)?.let { json ->
            JSONArray(json).let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> TreeFolder(o.getString("uri"), o.getString("label")) } } }
        }.orEmpty(),
        allFiles = prefs.getBoolean("allFiles", false),
        paused = prefs.getBoolean("paused", false),
        sinceMillis = prefs.getLong("sinceMillis", 0),
        wifiOnly = prefs.getBoolean("wifiOnly", true),
        chargingOnly = prefs.getBoolean("chargingOnly", false),
    )

    fun update(change: (Config) -> Config) {
        val next = change(_config.value)
        prefs.edit()
            .putBoolean("paired", next.paired)
            .putString("addresses", JSONArray(next.addresses).toString())
            .putString("pin", next.pin)
            .putString("deviceName", next.deviceName)
            .putString("volume", next.volume)
            .putString("volumeName", next.volumeName)
            .putString("folder", next.folder)
            .putBoolean("photos", next.photos)
            .putBoolean("allBuckets", next.allBuckets)
            .putStringSet("buckets", next.buckets)
            .putString("trees", JSONArray(next.trees.map { JSONObject().put("uri", it.uri).put("label", it.label) }).toString())
            .putBoolean("allFiles", next.allFiles)
            .putBoolean("paused", next.paused)
            .putLong("sinceMillis", next.sinceMillis)
            .putBoolean("wifiOnly", next.wifiOnly)
            .putBoolean("chargingOnly", next.chargingOnly)
            .apply()
        _config.value = next
    }

    /** The address that answered last goes first next time. */
    fun preferAddress(address: String) {
        val current = _config.value.addresses
        if (current.firstOrNull() == address) return
        update { it.copy(addresses = listOf(address) + current.filter { a -> a != address }) }
    }

    var token: String?
        get() = prefs.getString("token", null)?.let { decrypt(it) }
        set(value) {
            prefs.edit().apply { if (value == null) remove("token") else putString("token", encrypt(value)) }.apply()
        }

    /** Forgets the house: the phone goes back to the start. What was sent stays there. */
    fun unpair() {
        token = null
        prefs.edit().clear().apply()
        _config.value = Config()
    }

    // ---- The key of the token, in the Keystore ----

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = try {
        val sealed = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
        String(cipher.doFinal(sealed, 12, sealed.size - 12))
    } catch (e: Exception) {
        null // the key is gone, after a restore for example: it has to be paired again
    }

    private companion object {
        const val KEY_ALIAS = "datame-token"
    }
}
