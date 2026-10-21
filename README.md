<a href="https://github.com/sponsors/gi-os"><img src="https://raw.githubusercontent.com/gi-os/gi-os/main/images/bright-sponsors.svg" width="100%" alt="Thank you to my sponsors"></a>

# BrightRemote Lite

> **This is the Lite version.** BrightRemote Lite is built only with Light's official SDK, so it can go in the Light Tool Library. It does less than the full app. For the full app, get [BrightRemote](https://github.com/gi-os/BrightRemote) ([on BrightMarket](https://brightmarket.gzl.dev/app/com.gios.lightremote/)).

An Apple TV remote for the Light Phone III, built as an official Light SDK tool. Launcher label
**BrightRemote Lite**, tool id `com.gios.brightremotelite`.

This is the SDK-compatible cut of [BrightRemote](https://github.com/gi-os/BrightRemote), a
sideloaded app. Both speak Apple's Companion protocol directly: no server, no bridge, no app on
another device. The phone opens a TCP connection to the Apple TV, pairs with the four-digit code
the TV shows, and from then on sends encrypted button, touch, app and text commands. The protocol
stack was hand-ported to Kotlin from [pyatv](https://github.com/postlund/pyatv) for the original,
and it carries over here almost line for line.

## What it does

**The touchpad.** Nearly the whole panel is the surface you swipe on, with nothing drawn on it.
Swipe to move the tvOS focus, tap to select. Nothing goes to the TV until your finger passes the
touch slop, so a tap that wobbles is still a tap. Drags are scaled to half the TV's touch surface
(a full-panel drag on a 1:1 mapping reads as a flick), throttled to one sample per 16 ms, and sent
in order through a single queue so the TV never sees the finger jump backwards.

**The buttons.** Back, Home and More along the bottom. Holding Home opens Control Centre; holding
More jumps straight to typing. More slides up a drawer with skip back and forward 15 seconds,
play/pause, volume down, mute and volume up, a switch between the touchpad and a D-pad, the wheel
axis, Apps and Type. Playback buttons dim when the TV reports no media controls. Mute is volume
zero with the old level remembered, because Companion has no mute command; it only works on a TV
that reports volume control.

**Power** sits top right and is held for three seconds, with a vibration that climbs while you
hold and a firm pulse when it fires. Letting go early cancels it. The icon is lit when the TV is
on.

**The wheel** walks the tvOS focus: two notches per row, at most one step every 110 ms, each
step waiting for the previous press to finish. A stray reversed notch mid-turn is ignored; three
in a row count as a real change of direction. The axis (up/down or left/right) is a toggle in the
drawer. On the TV list and the app list the wheel scrolls the list instead.

**The volume rocker** drives the TV's volume while a TV is connected and the remote is on screen.
Otherwise the phone keeps it.

**Apps** lists what the TV can launch, with pins at the top. Tap one to open it on the TV.

**Type** sends text to the focused field on the TV using the Light keyboard. SEND appends,
REPLACE clears the field first. The top line shows what is in the TV's field.

**Now playing.** On tvOS 18 and later the TV pushes the position of what is playing, and a thin
bar under the top row shows how far in you are.

**Connecting.** The tool reconnects to the last TV when it opens and whenever it comes back on
screen. A failed connect is tried three times with a doubling delay, because a TV that just
dropped a session refuses pair-verify for a moment. If nothing answers at the stored address, the
tool searches for the TV by name once and retries at the new address (DHCP leases move). A link
the TV drops on its own is picked back up twice before the tool says so.

## Finding the TV

The full app used Android's `NsdManager` and a Wi-Fi `MulticastLock`. Both live behind
`getSystemService`, which the SDK blocks. So this tool sends its own mDNS query:

- One UDP datagram from an ordinary ephemeral port to `224.0.0.251:5353`, asking for
  `_companion-link._tcp.local` with the unicast-response bit set. A query that does not come from
  port 5353 is a "legacy unicast" query (RFC 6762, section 6.7), and the TV answers it directly to
  the sending port. The reply is plain unicast UDP, so no multicast lock is needed.
- The PTR, SRV, TXT and A records are parsed by `discovery/Mdns.kt`, a small pure-Kotlin DNS
  reader with tests. If a reply leaves something out, the tool asks for just that.
- A search is a five-second burst with three queries. It runs when you open the TV list or tap
  SEARCH, never in the background.

Some networks block this entirely: guest and IoT networks with client isolation, some mesh
systems, routers that filter multicast. For those, **ADDRESS** takes the TV's IP from
Settings > General > About. With no port, the tool asks the TV itself for its Companion port
(a unicast mDNS question to the TV's port 5353). If that also goes unanswered, type the port
after a colon, for example `192.168.1.20:49153`.

Pairing has to be allowed on the TV (Settings > AirPlay and HomeKit). A TV with it off is listed
but cannot be tapped.

## Protocol layer

Everything under `crypto/`, `proto/` and `companion/` is ported from BrightRemote with only the
package name and logging changed. It uses nothing beyond the JDK.

| Piece | File |
| --- | --- |
| OPACK, including the back-reference table | `proto/Opack.kt` |
| TLV8 | `proto/Tlv8.kt` |
| Binary property lists | `proto/BPlist.kt` |
| NSKeyedArchiver payloads for text input and now playing | `proto/RtiPayloads.kt`, `proto/NowPlaying.kt` |
| SRP-6a, 3072-bit, SHA-512 | `crypto/Srp.kt` |
| Ed25519, X25519 | `crypto/Curve25519.kt` |
| ChaCha20-Poly1305 | `crypto/ChaCha20Poly1305.kt` |
| HKDF-SHA512 | `crypto/Digest.kt` |
| Framing, request matching, pairing, commands | `companion/` |

The crypto did not need to move onto BouncyCastle. It is pure Kotlin over `BigInteger`,
`MessageDigest` and `Mac`, all of which a tool may use. Shipping BouncyCastle (about 8 MB) for a
dozen operations per pairing would not buy anything. `Curve25519.kt` is not constant time; the
only secrets are an ephemeral X25519 key and a long-term Ed25519 key used against a TV on the
local network.

How it is checked with no Apple TV in CI:

1. **Golden vectors** (`tool/src/test/resources/vectors.properties`), generated in the original
   repo from the libraries pyatv itself uses (pyatv's `opack.py` and `hap_tlv8.py`, `srptools`,
   `cryptography`). The Ed25519 and X25519 vectors also reproduce RFC 8032 and RFC 7748.
2. **A fake Apple TV** (`FakeAppleTv.kt`) that implements the device side of the handshake.
   `HandshakeTest` drives the real client against it over a loopback socket: pair, drop,
   reconnect with saved credentials, send encrypted commands.
3. **BouncyCastle cross-checks** (`BouncyCastleCrossCheckTest.kt`). BouncyCastle is on the SDK
   allowlist, so it is a test-only dependency here. Ed25519 keys and signatures, X25519
   agreement, ChaCha20-Poly1305 and HKDF-SHA512 are compared on seeded random inputs, and the
   SRP-6a shared secret is checked against BouncyCastle's SRP server.

## Storage

Pairing credentials hold the Ed25519 key the TV trusts. They are sealed with AES-256-GCM under a
key kept in the Android Keystore (`java.security.KeyStore`, alias
`brightremotelite.credentials`) and the sealed blob is written to the tool's DataStore. If the
tool's data were restored onto another phone, the key would not come with it and the TV would
show as unpaired, which is the intended result. The client identity, the last TV, pinned apps and
the touchpad and wheel settings are stored in plain DataStore preferences.

## What was dropped, and why

| Feature in BrightRemote | Why it is not here |
| --- | --- |
| Now-playing title and artwork (MRP over an AirPlay 2 tunnel, with a second AirPlay pairing) | Large second protocol stack, kept out of a lite cut. The progress bar from Companion's own `NowPlayingInfo` push is kept. |
| Media session and lock-screen controls | Needs `MediaSession` wired to a foreground service and a notification. The SDK exposes no notification API and blocks `startService`. |
| Keeping the link up in the background | Needs a foreground service (`startService`, `android.app.*`), which the SDK blocks. The tool reconnects when it comes back on screen instead. |
| `NsdManager` discovery with a `MulticastLock` | Both come from `getSystemService`, which is blocked. Replaced by the unicast-reply mDNS query above. |
| Reconnecting the moment the network comes back | Needs a `ConnectivityManager` callback started by the app. Reconnecting on return to the screen covers the same case when you pick the phone up. |
| BrightNotebook "watched" log and its content provider | Exposing data to another app needs a `ContentProvider`, which is blocked. |
| Shake to report, and automatic reports when the link drops | Needs `SensorManager` from `getSystemService`, and the reporter lives in Gio's `light-common` library, which is not on the allowlist. |
| Backup and restore of pairings | Built on `light-common`'s sync backup, which is not on the allowlist. Pairing again takes a few seconds. |
| BrightControl integration for the wheel click and camera key | Belongs to a separate accessibility-service app, not something a tool can talk to. |

## Network hosts

The tool talks only to devices on your local network:

- `224.0.0.251` UDP port 5353 (mDNS), when you search for TVs.
- Your Apple TV: UDP port 5353 when you add it by address, and TCP on its Companion port
  (usually 49152 or 49153) for the remote itself.

There is no internet traffic, no analytics and no crash reporting. `INTERNET` is the Android
permission that covers any socket, including local ones.

## Build

You need JDK 17 and the Android SDK (platform 36).

```sh
./gradlew -DlightSdk.toolOnly=true :tool:assembleDebug
./gradlew -DlightSdk.toolOnly=true :tool:testDebugUnitTest :tool:lintDebug
```

CI (`.github/workflows/build-tool.yml`) runs the tests, lint and the debug build on every push,
with the same flags as Light's tool builder. It publishes nothing.

To install on a phone or emulator running the Light SDK server, see `./gradlew :tool:uploadTool`
in `tool/build.gradle.kts`.

## Permissions

- `INTERNET`, for the TCP connection to the TV and the mDNS query.
- `ACCESS_NETWORK_STATE`, declared for network checks. Nothing else.

No cleartext-HTTP capability is requested: Companion is a binary TCP protocol, not HTTP.

## Tool Library submission notes

- The tool id, label and version are in `tool/lighttool.toml`. Bump `versionCode` for every
  submission.
- Everything is in the `tool` module. `sdk/`, `plugin/` and `lint-rules/` are upstream and
  unmodified, so this repository can be rebased onto
  [lightphone/light-sdk](https://github.com/lightphone/light-sdk).
- Runtime dependencies are only the SDK and what it brings (Compose, coroutines, DataStore).
  BouncyCastle is used in tests only.
- No reflection, no blocked imports, no custom manifest, no background work. Nothing refreshes on
  its own: a search runs when asked, and the app list loads when you open it.

## Not verified on hardware

The protocol is verified against vectors and a fake accessory, not a real Apple TV, from inside
this repository. The original app does run against real TVs. Things only a device can confirm:
that LightOS delivers the legacy-unicast mDNS replies to a tool, the Keystore key creation on the
LP3, and how the SDK's back handling feels with the remote as the first screen.

## Credits

Built on [lightphone/light-sdk](https://github.com/lightphone/light-sdk) (MIT); see `LICENSE`.
The Companion protocol code is ported from [gi-os/BrightRemote](https://github.com/gi-os/BrightRemote)
(MIT), which was written against [pyatv](https://github.com/postlund/pyatv) (MIT) as the reference.
