# Development journal

## Software stack

| Piece | Choice |
|---|---|
| Build | Gradle 9.7.1, AGP 9.4.0, Kotlin 2.4.20, JVM target 17 |
| SDK | minSdk 33, compileSdk 37, targetSdk 36 |
| UI | Views + viewBinding, Material 3 DayNight, RecyclerView, ConstraintLayout |
| Async | Coroutines |
| HTTP | OkHttp 5 |
| Tests | JUnit 5 in `:protocol`; no Robolectric |
| CI | GitHub Actions, signed release APK published as the rolling `dev` pre-release |

Two modules. `:protocol` is pure Kotlin/JVM and holds the byte-level protocol work,
the transfer decision logic and the probe log. `:app` holds the Android transports,
the SAF destination and the UI.

## Target hardware

- **Primary:** DJI Osmo Action 5 Pro
- **Secondary:** Insta360 Ace Pro
- **Destination:** a USB drive attached to the phone, via the Storage Access Framework

## Key decisions

**Two modules, not three, and the split is about testability.** The developer cannot
build Android locally and every on-device test costs a CI build plus a sideload.
Anything that can be decided without a device — framing, checksums, reassembly,
skip/resume/conflict — lives in `:protocol` where it runs in seconds on CI. A separate
`:transport` module would have exactly one consumer, so it can be extracted later if
that ever changes.

**No step or state-machine framework.** An earlier design had camera profiles as a
`List<Step>` over a mutable context bag, so a debug UI could run steps individually.
That UI was dropped (iterate in code instead), which removed the only thing the
indirection bought. The connect chain is plain suspend functions composed in one
`connect()`, each wrapped in `probe.stage(...)`. Typed handles pass between them, so
the compiler enforces the ordering and there is no bag to keep consistent.

**The probe log is written to disk on every entry, flushed each time.** Slow and safe, by
explicit choice. The runs worth keeping are the ones that end in a crash, and those are exactly
the ones an in-memory log loses. Volumes are a few hundred lines per connection attempt, so the
cost is irrelevant next to never losing a capture. Entries are rendered already redacted, which
also makes the file safe to share as it stands — including entries from earlier runs, where a
fresh process could no longer know which values were secrets.

**Nothing in the reader may throw.** An uncaught exception in a coroutine takes the whole
process down. `DumlSession`'s reader catches everything, records it and fails the requests
waiting on it. This was a real crash: the camera drops the BLE link after pairing, `GattClient`
closed the notification channel with a cause, and `receiveAsFlow` rethrew it into a bare
`scope.launch`.

**The probe log is the deliverable, not a debugging aid.** With a slow test loop, a run
that reports only "it failed" is a wasted round trip. Everything the transports do is
recorded verbatim, including bytes nobody understands yet.

**One log format, not two.** The design called for a JSONL sink beside the rendered
text. Dropped: `Hexdump.parse` already reads the text format back into bytes, so a
second format would be two things to keep in step for no gain.

**Redaction at render time, never at record time.** Recorded events hold the real bytes,
because the app needs them and because a half-scrubbed capture is useless as a protocol
fixture. Everything that renders them — the screen, the file, the export — scrubs on the way
out, and pseudonyms are stable within a log so it can still be reasoned about. The consequence
is that a value must be registered with the redactor *before* the frame carrying it is logged,
since the file entry is written immediately and there is no later pass over it.

**Checksums computed, not tabulated.** DUML's CRC-8 and CRC-16 are ordinary reflected
CRCs with DJI seeds. Frames are a few dozen bytes, so a 256-entry table buys nothing and
costs 512 magic numbers that cannot be checked by eye.

**AGP's built-in Kotlin is lifted to match `:protocol`.** AGP 9 compiles Kotlin itself
and pins KGP 2.2.10. An Android module cannot read metadata from a newer compiler, so
the root buildscript raises AGP's built-in Kotlin to the version in the catalog rather
than letting the two modules drift.

**targetSdk 36 while compileSdk is 37.** Targeting 37 makes `ACCESS_LOCAL_NETWORK`
mandatory, and it is undocumented whether an app-requested local-only Wi-Fi network is
exempt. That variable is introduced on its own once the camera protocol works, so a
failure there is unambiguous.

**minSdk 33.** BLUETOOTH_SCAN/CONNECT arrived in 31, NEARBY_WIFI_DEVICES and the
non-deprecated GATT write and notify overloads in 33. Anything lower puts three compatibility
branches through the most delicate code in the app, for phones this will never run on.

