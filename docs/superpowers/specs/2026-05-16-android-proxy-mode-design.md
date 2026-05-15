# Proxy Mode (Charles/Proxyman-like) Design

**Date:** 2026-05-16
**Status:** Draft — awaiting user review before plan
**Author:** Jayden (with Claude)

## 1. Goal & Motivation

Add a Charles/Proxyman-style HTTP(S) MITM proxy capture mode to `android-network-inspector` as a **lighter alternative** to the existing JVMTI mode, targeted at the developer's own debuggable apps.

The JVMTI mode works but requires:
- Studio bundle extraction, `perfd` daemon push, agent attach, port forward, gRPC handshake
- Debuggable target, OkHttp/HttpURLConnection class signatures intact (R8 strips break it)

Proxy mode trades capability for setup friction:
- Capture only happens above TLS via MITM with a user-installed CA
- Works on any debug app that trusts user CAs via `networkSecurityConfig`
- No agent attach, no perfd, no JVMTI

The two modes are **complementary**, not competing. JVMTI keeps covering gRPC/HTTP/2 and pinning-immune capture; Proxy is the fast path for routine REST/HTTPS debugging.

## 2. Scope

### In scope (v1)

- Embedded HTTP/1.1 MITM proxy bound to all interfaces (LAN reachable from device)
- Persistent root CA generated once, pushed to device via adb
- Device-side automation: CA install intent + `settings put global http_proxy`
- Per-package learned-host cache; first attach is Learn mode, subsequent is Filtered
- Reuse of existing `NetworkRow` model and `:ui` Inspector / RequestDetail / RequestTable / InterceptRules screens
- Intercept rules applied at the proxy filter layer (URL pattern → fabricated response)
- Clean detach: restore device global proxy; fallback recovery on next launch

### Out of scope (v1, deferred)

- HTTP/2, gRPC, WebSocket capture (BrowserUp Proxy is HTTP/1.1; JVMTI mode covers this)
- Cert pinning bypass (Frida/objection integration)
- Non-debug / release apps, system CA installation, rooted device flows
- `:cli` support for proxy mode
- Multiple devices captured concurrently
- HAR export from proxy mode (existing JSON export already covers this in `:ui`)

## 3. Architecture

### Module layout

A new `:proxy` Gradle module is added, parallel to `:engine`. Both feed `NetworkRow` instances into `:ui`.

```
:log       (existing)  DiskLogger
:adb       (existing)  ddmlib wrappers
:protocol  (existing)  Studio transport gRPC (JVMTI mode only)
:engine    (existing)  JVMTI orchestration + NetworkRow model
:proxy     (NEW)       Embedded MITM proxy
:cli       (existing)  CLI entry (no proxy support v1)
:ui        (existing)  Compose Desktop GUI
```

Dependencies:

```
:ui ──► :engine
:ui ──► :proxy
:proxy ──► :adb
:proxy ──► :log
:proxy ──► :engine     (model reuse only — NetworkRow, ConnectionState, TransportProtocol)
```

`:proxy → :engine` is a one-way data-model dependency. `:engine` does not know `:proxy` exists. If `:engine`'s transport deps later make this awkward, the model types can be extracted to a `:model` module; we don't do that pre-emptively (YAGNI).

### `settings.gradle.kts` addition

```kotlin
include(":log")
include(":adb")
include(":protocol")
include(":engine")
include(":proxy")     // new
include(":cli")
include(":ui")
```

### Proxy engine choice: BrowserUp Proxy

Embedded JVM proxy server (BrowserUp Proxy, a maintained LittleProxy fork). Chosen over alternatives:

| Option | Verdict |
|---|---|
| **BrowserUp Proxy** | ✅ JVM-only, MITM cert auto-gen, lightweight. HTTP/1.1 only. |
| mitmproxy subprocess | Rejected: bundles Python runtime, IPC layer required, fights this project's pure-JVM `.app` story. |
| Netty/Ktor from scratch | Rejected for v1: MITM cert chain, CONNECT tunneling, ALPN all to be hand-rolled. |

HTTP/2 is a future migration concern; the `ProxyServer`/`CaptureFilter` interfaces are designed so the backend can be swapped without touching `:ui` or the aggregation layer.

## 4. Components

