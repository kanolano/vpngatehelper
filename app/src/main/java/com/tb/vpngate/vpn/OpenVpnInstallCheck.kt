package com.tb.vpngate.vpn

import android.content.Intent
import androidx.core.net.toUri

/**
 * Where to send the user when the companion app is missing.
 *
 * Both are offered because OpenVPN for Android is published on Play and F-Droid, and a device
 * without Play Services can still install it from F-Droid.
 */
object OpenVpnInstallCheck {

    private const val PKG = OpenVpnAidlEngine.OPENVPN_PACKAGE

    fun playStoreIntent(): Intent =
        Intent(Intent.ACTION_VIEW, "market://details?id=$PKG".toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** Used when no Play client can handle the market:// URI. */
    fun playStoreWebIntent(): Intent =
        Intent(
            Intent.ACTION_VIEW,
            "https://play.google.com/store/apps/details?id=$PKG".toUri(),
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    fun fdroidIntent(): Intent =
        Intent(Intent.ACTION_VIEW, "https://f-droid.org/packages/$PKG/".toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
