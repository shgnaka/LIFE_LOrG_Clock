package com.example.orgclock.desktop

import com.example.orgclock.sync.SyncPairingCode
import com.example.orgclock.sync.SyncPairingCodeCodec
import com.example.orgclock.sync.SyncPairingInvitation
import com.example.orgclock.sync.SyncPairingInvitationCodec
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.file.Path

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

    fun deviceId(rootPath: Path): String = "desktop-${rootPath.toString().hashCode()}"
    fun tlsIdentity(rootPath: Path): DesktopTlsIdentity = DesktopTlsIdentity.loadOrCreate(rootPath)

    private fun findLanAddress(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .asSequence()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { it.inetAddresses.toList().asSequence() }
        .filterIsInstance<Inet4Address>()
        .map { it.hostAddress }
        .firstOrNull { !it.startsWith("169.254.") }

    companion object {
        const val DEFAULT_PORT = 8787
    }
}
