package com.tb.vpngate.vpn

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

/**
 * Checks the identity of the app that will actually run the tunnel.
 *
 * Two modes, in order of strength:
 *
 *  1. An explicit allow-list of SHA-256 signing-certificate digests ([trustedSigners]). This is
 *     the stronger option, but it is empty by default: shipping a digest that has not been
 *     verified against a real release would brick the app for everyone, so it is left for the
 *     operator to populate (see README).
 *  2. Pin-on-first-use. The digest observed the first time we bind is recorded, and a later
 *     change is refused. That cannot tell a genuine install from an impostor on day one, but it
 *     does catch the companion app being swapped out underneath an existing install, which is
 *     the realistic attack.
 */
class CompanionAppVerifier(
    private val context: Context,
    private val store: PinnedSignerStore,
    private val trustedSigners: Set<String> = emptySet(),
) {
    sealed interface Result {
        data object Trusted : Result
        data object NotInstalled : Result
        data class Untrusted(val reason: String) : Result
    }

    suspend fun verify(packageName: String = OpenVpnAidlEngine.OPENVPN_PACKAGE): Result {
        val digests = try {
            signerDigests(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            return Result.NotInstalled
        }
        if (digests.isEmpty()) {
            return Result.Untrusted("its signing certificate could not be read")
        }

        if (trustedSigners.isNotEmpty()) {
            return if (digests.any { it in trustedSigners }) Result.Trusted
            else Result.Untrusted("it is not signed by a trusted key")
        }

        val pinned = store.pinnedSigner()
        return when {
            pinned == null -> {
                store.pinSigner(digests.first())
                Result.Trusted
            }
            pinned in digests -> Result.Trusted
            else -> Result.Untrusted(
                "its signing certificate changed since it was first used, which means it was " +
                    "reinstalled from a different source",
            )
        }
    }

    /** SHA-256 of every signing certificate currently associated with [packageName]. */
    private fun signerDigests(packageName: String): Set<String> {
        val pm = context.packageManager
        val signatures: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signingInfo = info.signingInfo ?: return emptySet()
            // With multiple signers every one matters; otherwise the history covers key rotation.
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            } ?: return emptySet()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
                ?: return emptySet()
        }
        return signatures.map { sha256Hex(it.toByteArray()) }.toSet()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