| Component | Responsibility | Key deps |
|---|---|---|
| `CertificateAuthority` | One-time root CA generation (RSA 2048 or EC, 10y validity) at macOS user data dir. Persists as PKCS#12. Exposes PEM + DER export for adb push. | Bouncy Castle |
| `LeafCertGenerator` | Per-SNI leaf cert issuance on demand, signed by root CA. LRU cache keyed by hostname. | CA |
| `ProxyServer` | Wraps `BrowserUpProxyServer`. Binds `0.0.0.0:<port>` (default 8765, with sequential retry up to 8769). HAR disabled. | BrowserUp Proxy |
| `CaptureFilter` (`HttpFiltersAdapter` subclass) | Hooks `clientToProxyRequest` / `proxyToServerRequest` / `serverToProxyResponse` / `proxyToClientResponse` / `serverToProxyResponseTimedOut`. Emits `ProxyCaptureEvent` into an unbuffered Channel. Applies intercept rules at request stage. | Netty/LittleProxy |
| `ProxyCaptureEvent` | Sealed type: `Started(connId, method, url, headers, ts)`, `RequestBody(connId, bytes)`, `ResponseStarted(connId, status, headers, ts)`, `ResponseBody(connId, bytes)`, `Closed(connId, ok, ts)`. | — |
| `ProxyRowAggregator` | Mirrors `:engine`'s `RowAggregator`. Consumes `ProxyCaptureEvent` → emits `NetworkRow` updates. | `:engine` model |
| `HostFilter` | Two modes: `Learn` (pass everything, emit discovered hosts) / `Filtered` (only whitelisted hosts pass downstream UI). Wildcards: `*.myapp.com`. | LearnedHostsStore |
| `LearnedHostsStore` | Persists per-package host whitelist to `~/Library/Application Support/network-inspector/learned-hosts/<pkg>.json`. Atomic write (tmp+rename). | — |
| `DeviceProxySetup` | adb-side automation: push CA cert; launch install intent; `settings put global http_proxy`; detach restore (`settings put global http_proxy :0`). | `:adb` |
| `ProxySession` (`AutoCloseable`) | Orchestrates the full lifecycle. UI talks only to this. Exposes `Flow<NetworkRow>`, `mode: StateFlow<FilterMode>`, `discoveredHosts: StateFlow<Set<String>>`. | All of above |

### Key design decisions

**Raw filter, not HAR.** BrowserUp's HAR integration buffers in memory and doesn't fit live streaming. `HttpFiltersAdapter` hooks emit events immediately.

**Body size cap.** Bodies >5 MB are truncated; `NetworkRow` gains `requestBodyTruncated: Boolean` and `responseBodyTruncated: Boolean` fields. The cap is hardcoded in v1; configurable later if needed.

**Connection ID collision avoidance.** Proxy-side IDs are issued from an `AtomicLong` starting at `Long.MIN_VALUE / 2`, well outside JVMTI's positive ID range.

**TransportProtocol enum extension.** A `PROXY` variant is added so the request table can show capture source. Existing enum values (`JAVA_NET`, `OKHTTP2`, `OKHTTP3`, `UNKNOWN`) untouched.

## 5. User Flow

### First attach on a device (cold path)

