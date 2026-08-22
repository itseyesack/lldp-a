# lldp-a

An Android app that turns a phone/tablet with USB-OTG into a portable LLDP/CDP network
discovery tool. Plug in a USB-to-Ethernet adapter, connect it to a switch port, and the
app passively captures and decodes LLDP and CDP frames to show you the switch name, port,
chassis ID, VLAN, and management IP of whatever you're plugged into.

## Features

- **Passive LLDP + CDP capture**: reads raw Ethernet frames directly off USB Ethernet
  adapters and decodes both protocols, no root required.
- **Modular vendor chipset support**: adapters that expose a vendor-specific USB
  interface (no CDC-ECM fallback) are brought up directly via their own register map.
  Currently supported:
  - Realtek RTL8153 / RTL8152 / RTL8156
  - ASIX AX88179 / AX88178A (USB 3.0 Gigabit)
  - ASIX AX88772 / AX88772A / AX88772B (USB 2.0 Fast Ethernet), including Apple's A1277
    "Apple USB Ethernet Adapter" rebrand
- **Live switchport view**: merged LLDP/CDP fields for the current link session.
- **Diagnostic + raw packet logs** for troubleshooting adapter bring-up.

Adding a new chipset means implementing one `VendorAdapterDriver` and registering it -
the capture/ingestion pipeline and packet parser are chip-agnostic.

## Building

```bash
./gradlew assembleDebug
```

The APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

A signed release build requires a keystore at `app/release.jks` and the
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` environment variables set; without
them, `assembleRelease` produces an unsigned APK.

## CI

GitHub Actions builds debug and release APKs on every push/PR and publishes a GitHub
Release with the signed APK on pushes to `main` (see `.github/workflows/build.yml`).

## Documentation

- [docs/USAGE.md](docs/USAGE.md) — connecting an adapter, running a capture session,
  managing history, and a full walkthrough of the Settings page.
- [docs/WEBHOOK_TEMPLATES.md](docs/WEBHOOK_TEMPLATES.md) — sending session results to a
  webhook (Discord or any JSON endpoint), including the custom template/placeholder system.

## License

MIT