**The channel logs bytes, the session logs frames.** A `DumlChannel` implementation records
what actually moved — which is not always what it was handed, since a frame can be split
across several GATT writes — and `DumlSession` records decoded frames. The log ends up with
the raw hexdump and its meaning side by side rather than the same bytes twice.

**GATT operations are serialised behind a mutex.** `BluetoothGatt` accepts one outstanding
operation and silently drops a second: no exception, no callback, the write just never
happens. Every operation also carries a timeout, or a call the stack accepts but never answers
would hold the lock forever and wedge everything after it.

**Characteristics are found by searching every service.** Which service holds `fff4` and
`fff5` on this camera is a guess, and a wrong guess would look exactly like the
characteristic being absent. The failure message lists what was actually discovered.

**The BLE scan is unfiltered.** What an Osmo Action 5 Pro advertises is not reliably
documented, and a `ScanFilter` built on a guess produces an empty scan indistinguishable from
a camera that is switched off. Every distinct device seen is logged once.

**Wi-Fi sockets are bound individually, never with `bindProcessToNetwork`.** A camera access
point has no internet, so Android keeps the default route on cellular and anything unbound
leaves by the wrong interface — failing in a way that looks exactly like a camera that is not
answering. `WifiLease` hands out pre-bound sockets rather than exposing the raw `Network`.

**The updater offers a *different* build, not a newer one.** CI publishes to a rolling `dev`
tag and names the APK after the commit it came from. Commit hashes have no order, so claiming
to know which of two builds is newer would be a lie; "this is not the build you are running" is
both true and sufficient. The asset-name parsing lives in the pure module and is tested,
because a CI rename would otherwise fail silently in either direction — never offering an
update, or offering the same one forever.

**The updater uses an unbound HTTP client.** It reaches GitHub over whatever network the phone
normally uses, which only works because camera sockets are bound individually. Had the process
been pinned with `bindProcessToNetwork`, the update check would be trying to reach GitHub
through a camera.

**File identity is name plus size, with no date.** SAF offers no reliable way to stamp a
destination file with the camera's capture time, and exFAT timestamps are 2-second
granular and timezone-less, so a date read back only says when the copy was written.
DJI filenames embed the capture timestamp anyway.

## Core features

M1 is built: probe log, DUML codec, CI, and the DJI connect chain through to reaching the
camera over its own access point. Media listing and transfer are M2 and M3.

1. **Find and connect to a camera.** DJI: BLE pair, provision Wi-Fi, join the camera's
   AP, open a DUML session over UDP.
2. **List media and let the user pick.**
3. **Pick a destination** — a SAF tree, normally on a USB drive.
4. **Transfer robustly.** Skip what is already complete, resume what is partial,
   suffix `_1`, `_2` on a genuine name collision. Resume is required rather than nice
   to have: DJI transfers are known to cut around 760 MB on some bodies.
5. **Export the probe log** for whatever did not work.

## What the DJI camera actually does

Established from a capture against an Osmo Action 5 Pro (`OsmoAction5Pro5ECB`):

- It advertises `OsmoAction5Pro<serial>`, and holds `fff3`, `fff4` and `fff5` in service `fff0`.
- It grants an MTU of 517 when asked for 500.
- Pairing works: `0x07/0x45` is answered with flags `0xC0` and payload `00 02`.
- **A response swaps the target.** Our `0x0702` came back as `0x0207`, so correlating on the
  target field would never have matched anything. Message id plus command set and id is what
  pairs a reply to its request.
- **`0x07/0x47` is never answered.** A few seconds later the Bluetooth link dies with GATT
  status 8, a supervision timeout rather than a clean teardown — which is what a combined radio
  switching itself to Wi-Fi access point mode looks like from the Bluetooth side. The connect
  chain therefore treats a link ending during provisioning as expected and goes on to join.
- Unrelated status frames arrive unsolicited on the same characteristic (`0x0d/0x02` from
  target `0x2505`), so a session that assumed the next frame in was its answer would misread
  the camera constantly.

## Open questions

- Does `ACCESS_LOCAL_NETWORK` gate traffic on an app-requested local-only network?
- Does the camera use the SSID and passphrase handed to it by `0x07/0x47`, or raise an access
  point named by itself? It never answers the command, so the only way to tell is whether the
  proposed name appears on the air; the prefix-match fallback exists to answer that.
- Do these cameras enumerate over USB as MTP (usable through SAF) or as mass storage
  (not natively mountable)? Untested, and it would be the fastest transfer path.
- Does the Insta360 Ace Pro answer the OSC HTTP API, or does it need the protobuf
  transport on TCP 6666?
