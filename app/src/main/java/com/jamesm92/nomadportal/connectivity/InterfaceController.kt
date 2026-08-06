package com.jamesm92.nomadportal.connectivity

import kotlinx.coroutines.flow.StateFlow

/**
 * The single authoritative control point for which RNS interfaces are
 * actually live, and whether this device hosts a NomadNet node.
 *
 * "Authoritative" per nomadportal_android_handoff.md's "Main menu /
 * connectivity & privacy controls" section: these calls must actually
 * tear down/bring up the corresponding RNS `Interface` once the real
 * RNS/LXMF core (sequencing step 1, not yet extracted) exists — a
 * Settings toggle that only flips a persisted preference without this
 * actually happening would misrepresent what's live, which is exactly the
 * "fail closed on what you don't know" trust-model bug porting-notes.md §3
 * calls out from the original NomadPortal.
 *
 * [NoopInterfaceController] is the only implementation right now, and does
 * nothing beyond tracking the requested state — there is no RNS core to
 * control yet. Swap it for a real implementation (presumably backed by the
 * Chaquopy-embedded core) once that exists; nothing above this interface
 * (the Settings screen/ViewModel) should need to change when that happens.
 *
 * The connectivity toggles are independent and orthogonal by design — see
 * the handoff doc for why Bluetooth mesh and RNode-over-Bluetooth are NOT
 * the same toggle despite sharing a radio, and why local Wi-Fi discovery
 * is its own toggle rather than folded into TCP.
 */
interface InterfaceController {
    val tcpEnabled: StateFlow<Boolean>
    val bluetoothMeshEnabled: StateFlow<Boolean>
    val rNodeEnabled: StateFlow<Boolean>
    val wifiDiscoveryEnabled: StateFlow<Boolean>
    val nodeHostingEnabled: StateFlow<Boolean>

    /** Enables/disables all internet-based (TCP client/server) RNS interfaces. */
    suspend fun setTcpEnabled(enabled: Boolean)

    /**
     * Enables/disables the BLE-mesh RNS interface specifically. Does NOT
     * affect an RNode connected over Bluetooth — see [setRNodeEnabled].
     */
    suspend fun setBluetoothMeshEnabled(enabled: Boolean)

    /** Enables/disables the RNode interface, regardless of whether that RNode is USB- or Bluetooth-connected. */
    suspend fun setRNodeEnabled(enabled: Boolean)

    /**
     * Enables/disables local-network peer discovery (RNS's `AutoInterface`
     * — IPv6 link-local multicast on the current LAN/Wi-Fi segment, not a
     * connection to any specific configured address). Distinct from
     * [setTcpEnabled]: TCP is for reaching specific remote/internet hosts;
     * this is for auto-discovering other RNS nodes already on the same
     * local network. Uses multicast on an existing Wi-Fi connection, not
     * Wi-Fi scanning — doesn't touch `BLUETOOTH_SCAN`/location at all.
     */
    suspend fun setWifiDiscoveryEnabled(enabled: Boolean)

    /**
     * Enables/disables whether this device answers page/file requests over
     * whichever interfaces are currently up. Independent of the three
     * interface toggles above and independent of browsing capability —
     * browsing must keep working with this off.
     */
    suspend fun setNodeHostingEnabled(enabled: Boolean)
}
