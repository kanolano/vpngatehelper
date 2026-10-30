# Testing on a phone, step by step

Everything below needs a real Android phone. This machine has no KVM and no nested
virtualisation, so an emulator can exercise the list and cache but can't be trusted for tunnels.

Work through the parts in order. Part A is setup, B installs the app, and C is the actual testing.
The ordering in C matters — it tests the "companion app missing" case *before* you install the
companion app, so you get that one for free.

Throughout, keep a second terminal open running the log watcher from
[§C0](#c0--start-the-log-watcher). Several checks are just "nothing bad appeared in the log".

---

## Part A — Set up the phone

**A1. Turn on developer options.**

On the phone: **Settings → About phone → Build number**, and tap it **7 times**. It counts down
("You are now 3 steps away…") and then says *You are now a developer*. You may need your PIN.

> On Samsung it's Settings → About phone → Software information → Build number. On Xiaomi it's
> Settings → About phone → MIUI version.

**A2. Turn on wireless debugging.**

**Settings → System → Developer options** (on some phones Developer options is at the top level of
Settings). Scroll to **Wireless debugging** and switch it **on**. Accept the prompt about the
network.

Leave this screen open — you need two numbers off it and they're not the same number.

**A3. Pair the phone with this machine.**

Still on the Wireless debugging screen, tap **Pair device with pairing code**. A dialog shows:

```
Wi-Fi pairing code
123456

IP address & Port
192.168.1.42:37103        <-- PAIRING port
```

In the terminal:

```bash
export PATH=$HOME/android-sdk/platform-tools:$PATH
adb pair 192.168.1.42:37103
```

It asks for the code; type the 6 digits. You want `Successfully paired`.

**A4. Connect.**

Dismiss the pairing dialog. The main **Wireless debugging** screen shows a *different* port under
**IP address & Port**:

```
192.168.1.42:41235      <-- CONNECTION port, not the pairing one
```

```bash
adb connect 192.168.1.42:41235
adb devices
```

You want one entry ending in `device`. If it says `unauthorized`, look at the phone — there's a
confirmation dialog waiting.

> **If `adb connect` just times out:** WSL2 is in NAT mode here, and on some networks it can't
> reach your LAN. Fall back to copying the APK over:
> ```bash
> cp app/build/outputs/apk/debug/app-debug.apk /mnt/c/Users/<your-windows-user>/Desktop/
> ```
> then move it to the phone and tap it to install (you'll need *Install unknown apps* allowed for
> whatever app you transfer with). You lose `adb logcat`, which several checks below rely on, so
> only do this if pairing genuinely won't work.

**A5. Do NOT install OpenVPN for Android yet.** §C1 tests what happens without it.

---

## Part B — Install the app

```bash
cd /path/to/vpngatetest
export ANDROID_HOME=$HOME/android-sdk
./gradlew :app:installDebug
```

You want `Installed on 1 device`. The app appears in your launcher as **VPN Gate** with a green
shield icon.

---

## Part C — The tests

### C0 — Start the log watcher

Second terminal, leave it running the whole session:

```bash
export PATH=$HOME/android-sdk/platform-tools:$PATH
adb logcat -c                                     # clear old noise first
adb logcat | grep -iE 'strictmode|vpngate|openvpn|ServiceConnection'
```

Debug builds have **StrictMode** on, so main-thread disk or network access and leaked service
registrations print here by themselves. A clean log is part of a pass.

---

### C1 — No companion app installed

**Do:** Open **VPN Gate**.

**Expect:**
- The server list fills in within a few seconds — countries, pings, speeds.
- The bar at the top says **OpenVPN for Android not installed**, with an **Install** button and a
  sentence explaining why it's needed.

**Then:** Tap **Install**. The Play Store (or your browser) should open on OpenVPN for Android.

**If instead** the bar looks normal and tapping a server does nothing at all, the `<queries>` entry
is missing from the manifest — that's the one failure mode that produces total silence.

**Now install it** — [Play](https://play.google.com/store/apps/details?id=de.blinkt.openvpn) or
[F-Droid](https://f-droid.org/packages/de.blinkt.openvpn/) — and come back. Note which one you
used; §C6 needs it.

---

### C2 — The list, with no tunnel involved

Do this before touching the VPN. It separates feed and cache problems from engine problems.

**C2a. Cold start.** Force-stop and reopen:

```bash
adb shell am force-stop com.tb.vpngate
```

**Expect:** list fills in. The country mix will be lopsided — mostly 🇯🇵 and 🇰🇷 with a handful
elsewhere. That's VPN Gate, not a bug. Status line reads `Updated just now · N servers`.

**C2b. Offline cache.** Turn on **airplane mode**, then:

```bash
adb shell am force-stop com.tb.vpngate
```

Reopen the app.

**Expect:** the list appears **immediately** — no spinner — and the status line reads
`Offline — saved list from … ago`.

**C2c. Failed refresh keeps the list.** Still in airplane mode, **pull down** on the list.

**Expect:** an error banner *above the rows*, saying it's showing the last saved list. The rows
stay. **A blank screen here is a bug** — that was the specific thing the cache is for.

**C2d. Successful refresh.** Airplane mode off, pull down again.

**Expect:** status line goes back to `Updated just now`.

**C2e. Filters.**
- Tap a country chip (e.g. **🇰🇷 KR 25**) → only Korean servers; status line reads
  `showing 25 of 95`. Tap it again to clear.
- Tap **TCP**, then **UDP** → list narrows each time. UDP will be a short list.
- Tap **Sort: Score** and pick **Ping** → order visibly changes, and servers showing `–` for ping
  sink to the **bottom**, not the top.

**C2f. Rotation.** Set a country filter and sort by Speed, then rotate the phone.

**Expect:** both survive.

**C2g. Process death.** This is the one rotation doesn't cover. With the filter and sort still set:

```bash
adb shell am force-stop com.tb.vpngate
```

Reopen.

**Expect:** filter and sort are still applied.

---

### C3 — The first real tunnel

**Do:** Tap any **🇯🇵 Japan** row (Japan has the most servers, so the best odds of a live one).

**Expect, in this exact order:**

1. A dialog from **OpenVPN for Android** asking whether to allow **VPN Gate** to control it →
   **OK / Allow**.
2. Android's own **Connection request** dialog ("… wants to set up a VPN connection") →
   **OK**.
3. The bar shows **Connecting…**, then **Connected**, with the country and `IP:port (TCP)`.
4. A **key icon** 🔑 appears in the phone's status bar.

If you get `Authentication failed`, that relay is most likely dead — try two or three others
before suspecting the app. VPN Gate relays go down constantly.

**C3a. Now prove traffic is actually routing.** This is the single most important check in this
document. `Connected` only means a handshake succeeded — it does **not** mean your packets are
going through the tunnel.

```bash
adb shell ip addr show tun0      # the interface must exist
adb shell ip route               # tun0 should own the default route
```

Then, on the phone, open a browser and go to **https://ifconfig.co**.

**Expect:** the IP and country shown match the relay you picked — a Japanese IP if you chose Japan.
If it still shows your home IP and country, the tunnel is up but **not carrying your traffic**, and
that is a real bug regardless of what the app says.

**C3b. DNS.** On the phone, visit **https://dnsleaktest.com** and run the standard test.

**Expect:** resolvers in the relay's country, not your ISP's.

**C3c. Stay connected.** Browse for 2–3 minutes. Some relays drop almost immediately; the bar
should follow along rather than sitting on a stale `Connected`.

**C3d. Disconnect.** Tap **Disconnect**.

**Expect:** key icon disappears, bar returns to **Not connected**.

---

### C4 — Quick Connect

**C4a.** Tap **Quick Connect**.

**Expect:** the bar shows **Trying Japan (1 of 3)** (or whichever country), then **Connected**.
Behind that, the app scored the list and TCP-probed the top few to skip dead relays.

**C4b. Failover.** This is the hardest thing here to trigger deliberately, because you don't get to
choose which relays are dead. Cheapest approach: disconnect and hit **Quick Connect** several times
over. Dead relays are common enough that you'll eventually see the counter advance to
**(2 of 3)** or **(3 of 3)** before it connects.

**Expect:** when it does advance, it keeps going and lands on something live. It must not hang on
a dead relay, and it must not give up on the first failure.

**C4c. All candidates fail.** If you ever see every attempt fail, you should get a message saying
it tried N servers and none connected, suggesting a refresh — not a silent return to idle.

---

### C5 — The regression probes

Each of these exists because something was actually wrong. None can be caught by the automated
tests, so this is the only place they get checked.

**C5a. A snackbar must not block the consent dialog.**
Filter to a country, then tap that same chip plus **TCP/UDP** until **no servers match**. Tap
**Quick Connect** → a message appears at the bottom. *Immediately*, while it's still visible, clear
the filters and tap a server.

**Expect:** the connect proceeds right away. If tapping a server appears to do nothing for several
seconds until the message fades, the effect queue is blocking again.

**C5b. Double-tap.** Tap a server row **twice, fast**.

**Expect:** one connection attempt. Watch the log — you should not see two profiles handed over.

**C5c. Bind race.** Force-stop, reopen, and tap a server **within the first second**, before the
list has settled. Then:

```bash
adb logcat -d | grep -i "ServiceConnection leaked"
```

**Expect:** no output. StrictMode also reports this as a leaked registration object.

**C5d. A tunnel started elsewhere.** Open **OpenVPN for Android** directly and start any profile
from its own UI. Then switch to **VPN Gate**.

**Expect:** the bar says a tunnel is up that was **started outside this app** — and no row shows
the `ACTIVE` badge. It must not claim one of its own servers is your exit point.

**C5e. No jank.** Pull to refresh five or six times in a row.

**Expect:** smooth. Also check the log — any `StrictMode policy violation` mentioning disk or
network on the main thread means work moved back onto the UI thread.

---

### C6 — Untrusted companion app

Do this last; it involves uninstalling things.

The app pins the signing certificate of OpenVPN for Android the first time it binds, and refuses to
hand over a profile if it later changes. The **Play and F-Droid builds are signed with different
keys**, which gives you a clean way to test that for real:

1. You've been using one of them (from §C1). Connect once more to be sure the pin is set.
2. Uninstall OpenVPN for Android.
3. Install it from **the other source** — F-Droid if you had Play, or Play if you had F-Droid.
4. Open **VPN Gate** and tap a server.

**Expect:** the bar says **OpenVPN for Android could not be trusted**, explains that the signing
certificate changed, and **no profile is sent**. You should not reach a consent dialog.

**To reset:** Settings → Apps → VPN Gate → Storage → **Clear storage**. That drops the pin and the
next bind re-pins to whatever is installed.

---

## When something goes wrong

```bash
# just this app
adb logcat --pid=$(adb shell pidof com.tb.vpngate)

# everything VPN-related, including the companion app
adb logcat | grep -iE 'openvpn|vpnservice|vpngate'

# StrictMode only
adb logcat | grep -i strictmode
```

**The handshake log lives in OpenVPN for Android, not here.** Open that app and read its log
screen. Cipher mismatches, certificate problems and connect timeouts all surface there, not in this
app's logs.

If relays stop connecting across the board, check whether upstream changed before digging into the
app:

```bash
VPNGATE_LIVE_TEST=1 ./gradlew :app:testDebugUnitTest --tests '*LiveFeedSmokeTest' --rerun-tasks -i
```

| Symptom | Look here first |
|---|---|
| Tapping connect does nothing at all | `<queries>` missing from the manifest, or the API permission was denied |
| `Authentication failed` | That relay is dead or refusing. Try several others first |
| `Connected` but websites show your real IP | Routing. Check `adb shell ip route` and `tun0` |
| Empty list and no error | Feed changed shape, or a captive portal answered 200. Run the live smoke test |
| `could not be trusted` | Companion app's signer changed. Reinstall it, or clear this app's storage to re-pin |
| Stutter when refreshing | Something moved parsing back to the main thread. Check StrictMode output |

---

## What the automated tests already cover

`./gradlew :app:testDebugUnitTest` runs **80 tests** on the JVM, no device needed — including 17
Compose UI tests via Robolectric that drive the real screen. They cover feed parsing, the cache and
offline behaviour, response size limits, scoring, profile building, state mapping, and the screen's
rendering and callbacks.

What they can't cover, and why the manual pass above exists: the AIDL binding to another process,
the two consent dialogs, whether a tunnel actually carries traffic, and anything involving real
threading on a real device.
