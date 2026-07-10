package io.github.shgnaka.orgclock.synccore.security

import java.security.MessageDigest

internal actual fun sha256Hex(value: String): String =
    sha256Hex(value.toByteArray(Charsets.UTF_8))

internal actual fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}
