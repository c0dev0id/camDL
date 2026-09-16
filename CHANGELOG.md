# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Project skeleton: a pure JVM `:protocol` module holding everything that can be
  tested without a device, and an Android `:app` module for the transports and UI.
- A shareable probe log. Every byte that crosses a transport boundary is recorded
  and rendered as `hexdump -C` style text that can be exported from the app. The
  renderer is reversible, so a log captured from a camera turns back into a
  byte-exact test fixture. Wi-Fi credentials, SSIDs, MAC addresses and serials are
  replaced with stable pseudonyms when the log is exported.
- DUML protocol support: frame encoding and decoding, a resynchronising stream
  framer for reassembling frames split across BLE notifications or packed into one
  datagram, and the pairing and Wi-Fi provisioning commands for DJI cameras.
- DJI camera connection: find the camera over Bluetooth LE, pair with it, ask it for a Wi-Fi
  access point, join that access point and confirm the camera is reachable. Each step appears
  in the probe log as a named stage with its timing, so a failure says where it stopped.
- A screen showing the probe log, with buttons to share it, switch recording off and on, and
  clear it. Credentials and identifiers are replaced by pseudonyms before anything is written.
- The probe log is written to disk as each entry happens and kept across restarts, with a
  banner marking where each run begins, so a crash or a force-stop no longer loses the
  capture that would have explained it.
- An update button that checks the published development build, downloads it when it differs
  from the installed one, and hands it to the system package installer.
- Continuous integration producing a signed APK as a rolling `dev` pre-release.

### Fixed

- Connecting no longer gives up when the camera's Bluetooth link ends during Wi-Fi setup. The
  camera stops answering and the link times out a few seconds after being asked to bring up its
  access point, which is what its radio switching to Wi-Fi looks like from the Bluetooth side —
  so the connection was being abandoned at the moment it started working.
- If no access point appears under the name the camera was given, camDL now offers to join one
  whose name starts with "Osmo" instead.
- Bluetooth disconnect reasons are written to the log in words rather than as a bare number.
- The app requested no internet permission, so connecting to the camera's access point would
  have been refused even when everything else worked.
