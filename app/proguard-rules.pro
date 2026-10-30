# AIDL stubs for the OpenVPN for Android external API must keep their names,
# since the remote app resolves them by the interface descriptor string.
-keep interface de.blinkt.openvpn.api.** { *; }
-keep class de.blinkt.openvpn.api.** { *; }