1. Home → mode toggle "Proxy" → Device + Package → Attach.
2. `ProxySession.attach()`:
   - Generate CA if absent (one-time, persists across runs).
   - Detect macOS LAN IPv4 (`NetworkInterface.getNetworkInterfaces()`, filter loopback/down/virtual).
   - Start `ProxyServer` on first free port in 8765–8769.
   - `adb push <ca.pem> /sdcard/Download/network-inspector-ca.crt`.
   - Fire install intent: `am start -a android.intent.action.VIEW -t application/x-x509-ca-cert -d file:///sdcard/Download/network-inspector-ca.crt`.
   - UI shows a guidance modal: "Confirm certificate install on device, then click Next" (with skip option for users who've installed before).
   - `adb shell settings put global http_proxy <mac_ip>:<port>`.
3. `LearnedHostsStore.load(packageName)`:
   - **Hit** → start in `Filtered` mode, jump straight to `InspectorScreen`.
   - **Miss** → start in `Learn` mode, top banner shows "🔍 Learning hosts (N discovered)".

### Learn → Filtered transition

- Learn-mode top panel lists discovered hosts with checkboxes.
- User checks the hosts that belong to their app → clicks "Save filter".
- `LearnedHostsStore.save(packageName, hosts)`.
- Mode flips to `Filtered`; already-captured rows whose host is unchecked are hidden retroactively.

### Re-attach (same device, same package)

1. Attach clicked → `DeviceProxySetup` verifies CA presence is implied if `adb shell settings get global http_proxy` already shows our setting; otherwise re-fire setup.
2. Learned hosts loaded → `Filtered` mode immediately.
3. Newly seen hosts not in whitelist → toast: "New host `X` seen — add to filter?".

### Detach

1. UI Detach.
2. `ProxySession.close()`:
   - `adb shell settings put global http_proxy :0` (CRITICAL — leaving this stuck on a port the user later closes will brick the device's network).
   - `ProxyServer.stop()`.
   - Cert remains on device for reuse.
3. Crash recovery: on next app launch, if the last-known device's `settings get global http_proxy` matches our proxy signature (host:port we previously bound), clean it up before showing the home screen.

### CA trust prerequisite (documented, not enforced)

This flow only works if the target debug app trusts user-installed CAs:

```xml
<!-- AndroidManifest.xml -->
<application android:networkSecurityConfig="@xml/network_security_config" ...>

<!-- res/xml/network_security_config.xml -->
<network-security-config>
  <debug-overrides>
    <trust-anchors>
      <certificates src="user" />
    </trust-anchors>
  </debug-overrides>
</network-security-config>
```

The UI surfaces a help link to this snippet on the failure path (see §7).

## 6. Data Flow & UI Integration

### Capture → row pipeline

```
Netty event loop
   │
   ▼
CaptureFilter (HttpFiltersAdapter)
   ├─ clientToProxyRequest(HttpRequest) ───► ProxyCaptureEvent.Started
   ├─ clientToProxyRequest(HttpContent) ───► RequestBody
   ├─ serverToProxyResponse(HttpResponse) ─► ResponseStarted
   ├─ serverToProxyResponse(HttpContent) ──► ResponseBody
   └─ serverToProxyResponseTimedOut /    ──► Closed(ok=true|false)
      proxyToClientResponse
        │
        ▼ Channel<ProxyCaptureEvent>(Channel.UNLIMITED)
        │
        ▼ HostFilter.allow(event)
        │
        ▼ ProxyRowAggregator.consume(event): NetworkRow?
        │
        ▼ SharedFlow<NetworkRow> ◄── collected by AppStore
```

### `NetworkRow` field mapping

| Field | Source |
|---|---|
| `connectionId` | `AtomicLong`, base `Long.MIN_VALUE / 2` |
| `startTimestamp` | request received nanos (parity with JVMTI) |
| `endTimestamp` | response complete nanos |
| `method`, `url` | `HttpRequest.method()` / `HttpRequest.uri()` (reconstructed full URL with scheme+host) |
| `protocol` | `TransportProtocol.PROXY` |
| `statusCode` | `HttpResponse.status().code()` |
| `requestHeaders`, `responseHeaders` | Netty `HttpHeaders` → `List<Pair<String, List<String>>>` |
| `requestBody`, `responseBody` | Aggregated `HttpContent` bytes. Gzip NOT decoded here — `:ui`'s `BodyDecoder` already handles it. |
| `state` | `IN_FLIGHT` until Closed; `COMPLETED` if `ok=true`, `FAILED` otherwise |

### `:ui` changes

```kotlin
sealed interface ActiveSession {
  data class Jvmti(val session: AttachSession) : ActiveSession
  data class Proxy(val session: ProxySession) : ActiveSession
}
```

- `AttachMode` (or new `CaptureMode`) gains a `Proxy` variant alongside `ColdStart` / `AttachRunning`.
- `HomeScreen` shows the mode picker; Proxy mode hides the Activity field.
- `AppStore` / `UiState` track `ActiveSession`.
- `InspectorScreen`, `RequestTable`, `RequestDetail`, `InterceptRulesScreen` — unchanged. They consume `Flow<NetworkRow>` and `interceptRules`; capture source is irrelevant.
- `StatusBar` adds source/mode/host-count badge: `Proxy · 127.0.0.1:8765 · learn (12 hosts)` or `Proxy · filtered (5 hosts)`.

### Intercept rules

The existing `InterceptRule` data class is reused. Application site differs:

- **JVMTI mode:** `RuleSender` pushes rules to the on-device inspector dex.
- **Proxy mode:** `CaptureFilter.clientToProxyRequest` matches URL → returns a fabricated `HttpResponse` instead of forwarding upstream.

Same rule UI, same data, two enforcement points.

## 7. Error Handling

| Situation | Detection | Handling |
|---|---|---|
| Proxy port in use | `BindException` on start | Try next port (8765 → 8769, 5 attempts); fail UI if exhausted |
| No LAN IPv4 (WiFi off / VPN restricting) | Zero `NetworkInterface` candidates | UI error with manual IP override field |
| `adb shell settings put` fails | exit ≠ 0 | DiskLogger captures stderr; UI shows manual setup instructions |
| CA push fails | `adb push` exception | Same as above |
| User skips cert install | ≥5 consecutive TLS handshake failures observed | Top banner "Certificate not trusted on device — confirm installation"; re-fire install intent button |
| Pinned app | TLS handshake fails for one host while others succeed | 🔒 icon on host row; pinning bypass explicitly out of scope (link to docs) |
| App crash during session | At next launch, `settings get global http_proxy` on last-known device still ours | Auto-cleanup `:0` before showing home |
| Body > 5 MB | Filter-side byte count | Truncate; set `responseBodyTruncated=true`; show ✂️ badge in detail view |
| Detach fails to clear device proxy | adb error during `close()` | Show recovery dialog with manual unset instructions; `ProxyServer.stop()` still runs unconditionally |

DiskLogger (existing) gets a `proxy/` tag for every step. Same log file (`~/Desktop/network-inspector.log`) — single source of truth for both modes.

## 8. Test Strategy

### `:proxy` unit tests (JUnit 4 + MockK)

- `CertificateAuthority`: P12 persistence, idempotent generation, leaf cert ASN.1 fields (CN, SAN), CA→leaf signature chain.
- `LeafCertGenerator`: cache hit/miss, eviction on size limit, signature validity.
- `HostFilter`: Learn ↔ Filtered toggle, wildcard `*.myapp.com` and exact match, discovery emission, retroactive filter (re-evaluate existing rows).
- `LearnedHostsStore`: JSON round-trip, atomic write (kill mid-write does not corrupt), per-package isolation.
- `ProxyRowAggregator`: ProxyCaptureEvent stream → expected NetworkRow sequence; partial event preservation (request started but no response yet).
- `DeviceProxySetup`: ddmlib shell mocked; assert command sequence (CA push, intent, settings put) and detach sequence.

### `:proxy` integration tests (JUnit 4, real proxy)

- Embedded ProxyServer + local HTTPS test server (Ktor/Jetty) → OkHttp client with the test CA injected into its `TrustManager`:
  - Plain GET + JSON body capture.
  - gzip response → body preserved raw.
  - 4xx / 5xx status surface.
  - Intercept rule → response substituted, upstream not contacted (verify via test server hit counter).

### `:ui` (Compose Desktop test or manual)

- Mode picker present; Proxy mode hides Activity field.
- StatusBar text per mode.
- Learn → Filtered transition hides non-whitelisted rows.

### Manual device verification (documented in README)

- Pixel emulator API 34, debug app with the `network_security_config` snippet above.
- Cold attach: cert install dialog appears, proxy setting applied, REST + HTTPS traffic captured.
- Detach: `adb shell settings get global http_proxy` returns `:0` or unset.
- Crash recovery: kill the macOS app mid-session, relaunch, verify device proxy gets cleared.

## 9. Migration / Rollout

- New module + UI mode toggle ships behind no flag; JVMTI mode remains the default until proxy stabilizes.
- Existing JVMTI users see no behavior change.
- README "Usage (GUI)" section gets a new "Proxy mode" subsection with the `networkSecurityConfig` snippet and CA install screenshot.

## 10. Open Questions (resolved in this spec)

- **Filter granularity:** per-package host whitelist with Learn → Filtered, cached. (§5)
- **Proxy backend:** BrowserUp Proxy. HTTP/2 deferred. (§3)
- **Module placement:** new `:proxy` module, depends on `:engine` for model only. (§3)
- **Mode coexistence:** sealed `ActiveSession`, one session at a time, both modes reuse `InspectorScreen`. (§6)
- **Intercept rules:** same data class, two enforcement points (RuleSender vs CaptureFilter). (§6)
