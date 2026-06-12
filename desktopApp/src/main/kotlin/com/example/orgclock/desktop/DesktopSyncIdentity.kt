package com.example.orgclock.desktop

import com.example.orgclock.sync.SyncPairingCode
import com.example.orgclock.sync.SyncPairingCodeCodec
import com.example.orgclock.sync.SyncPairingInvitation
import com.example.orgclock.sync.SyncPairingInvitationCodec
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.file.Path
import java.util.Locale

class DesktopSyncIdentity(
    private val port: Int = DEFAULT_PORT,
) {
    fun pairingCode(rootPath: Path, invitation: SyncPairingInvitation): String? {
        val host = findLanAddress() ?: return null
        val authority = "$host:$port"
        val credential = SyncPairingInvitationCodec.encode(invitation)
        return SyncPairingCodeCodec.encode(
            SyncPairingCode(
                peerId = deviceId(rootPath),
                deviceId = deviceId(rootPath),
                displayName = System.getProperty("user.name")?.takeIf { it.isNotBlank() }?.let { "$it desktop" }
                    ?: "Org Clock Desktop",
                publicKeyBase64 = credential,
                endpoint = "https://$authority",
            ),
        )
    }

    fun deviceId(rootPath: Path): String = "desktop-${stableRootKey(rootPath).hashCode()}"
    fun tlsIdentity(rootPath: Path): DesktopTlsIdentity = DesktopTlsIdentity.loadOrCreate(rootPath)

    private fun findLanAddress(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .asSequence()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { network ->
            network.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .map { address -> network to address }
        }
        .filter { (_, address) -> !address.hostAddress.startsWith("169.254.") }
        .sortedByDescending { (network, address) ->
            var score = if (address.isSiteLocalAddress) 100 else 0
            val label = "${network.name} ${network.displayName}".lowercase(Locale.ROOT)
            if (VIRTUAL_ADAPTER_MARKERS.any(label::contains)) score -= 50
            score
        }
        .map { (_, address) -> address.hostAddress }
        .firstOrNull()

    companion object {
        const val DEFAULT_PORT = 8787
        val VIRTUAL_ADAPTER_MARKERS = listOf("vpn", "virtual", "vethernet", "hyper-v", "wsl", "docker")
    }
}

internal fun stableRootKey(rootPath: Path): String {
    val canonical = runCatching { rootPath.toRealPath() }
        .getOrElse { rootPath.toAbsolutePath().normalize() }
        .toString()
    return if (System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("windows")) {
        canonical.lowercase(Locale.ROOT)
    } else {
        canonical
    }
}
