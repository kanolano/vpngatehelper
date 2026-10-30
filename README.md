# VPN Gate Android client

Personal project. Makes using vpn gate more convenient.

[VPN Gate](https://www.vpngate.net/) is a pool of volunteer-run public VPN relays. Using it by
hand is a chore: open the site, scan a table, download an `.ovpn`, import it into a VPN client,
connect — then do the whole thing again when that relay dies, which happens a lot. This app is
that loop, collapsed into one tap.

## How it works

The useful thing about VPN Gate's API is that **every row of the server list already contains a
complete `.ovpn` profile**, base64-encoded, certificates and all. There's no config file to go
and fetch — the list response *is* the config.

```
GET vpngate.net/api/iphone/   ->  CSV, 15 columns, ~95 rows
                                   last column = base64 .ovpn
          |
     parse + decode             ->  protocol, remote host:port
          |
     tap a row                  ->  hand the profile to...
          |
  OpenVPN for Android           ->  runs the tunnel
```

Quick Connect adds a scoring pass over that list, TCP-probes the top few to throw out relays
that are already dead, and falls over to the next candidate if a tunnel doesn't come up.

## Why there's a second app involved

The tunnel itself is run by [OpenVPN for Android](https://github.com/schwabe/ics-openvpn), which
this app drives over that app's AIDL interface. So you need both installed; the app notices when
the companion is missing and offers Play and F-Droid links.

That's a real cost, and it bought three things:

**Licensing.** ics-openvpn is GPLv2, but its docs carve out the AIDL interface explicitly — *"The
use of the AIDL API to control OpenVPN for Android from an external app is not subject to the
license."* Embedding the engine instead would pull this app's whole source under GPLv2.

**No native build.** Embedding means NDK, CMake, swig and submodules. This way it's all Kotlin.

**No foreground service.** The companion app owns the `VpnService` and its notification, so there's
no `FOREGROUND_SERVICE_*` permission here and no Android 14 service-type paperwork. The manifest is
`INTERNET` and one `<queries>` entry, and that's it.

`VpnEngine` is an interface, so if the tradeoff ever stops being worth it, an embedded backend can
go in behind it without the UI or data layers noticing.

## What this is and isn't

**It changes where you appear to be. It is not a privacy tool.** Worth being blunt about:

- The relays belong to volunteers, not to this app. Whoever runs the one you picked can see where
  your traffic goes.
- The profiles mandate **AES-128-CBC with SHA-1**. VPN Gate's servers require it and no client can
  change it — rewrite the cipher suite and the handshake simply fails. `VpnProfileTest` pins this
  so a well-meant "let's harden the crypto" patch breaks loudly instead of silently.
- There's no `remote-cert-tls server` or `verify-x509-name` in the profiles, and the `<ca>` is a
  single Let's Encrypt root. Adding stricter certificate checks breaks relays that otherwise work.

The app says all of this on first run rather than showing a padlock and letting you assume more.

Before sending a profile anywhere, the app checks the signing certificate of whatever is installed
as `de.blinkt.openvpn`. An impostor squatting that package name could otherwise report a healthy
tunnel while routing your traffic wherever it liked. By default the digest is pinned the first time
you bind and a later change is refused, which catches the companion app being swapped out
underneath you. For stronger protection, put a known-good SHA-256 digest in `trustedSigners` —
see `CompanionAppVerifier`. It ships empty on purpose: a wrong digest bricks the app, so it isn't
something to guess at.

## Build

The toolchain is already set up here; `gradle.properties` pins the JDK.

| | |
|---|---|
| JDK | Temurin 21 (any JDK with `javac` works) |
| Android SDK | platform 37.0, build-tools 37.0.0 — `$ANDROID_HOME` |
| AGP / Gradle / Kotlin | 9.5.0 / 9.8.2 / 2.4.21 (via the wrapper) |

If Gradle complains that the toolchain *"does not provide the required capabilities:
[JAVA_COMPILER]"*, your default `java` is a JRE with no compiler. Point Gradle at a real JDK from
your **user-level** properties file, so the path stays out of the repo:

```properties
# ~/.gradle/gradle.properties
org.gradle.java.home=/path/to/jdk-21
```

```bash
export ANDROID_HOME=$HOME/android-sdk

./gradlew :app:testDebugUnitTest     # 80 tests, no device needed
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Release builds are minified and shrunk (1.5 MB, against 20 MB for debug). They're signed only if
you supply a keystore, so a release can't accidentally go out with debug keys. Put this in
`~/.gradle/gradle.properties`:

```properties
vpngate.keystore=/path/to/release.jks
vpngate.storePassword=…
vpngate.keyAlias=…
vpngate.keyPassword=…
```

Without it `assembleRelease` still works and just leaves you an unsigned APK.

### Checking the live feed

Unit tests never touch the network. When something stops connecting and you want to know whether
upstream changed shape or the app broke:

```bash
VPNGATE_LIVE_TEST=1 ./gradlew :app:testDebugUnitTest --tests '*LiveFeedSmokeTest' --rerun-tasks -i
```

It hits the real endpoint and checks that every relay in it still produces a connectable profile.

### The test fixture

`app/src/test/resources/vpngate_sample.csv` is a real captured feed, and some parser tests assert
counts from **that snapshot** — 97 rows, 85 TCP / 12 UDP, 40 JP / 33 KR. Re-capturing it will break
those tests; that's expected, just update the numbers:

```bash
curl -s https://www.vpngate.net/api/iphone/ -o app/src/test/resources/vpngate_sample.csv
```

## Layout

```
app/src/main/
  aidl/de/blinkt/openvpn/api/     AIDL, copied verbatim from ics-openvpn remoteExample (Apache 2.0)
  java/de/blinkt/openvpn/api/     APIVpnProfile; the AIDL won't compile without it
  java/com/tb/vpngate/
    data/     VpnGateApi (FeedSource) · VpnGateCsvParser · ServerCache · ServerRepository
    vpn/      VpnEngine · OpenVpnAidlEngine · CompanionAppVerifier · VpnProfile · VpnState
    scoring/  ServerScorer (pure ranking) · ReachabilityProbe (TCP liveness)
    ui/       ServerListScreen · ServerRow · ConnectionBar · ServerListViewModel · Format · Theme
```

No Room: the feed is ~95 rows replaced wholesale every refresh, so a file plus its mtime does the
job without a schema or a code generator. No Retrofit or a JSON library either, because the payload
is CSV. No DI container for an object graph of four things.

## Third-party code

The files under `app/src/main/aidl/de/blinkt/openvpn/api/` and
`app/src/main/java/de/blinkt/openvpn/api/APIVpnProfile.java` are copied verbatim from
[schwabe/ics-openvpn](https://github.com/schwabe/ics-openvpn)'s `remoteExample` module, which is
Apache 2.0. ics-openvpn as a whole is GPLv2, but its documentation exempts this interface: *"The
use of the AIDL API to control OpenVPN for Android from an external app is not subject to the
license."* Each copied file keeps its original header.

No licence has been chosen for the rest of this project yet, which by default means all rights
reserved — add a `LICENSE` before expecting anyone to reuse it.

## Things that look wrong but aren't

Scars, mostly. Each of these has bitten or would have:

**`<queries>` in the manifest is load-bearing.** Take it out and `bindService` and
`getPackageInfo` against `de.blinkt.openvpn` fail *silently* on Android 11+. The symptom is
"tapping connect does nothing", with no error anywhere.

**The AIDL method order must match upstream exactly.** Binder dispatches on transaction index, not
name. Reorder or delete a method above one you call and you'll quietly invoke a different remote
method.

**`APIVpnProfile.writeToParcel` inverts its boolean.** That's an upstream bug, copied deliberately.
The parcel layout has to match the other process byte for byte.

**Parsing happens on `Dispatchers.Default`, not inline.** Splitting 1.3 MB and base64-decoding ~95
profiles measured ~9 ms on a desktop JVM, so tens of milliseconds on a phone — enough dropped
frames to feel, every load and every refresh.

**The response body is read with a cap.** `body.string()` is unbounded; a broken mirror or a
captive portal serving something enormous shouldn't be able to OOM the app.

**Every call into the companion app runs off the main thread,** including teardown. They're all
blocking cross-process transactions, and a wedged companion app shouldn't freeze the UI.

**`bind()` holds a mutex.** The eager bind at startup and an impatient first tap can otherwise both
see a null service and bind twice with the same `ServiceConnection`, which leaks the binding because
unbind only undoes one.

**Snackbars are shown on their own coroutine.** `showSnackbar` suspends until it's dismissed, so
dispatching it inline blocked the effect queue — a consent dialog stuck behind a snackbar looked
exactly like a dead tap.

**Status callbacks arrive on a Binder thread, and the stream is global.** It reports tunnels
started from the companion app's own UI too. A `Connected` with no server attached is shown as
"started outside this app" rather than claimed as ours, because mislabelling someone's exit point
is worse than admitting we don't know.

**Two consent dialogs, in this order:** `prepare()` (ics-openvpn's own confused-deputy guard), then
`prepareVPNService()` (Android's VPN consent). `withConsent` walks the chain by re-entering after
each Activity result, with a cap so a denial can't loop forever.

**Commented-out directives aren't directives.** Every profile ships a commented `#auth-user-pass`.
Anything that inspects profile contents has to strip comments first — a plain `contains()` here
gave a confidently wrong answer once already.

## Status

Verified here: debug and minified release builds, lint clean, 80 tests, R8 confirmed to keep
the AIDL stubs, and a live fetch-parse-profile run against the real endpoint (95/95 relays
connectable).

Debug builds run with **StrictMode** on (`src/debug/.../DebugApplication.kt`), which logs
main-thread disk and network access and leaked service registrations — the class of bug that got
past the compiler, lint and the test suite once already. Read it with
`adb logcat | grep -i StrictMode`.

**Never run on a device.** There's no KVM and no hardware attached to this machine, so no tunnel
has ever actually been opened. [TESTING.md](TESTING.md) is the checklist for that; the short
version of what's still unproven:

1. Both consent dialogs, in order.
2. A tunnel coming up, and **traffic actually routing** — compare your public IP before and after
   against the country you picked. "Connected" on its own proves nothing.
3. Quick Connect's failover, ideally against a deliberately stale list.
4. The missing-companion-app and untrusted-signer paths.
5. Rotation and backgrounding while connected.
