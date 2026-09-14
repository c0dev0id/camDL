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
- Continuous integration producing a signed APK as a rolling `dev` pre-release.
