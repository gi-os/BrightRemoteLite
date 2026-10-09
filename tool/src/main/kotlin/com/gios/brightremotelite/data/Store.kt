package com.gios.brightremotelite.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.gios.brightremotelite.companion.ClientIdentity
import com.gios.brightremotelite.companion.Credentials
import kotlinx.coroutines.flow.first
import java.security.SecureRandom

/** A TV this phone has paired with. */
data class PairedDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val credentials: Credentials,
)

/**
 * Paired devices, the client identity and a few preferences, in the tool's DataStore.
 *
 * The pairing credentials are sealed with a Keystore key before they are written (see [Vault]).
 * They hold the Ed25519 key the TV trusts, so anyone with them could press buttons on it.
 */
class Store(
    private val dataStore: DataStore<Preferences>,
    private val vault: Vault,
) {

    private companion object {
        val DEVICE_ID = stringPreferencesKey("client.device_id")
        val DEVICE_IDS = stringSetPreferencesKey("devices")
        val LAST_DEVICE = stringPreferencesKey("last_device")
        val PREFER_TOUCHPAD = booleanPreferencesKey("prefer_touchpad")
        val WHEEL_HORIZONTAL = booleanPreferencesKey("wheel_horizontal")
        val PINNED = stringSetPreferencesKey("pinned_apps")

        fun keyName(id: String) = stringPreferencesKey("device.$id.name")
        fun keyHost(id: String) = stringPreferencesKey("device.$id.host")
        fun keyPort(id: String) = intPreferencesKey("device.$id.port")
        fun keySealed(id: String) = stringPreferencesKey("device.$id.credentials")

        const val DEFAULT_PORT = 49153
    }

    private suspend fun prefs(): Preferences = dataStore.data.first()

    /**
     * A stable per-install identifier. The TV keys its paired-controller list on it, so making a
     * new one would orphan the pairing and add a second entry on the TV.
     */
    suspend fun identity(): ClientIdentity {
        prefs()[DEVICE_ID]?.let { return ClientIdentity(deviceId = it) }
        val fresh = randomDeviceId()
        var stored = fresh
        dataStore.edit { p ->
            val existing = p[DEVICE_ID]
            if (existing == null) p[DEVICE_ID] = fresh else stored = existing
        }
        return ClientIdentity(deviceId = stored)
    }

    suspend fun devices(): List<PairedDevice> {
        val p = prefs()
        return p[DEVICE_IDS].orEmpty().mapNotNull { id ->
            val credentials = p[keySealed(id)]?.let(vault::open)?.let { Credentials.parse(it) } ?: return@mapNotNull null
            PairedDevice(
                id = id,
                name = p[keyName(id)] ?: "Apple TV",
                host = p[keyHost(id)] ?: return@mapNotNull null,
                port = p[keyPort(id)] ?: DEFAULT_PORT,
                credentials = credentials,
            )
        }.sortedBy { it.name.lowercase() }
    }

    suspend fun save(device: PairedDevice) {
        val blob = vault.seal(device.credentials.serialize())
        dataStore.edit { p ->
            p[DEVICE_IDS] = p[DEVICE_IDS].orEmpty() + device.id
            p[keyName(device.id)] = device.name
            p[keyHost(device.id)] = device.host
            p[keyPort(device.id)] = device.port
            p[keySealed(device.id)] = blob
        }
    }

    /** Addresses move when a router reboots; the credentials stay valid. */
    suspend fun updateAddress(id: String, host: String, port: Int) {
        dataStore.edit { p ->
            p[keyHost(id)] = host
            p[keyPort(id)] = port
        }
    }

    suspend fun forget(id: String) {
        dataStore.edit { p ->
            p[DEVICE_IDS] = p[DEVICE_IDS].orEmpty() - id
            p.remove(keyName(id))
            p.remove(keyHost(id))
            p.remove(keyPort(id))
            p.remove(keySealed(id))
            if (p[LAST_DEVICE] == id) p.remove(LAST_DEVICE)
        }
    }

    suspend fun lastDeviceId(): String? = prefs()[LAST_DEVICE]

    suspend fun setLastDeviceId(id: String) {
        dataStore.edit { it[LAST_DEVICE] = id }
    }

    suspend fun preferTouchpad(): Boolean = prefs()[PREFER_TOUCHPAD] ?: true

    suspend fun setPreferTouchpad(value: Boolean) {
        dataStore.edit { it[PREFER_TOUCHPAD] = value }
    }

    suspend fun wheelHorizontal(): Boolean = prefs()[WHEEL_HORIZONTAL] ?: false

    suspend fun setWheelHorizontal(value: Boolean) {
        dataStore.edit { it[WHEEL_HORIZONTAL] = value }
    }

    suspend fun pinnedApps(): Set<String> = prefs()[PINNED].orEmpty()

    suspend fun togglePin(bundleId: String): Set<String> {
        var result: Set<String> = emptySet()
        dataStore.edit { p ->
            val current = p[PINNED].orEmpty()
            result = if (bundleId in current) current - bundleId else current + bundleId
            p[PINNED] = result
        }
        return result
    }

    private fun randomDeviceId(): String {
        // Shaped like a MAC address, because that is what the field carries on real clients.
        val bytes = ByteArray(6)
        SecureRandom().nextBytes(bytes)
        bytes[0] = ((bytes[0].toInt() and 0xFE) or 0x02).toByte() // locally administered
        return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
    }
}
