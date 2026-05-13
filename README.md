# android-network-inspector

A standalone macOS app that captures Android app network traffic — HTTP/HTTPS via `HttpURLConnection` and OkHttp, plus gRPC — by piggybacking on Android Studio's Network Inspector device-side agents over `adb`.

Decrypted (plaintext) traffic, response/request bodies, headers, and gRPC frames are intercepted via JVMTI bytecode rewriting inside the target app's ART runtime, exactly the way Studio does it. No certificate pinning gymnastics, no app SDK integration.

## Status

Proof-of-concept. End-to-end attach works against debuggable apps on emulator and physical devices. UI shows the live request table, headers/body detail, intercept rules. Hardening, polish, and broader device coverage are next.

## How it works

```
[macOS app]                                  [Android device]
                                                                           
DeviceChooser           ddmlib over adb       transport daemon (perfd)
PackageDropdown   ───────── push ──────────►  /data/local/tmp/perfd/
ColdStart/AttachRunning                       
                                                                           
                  am attach-agent / am start
                  ──────────────────────────► JVMTI Agent_OnAttach
                                              libjvmtiagent_<abi>.so
                                              ClassFileLoadHook
                                              + RetransformClasses
                                                                           
                  adb forward tcp:N           
                    localabstract:            
                    AndroidStudioTransport    
                                                                           
gRPC TransportService                         hooks installed on:
  Execute(ATTACH_AGENT)    ─────────────►    URL.openConnection
  Execute(CreateInspector) ─────────────►    OkHttpClient.networkInterceptors
  Execute(StartInspection) ─────────────►    ManagedChannelBuilder.forAddress
  GetEvents (server stream) ◄─────── events  
                                                                           
RowAggregator      ◄── NetworkInspectorProtocol.Event ── inspector dex
Compose RequestTable
```

The device-side binaries (`transport`, `libjvmtiagent.so`, `perfa.jar`, `network-inspector.jar`) are **not** committed to this repository. They are extracted from your local Android Studio installation at build time by the `syncStudioBundle` Gradle task.

## Requirements

- macOS (Apple Silicon or Intel)
- JDK 21 (the Gradle daemon is pinned to this in `gradle.properties` — adjust if needed)
- Android Studio installed locally (Meerkat or newer recommended)
- `adb` on `PATH` (`brew install --cask android-platform-tools` or via Studio SDK)
- An Android emulator or physical device with API 26+
- Target app must be **debuggable** (`android:debuggable="true"`)

## Setup

```bash
git clone https://github.com/jisungbin/android-network-inspector
cd android-network-inspector

# 1) Extract the Studio device-side assets into studio-bundle/.
#    Edit android.studio.path in gradle.properties if your Studio lives elsewhere.
./gradlew syncStudioBundle

# 2a) Launch the GUI
./gradlew :ui:run

# 2b) Or build the CLI
./gradlew :cli:installDist
./cli/build/install/cli/bin/cli list-devices
./cli/build/install/cli/bin/cli attach \
  --device <serial> \
  --package com.example.app \
  --activity com.example.app/.MainActivity
```

To package a `.dmg`:

```bash
./gradlew :ui:packageDmg
# Output: ui/build/compose/binaries/main/dmg/
```

## Usage (GUI)

1. **Refresh** — populate the device dropdown
2. **Device** — pick the emulator/device
3. **Package** — search and pick a third-party app (auto-detects whether it is running)
4. **Mode** — `Cold start` (force-stop and relaunch, most stable) or `Attach running` (live-attach to an existing PID, API 28+)
5. **Activity** — auto-resolved on package select; edit only if you need a non-launcher entry point
6. **Attach**

The Inspector screen shows the live request table. Selecting a row opens the request/response panel with headers and a body viewer (gzip auto-decoded, text/binary auto-detected). The right panel also hosts Intercept Rules — match a URL pattern and replace status code / body.

## Limitations

- **Debuggable APKs only.** Release builds reject `am attach-agent`.
- **R8-stripped OkHttp/HttpURLConnection bypasses interception.** If the inspector class is gone, hooks can't be installed.
- **Apps with their own JVMTI agent may conflict** (e.g. some hot-swap or RASP tooling). Tombstones with `SEGV` inside `libjvmtiagent.so` on `Agent_OnAttach` are the usual symptom — try cold-start mode first, or test against a clean sample app.
- Tracks all traffic from the moment of attach; pre-attach requests are not captured.
- `streamId` is currently hard-coded to `0` in commands. Acceptable for single-device usage but should be replaced by the deviceId reported on the `STREAM` event for robustness.

## Troubleshooting

A rolling log file is written to `~/Desktop/network-inspector.log`. Every adb shell command, attach step, gRPC event, and failure stack trace lands there. When something is wrong, that file is the first thing to read.

Common gotchas already handled by this app:

- adb forward IPv4-only — gRPC client uses `127.0.0.1` instead of `localhost`
- ddmlib `executeShellCommand` 2-arg default timeout (5s) kills the long-lived transport daemon — we use the 5-arg overload with `Long.MAX_VALUE`
- `nohup` / `setsid` cannot keep the daemon alive against `adbd`'s shell-cleanup semantics — we keep an `executeShellCommand` call blocking on a dedicated thread instead
- `perfa.jar` must live in the app's `code_cache/` (not just in `/data/local/tmp/perfd/`) or `Agent_OnAttach` crashes
- `pgrep -f` matches the calling shell — `pidof <name>` is reliable

If attach fails, scroll to the bottom of the log file — the diagnose block printed there includes `ls -la /data/local/tmp/perfd/`, the foreground transport run output, and a filtered `logcat` excerpt.

## Architecture (modules)

```
core/   — adb/ + deploy/ + transport/ + inspector/ + intercept/ + log/
cli/    — command-line entry point (list-devices, attach)
ui/     — Compose Desktop GUI (Home + Inspector screens, intercept rules)
```

## Acknowledgments

This project is glue around Android Studio's Network Inspector. All the heavy lifting — JVMTI agent, transport daemon, network inspector dex, and the protobuf schemas — comes from AOSP `tools/base` and is licensed Apache 2.0. This wrapper layer is the part that lives in this repository.

## License

Apache License 2.0, matching the upstream AOSP assets it relies on.
