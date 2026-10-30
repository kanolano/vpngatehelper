// Copied from schwabe/ics-openvpn remoteExample (Apache 2.0).
// The AIDL API is explicitly exempt from ics-openvpn's GPL: see doc/README.txt --
// "The use of the AIDL API to control OpenVPN for Android from an external app
// is not subject to the license."
//
// Trimmed to the subset this app uses. Method ORDER MUST MATCH the upstream
// interface exactly: Binder dispatches by transaction index, not by name, so
// reordering or removing a method above one we call would silently invoke the
// wrong remote method.
package de.blinkt.openvpn.api;

import de.blinkt.openvpn.api.APIVpnProfile;
import de.blinkt.openvpn.api.IOpenVPNStatusCallback;

import android.content.Intent;
import android.os.ParcelFileDescriptor;

interface IOpenVPNAPIService {
    List<APIVpnProfile> getProfiles();

    void startProfile(String profileUUID);

    /** Use a profile with all certificates etc. embedded,
     * old version which does not return the UUID of the added profile, see
     * below for a version that return the UUID on add */
    boolean addVPNProfile(String name, String config);

    /** start a profile using a config as inline string. Make sure that all needed data is inlined,
     * e.g., using <ca>...</ca> or <auth-user-pass>...</auth-user-pass>
     * See the OpenVPN manual page for more on inlining files */
    void startVPN(in String inlineconfig);

    /** This permission framework is used to avoid confused deputy style attack to the VPN
     * calling this will give null if the app is allowed to use the external API and an Intent
     * that can be launched to request permissions otherwise */
    Intent prepare(in String packagename);

    /** Used to trigger to the Android VPN permission dialog (VPNService.prepare()) in advance,
     * if this return null OpenVPN for ANdroid already has the permissions otherwise you can start the returned Intent
     * to let OpenVPN for Android request the permission */
    Intent prepareVPNService();

    void disconnect();
    void pause();
    void resume();

    void registerStatusCallback(in IOpenVPNStatusCallback cb);
    void unregisterStatusCallback(in IOpenVPNStatusCallback cb);

    void removeProfile(in String profileUUID);

    boolean protectSocket(in ParcelFileDescriptor fd);

    APIVpnProfile addNewVPNProfile(String name, boolean userEditable, String config);
    void startVPNwithExtras(in String inlineconfig, in Bundle extras);
    APIVpnProfile addNewVPNProfileWithExtras(String name, boolean userEditable, String config, in Bundle extras);

    @nullable APIVpnProfile getDefaultProfile();
    void setDefaultProfile(String profileUUID);
}
