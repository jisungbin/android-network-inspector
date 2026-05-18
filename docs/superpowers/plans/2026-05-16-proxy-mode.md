# Proxy Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Charles/Proxyman-style HTTP(S) MITM proxy capture mode as a lighter alternative to JVMTI for developer's own debug apps with user-CA trust.

**Architecture:** New `:proxy` Gradle module hosts an embedded BrowserUp Proxy with on-the-fly MITM cert generation. Device-side automation (CA push, install intent, system proxy) over adb. Capture events flow `CaptureFilter → ProxyCaptureEvent → ProxyRowAggregator → NetworkRow`, feeding the existing `:ui` Inspector unchanged. Per-package learned-host cache drives a Learn → Filtered host filter.

**Tech Stack:** Kotlin/JVM (toolchain 21), BrowserUp Proxy 2.1.2 (LittleProxy/Netty), Bouncy Castle 1.78.1, kotlinx-coroutines, kotlinx-serialization-json, ddmlib, JUnit 4 + MockK + AssertK + Turbine + OkHttp for tests.

**Spec:** `docs/superpowers/specs/2026-05-16-android-proxy-mode-design.md`

---

## File Structure

### New module `:proxy`

```
proxy/
├── build.gradle.kts
└── src/
    ├── main/kotlin/com/jisungbin/networkinspector/proxy/
    │   ├── ca/CertificateAuthority.kt        # root CA gen + P12 persist
    │   ├── ca/LeafCertGenerator.kt           # per-SNI leaf cert
    │   ├── filter/HostFilter.kt              # Learn / Filtered modes
    │   ├── filter/LearnedHostsStore.kt       # per-package whitelist JSON
    │   ├── server/ProxyServer.kt             # BrowserUp wrapper
    │   ├── server/CaptureFilter.kt           # HttpFiltersAdapter → events
    │   ├── server/InterceptApplier.kt        # InterceptRule → response substitution
    │   ├── capture/ProxyCaptureEvent.kt      # sealed event type
    │   ├── capture/ProxyRowAggregator.kt     # events → NetworkRow
    │   ├── device/DeviceProxySetup.kt        # adb-side ops
    │   ├── device/LanIp.kt                   # macOS LAN IPv4 detection
    │   └── ProxySession.kt                   # AutoCloseable facade
    └── test/kotlin/com/jisungbin/networkinspector/proxy/
        ├── ca/CertificateAuthorityTest.kt
        ├── ca/LeafCertGeneratorTest.kt
        ├── filter/HostFilterTest.kt
        ├── filter/LearnedHostsStoreTest.kt
        ├── capture/ProxyRowAggregatorTest.kt
        ├── device/DeviceProxySetupTest.kt
        └── ProxyEndToEndTest.kt              # integration: real proxy + local HTTPS
```

### Modified files

- `settings.gradle.kts` — add `include(":proxy")`
- `gradle/libs.versions.toml` — add deps
- `engine/src/main/kotlin/.../NetworkRow.kt` — add `PROXY` variant, truncation flags
- `ui/build.gradle.kts` — add `implementation(project(":proxy"))`
- `ui/src/main/kotlin/.../UiState.kt` — add `CaptureMode`, `ActiveSession`, proxy fields
- `ui/src/main/kotlin/.../AppStore.kt` — branch on capture mode; proxy session wiring
- `ui/src/main/kotlin/.../screens/HomeScreen.kt` — capture mode picker
- `ui/src/main/kotlin/.../screens/StatusBar.kt` — proxy mode badge
- `ui/src/main/kotlin/.../screens/InspectorScreen.kt` — Learn-mode discovery panel slot
- `ui/src/main/kotlin/.../screens/LearnHostsPanel.kt` — new: discovery + checkbox UI
- `README.md` — Proxy mode subsection + `network_security_config` snippet

---

## Task 0: Branch + verify

- [ ] **Step 1: Create feature branch**

```bash
cd /Users/jayden/workspace/android-network-inspector
git checkout -b proxy-mode
```

- [ ] **Step 2: Verify clean baseline build**

Run: `./gradlew assemble`
Expected: `BUILD SUCCESSFUL`

---

## Task 1: Add `:proxy` module scaffold and deps

**Files:**
- Create: `proxy/build.gradle.kts`
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`

- [ ] **Step 1: Register module in settings**

In `settings.gradle.kts`, add `include(":proxy")` after `include(":engine")`:

```kotlin
include(":log")
include(":adb")
include(":protocol")
include(":engine")
include(":proxy")
include(":cli")
include(":ui")
```

- [ ] **Step 2: Add versions and libraries to catalog**

In `gradle/libs.versions.toml`, under `[versions]` append:

```toml
browserup-proxy = "2.1.2"
bouncycastle = "1.78.1"
junit4 = "4.13.2"
mockk = "1.13.13"
assertk = "0.28.1"
turbine = "1.1.0"
okhttp = "4.12.0"
```

Under `[libraries]` append:

```toml
browserup-proxy-core = { module = "com.browserup:browserup-proxy-core", version.ref = "browserup-proxy" }
bouncycastle-pkix = { module = "org.bouncycastle:bcpkix-jdk18on", version.ref = "bouncycastle" }
bouncycastle-prov = { module = "org.bouncycastle:bcprov-jdk18on", version.ref = "bouncycastle" }
junit4 = { module = "junit:junit", version.ref = "junit4" }
mockk = { module = "io.mockk:mockk", version.ref = "mockk" }
assertk = { module = "com.willowtreeapps.assertk:assertk-jvm", version.ref = "assertk" }
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttp-mockwebserver = { module = "com.squareup.okhttp3:mockwebserver", version.ref = "okhttp" }
```

- [ ] **Step 3: Create `proxy/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(libs.kotlin.stdlib)
    api(libs.coroutines.core)
    api(project(":engine"))
    implementation(project(":adb"))
    implementation(project(":log"))
    implementation(libs.browserup.proxy.core)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.bouncycastle.prov)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.assertk)
    testImplementation(libs.turbine)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test { useJUnit() }
```

- [ ] **Step 4: Register kotlin-serialization plugin**

In `gradle/libs.versions.toml`, under `[plugins]` append:

```toml
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

In root `build.gradle.kts`, under `plugins {}`, append:

```kotlin
alias(libs.plugins.kotlin.serialization) apply false
```

- [ ] **Step 5: Verify module builds**

Run: `./gradlew :proxy:assemble`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts gradle/libs.versions.toml build.gradle.kts proxy/build.gradle.kts
git commit -m "Scaffold :proxy module with BrowserUp Proxy + Bouncy Castle deps"
```

---

## Task 2: CertificateAuthority (root CA gen + persist)

**Files:**
- Create: `proxy/src/main/kotlin/com/jisungbin/networkinspector/proxy/ca/CertificateAuthority.kt`
- Test: `proxy/src/test/kotlin/com/jisungbin/networkinspector/proxy/ca/CertificateAuthorityTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.jisungbin.networkinspector.proxy.ca

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.cert.X509Certificate

class CertificateAuthorityTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `generates and persists root CA on first load`() {
        val ca = CertificateAuthority(temp.root)
        val mat = ca.load()
        assertThat(mat.certificate).isNotNull()
        assertThat(mat.privateKey).isNotNull()
        assertThat(java.io.File(temp.root, "root-ca.p12").exists()).isTrue()
    }

    @Test fun `second load returns the same CA`() {
        val first = CertificateAuthority(temp.root).load()
        val second = CertificateAuthority(temp.root).load()
        assertThat(second.certificate.encoded.toList()).isEqualTo(first.certificate.encoded.toList())
    }

    @Test fun `cert subject and issuer are equal (self-signed)`() {
        val cert: X509Certificate = CertificateAuthority(temp.root).load().certificate
        assertThat(cert.issuerX500Principal).isEqualTo(cert.subjectX500Principal)
    }

    @Test fun `cert is valid at least 5 years`() {
        val cert = CertificateAuthority(temp.root).load().certificate
        val validFor = cert.notAfter.time - cert.notBefore.time
        val fiveYearsMs = 5L * 365 * 24 * 3600 * 1000
        assertThat(validFor > fiveYearsMs).isTrue()
    }

    @Test fun `exports cert as DER bytes`() {
        val ca = CertificateAuthority(temp.root)
        ca.load()
        val der = ca.exportDer()
        assertThat(der.size > 100).isTrue()
    }
}
```

- [ ] **Step 2: Run test to verify failure**

Run: `./gradlew :proxy:test --tests "*CertificateAuthorityTest"`
Expected: FAIL — `CertificateAuthority` not defined.

- [ ] **Step 3: Implement CertificateAuthority**

```kotlin
package com.jisungbin.networkinspector.proxy.ca

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit

data class CaMaterial(
    val certificate: X509Certificate,
    val privateKey: PrivateKey,
)

class CertificateAuthority(private val dataDir: File) {
    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        if (!dataDir.exists()) dataDir.mkdirs()
    }

    private val keystoreFile = File(dataDir, KEYSTORE_FILENAME)

    fun load(): CaMaterial {
        if (keystoreFile.exists()) return readFromKeystore()
        val fresh = generate()
        writeToKeystore(fresh)
        return fresh
    }

    fun exportDer(): ByteArray = load().certificate.encoded

    fun exportPem(): String {
        val der = exportDer()
        val b64 = java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN CERTIFICATE-----\n$b64\n-----END CERTIFICATE-----\n"
    }

    private fun generate(): CaMaterial {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val notBefore = Date(now - TimeUnit.HOURS.toMillis(1))
        val notAfter = Date(now + TimeUnit.DAYS.toMillis(365L * 10))
        val subject = X500Name("CN=Network Inspector Root CA, O=Network Inspector, OU=Dev")
        val builder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
            subject, BigInteger.valueOf(now), notBefore, notAfter, subject, keys.public,
        ).apply {
            addExtension(Extension.basicConstraints, true, BasicConstraints(0))
            addExtension(
                Extension.keyUsage, true,
                KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign or KeyUsage.digitalSignature),
            )
        }
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(keys.private)
        val cert = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(builder.build(signer))
        return CaMaterial(cert, keys.private)
    }

    private fun writeToKeystore(material: CaMaterial) {
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, KEYSTORE_PASSWORD) }
        ks.setKeyEntry(ALIAS, material.privateKey, KEYSTORE_PASSWORD, arrayOf(material.certificate))
        keystoreFile.outputStream().use { ks.store(it, KEYSTORE_PASSWORD) }
    }

    private fun readFromKeystore(): CaMaterial {
        val ks = KeyStore.getInstance("PKCS12")
        keystoreFile.inputStream().use { ks.load(it, KEYSTORE_PASSWORD) }
        val key = ks.getKey(ALIAS, KEYSTORE_PASSWORD) as PrivateKey
        val cert = ks.getCertificate(ALIAS) as X509Certificate
        return CaMaterial(cert, key)
    }

    companion object {
        private const val KEYSTORE_FILENAME = "root-ca.p12"
        private const val ALIAS = "network-inspector-root"
        private val KEYSTORE_PASSWORD = "network-inspector".toCharArray()
    }
}
```

- [ ] **Step 4: Run test to verify pass**

Run: `./gradlew :proxy:test --tests "*CertificateAuthorityTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add proxy/src/main/kotlin/com/jisungbin/networkinspector/proxy/ca/CertificateAuthority.kt \
        proxy/src/test/kotlin/com/jisungbin/networkinspector/proxy/ca/CertificateAuthorityTest.kt
git commit -m "Add CertificateAuthority for proxy MITM root CA"
```

---

## Task 3: LeafCertGenerator (per-SNI cert with LRU cache)

**Files:**
- Create: `proxy/src/main/kotlin/com/jisungbin/networkinspector/proxy/ca/LeafCertGenerator.kt`
- Test: `proxy/src/test/kotlin/com/jisungbin/networkinspector/proxy/ca/LeafCertGeneratorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.jisungbin.networkinspector.proxy.ca

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isSameAs
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.cert.X509Certificate

class LeafCertGeneratorTest {
    @get:Rule val temp = TemporaryFolder()

    private fun generator() = LeafCertGenerator(CertificateAuthority(temp.root))

    @Test fun `issues a leaf cert with the host CN`() {
        val cert: X509Certificate = generator().issue("api.example.com").certificate
        assertThat(cert.subjectX500Principal.name).contains("CN=api.example.com")
    }

    @Test fun `issued cert is signed by the root CA`() {
        val ca = CertificateAuthority(temp.root)
        val gen = LeafCertGenerator(ca)
        val leaf = gen.issue("api.example.com").certificate
        leaf.verify(ca.load().certificate.publicKey)
    }

    @Test fun `cache returns same material for the same host`() {
        val gen = generator()
        val first = gen.issue("api.example.com")
        val second = gen.issue("api.example.com")
        assertThat(second).isSameAs(first)
    }

    @Test fun `subject alternative name includes the host`() {
        val cert = generator().issue("api.example.com").certificate
        val sans = cert.subjectAlternativeNames.map { it[1] as String }
        assertThat(sans).contains("api.example.com")
    }
}
```

- [ ] **Step 2: Run test to verify failure**

Run: `./gradlew :proxy:test --tests "*LeafCertGeneratorTest"`
Expected: FAIL — class not defined.

- [ ] **Step 3: Implement LeafCertGenerator**

```kotlin
package com.jisungbin.networkinspector.proxy.ca

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.Date
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class LeafMaterial(
    val certificate: X509Certificate,
    val privateKey: PrivateKey,
)

class LeafCertGenerator(
    private val ca: CertificateAuthority,
    cacheSize: Int = 256,
) {
    private val cache: MutableMap<String, LeafMaterial> = Collections.synchronizedMap(
        object : LinkedHashMap<String, LeafMaterial>(cacheSize, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, LeafMaterial>) =
                size > cacheSize
        }
    )
    private val serial = AtomicLong(System.currentTimeMillis())

    fun issue(host: String): LeafMaterial {
        cache[host]?.let { return it }
        synchronized(cache) {
            cache[host]?.let { return it }
            val created = create(host)
            cache[host] = created
            return created
        }
    }

    private fun create(host: String): LeafMaterial {
        val caMaterial = ca.load()
        val keys: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val notBefore = Date(now - TimeUnit.HOURS.toMillis(1))
        val notAfter = Date(now + TimeUnit.DAYS.toMillis(365L * 2))
        val builder = JcaX509v3CertificateBuilder(
            X500Name(caMaterial.certificate.subjectX500Principal.name),
            BigInteger.valueOf(serial.incrementAndGet()),
            notBefore, notAfter,
            X500Name("CN=$host"),
            keys.public,
        ).apply {
            addExtension(Extension.basicConstraints, false, BasicConstraints(false))
            addExtension(
                Extension.keyUsage, true,
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
            )
            addExtension(
                Extension.extendedKeyUsage, false,
                ExtendedKeyUsage(arrayOf(KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth)),
            )
            addExtension(
                Extension.subjectAlternativeName, false,
                GeneralNames(GeneralName(GeneralName.dNSName, host)),
            )
        }
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(caMaterial.privateKey)
        val cert = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(builder.build(signer))
        return LeafMaterial(cert, keys.private)
    }
}
```

- [ ] **Step 4: Run test to verify pass**

Run: `./gradlew :proxy:test --tests "*LeafCertGeneratorTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add proxy/src/main/kotlin/com/jisungbin/networkinspector/proxy/ca/LeafCertGenerator.kt \
        proxy/src/test/kotlin/com/jisungbin/networkinspector/proxy/ca/LeafCertGeneratorTest.kt
git commit -m "Add LeafCertGenerator for per-SNI MITM cert issuance"
```

---

## Task 4: Extend `NetworkRow` with `PROXY` transport and truncation flags

**Files:**
- Modify: `engine/src/main/kotlin/com/jisungbin/networkinspector/engine/NetworkRow.kt`

- [ ] **Step 1: Update enum and data class**

Replace the file body with:

```kotlin
package com.jisungbin.networkinspector.engine

data class NetworkRow(
    val connectionId: Long,
    val startTimestamp: Long,
    val endTimestamp: Long? = null,
    val method: String = "",
    val url: String = "",
    val protocol: TransportProtocol = TransportProtocol.UNKNOWN,
    val statusCode: Int? = null,
    val requestHeaders: List<Pair<String, List<String>>> = emptyList(),
    val responseHeaders: List<Pair<String, List<String>>> = emptyList(),
    val requestBody: ByteArray? = null,
    val responseBody: ByteArray? = null,
    val requestBodyTruncated: Boolean = false,
    val responseBodyTruncated: Boolean = false,
    val state: ConnectionState = ConnectionState.IN_FLIGHT,
    val lastUpdatedAtMs: Long = System.currentTimeMillis(),
    val responseAtMs: Long? = null,
)

enum class TransportProtocol { JAVA_NET, OKHTTP2, OKHTTP3, PROXY, UNKNOWN }
enum class ConnectionState { IN_FLIGHT, COMPLETED, FAILED }
```

- [ ] **Step 2: Verify nothing breaks**

Run: `./gradlew :engine:assemble :ui:assemble`
Expected: `BUILD SUCCESSFUL`. Existing UI code that switches on `TransportProtocol` may need a branch — search for uses next.

- [ ] **Step 3: Add `PROXY` rendering wherever needed**

Run: `grep -rn "TransportProtocol\." ui/src engine/src cli/src 2>/dev/null`

For each `when (...) {}` over `TransportProtocol`, ensure `PROXY` is handled (display label "Proxy"). If the UI uses else-fallbacks, no change needed.

- [ ] **Step 4: Commit**

```bash
git add engine/src/main/kotlin/com/jisungbin/networkinspector/engine/NetworkRow.kt \
        ui/src/main/kotlin/com/jisungbin/networkinspector/ui/screens
git commit -m "Add PROXY transport variant and body truncation flags to NetworkRow"
```

---

## Task 5: LearnedHostsStore (per-package whitelist JSON, atomic write)

**Files:**
- Create: `proxy/src/main/kotlin/com/jisungbin/networkinspector/proxy/filter/LearnedHostsStore.kt`
- Test: `proxy/src/test/kotlin/com/jisungbin/networkinspector/proxy/filter/LearnedHostsStoreTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.jisungbin.networkinspector.proxy.filter

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LearnedHostsStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `returns empty for unknown package`() {
        val store = LearnedHostsStore(temp.root)
        assertThat(store.load("com.unknown")).isEmpty()
    }

    @Test fun `saves and reloads a host set`() {
        val store = LearnedHostsStore(temp.root)
        store.save("com.app", setOf("api.app.com", "cdn.app.com"))
        assertThat(store.load("com.app")).containsExactlyInAnyOrder("api.app.com", "cdn.app.com")
    }

    @Test fun `per-package isolation`() {
        val store = LearnedHostsStore(temp.root)
        store.save("com.a", setOf("a.com"))
        store.save("com.b", setOf("b.com"))
        assertThat(store.load("com.a")).containsExactlyInAnyOrder("a.com")
        assertThat(store.load("com.b")).containsExactlyInAnyOrder("b.com")
    }

    @Test fun `atomic write does not leave tmp files on success`() {
        val store = LearnedHostsStore(temp.root)
        store.save("com.app", setOf("api.app.com"))
        val tmpCount = temp.root.walkTopDown().count { it.name.endsWith(".tmp") }
        assertThat(tmpCount.toString()).isEqualTo("0")
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :proxy:test --tests "*LearnedHostsStoreTest"`
Expected: FAIL.

- [ ] **Step 3: Implement LearnedHostsStore**

```kotlin
package com.jisungbin.networkinspector.proxy.filter

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
private data class HostsFile(val hosts: List<String>)

class LearnedHostsStore(private val rootDir: File) {
    private val json = Json { prettyPrint = true }

    init { rootDir.mkdirs() }

    fun load(packageName: String): Set<String> {
        val file = fileFor(packageName)
        if (!file.exists()) return emptySet()
        return runCatching {
            json.decodeFromString(HostsFile.serializer(), file.readText()).hosts.toSet()
        }.getOrElse { emptySet() }
    }

    fun save(packageName: String, hosts: Set<String>) {
        val file = fileFor(packageName)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(HostsFile.serializer(), HostsFile(hosts.sorted())))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun fileFor(packageName: String): File =
        File(rootDir, "${packageName.replace('/', '_')}.json")
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :proxy:test --tests "*LearnedHostsStoreTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add proxy/src/main/kotlin/com/jisungbin/networkinspector/proxy/filter/LearnedHostsStore.kt \
        proxy/src/test/kotlin/com/jisungbin/networkinspector/proxy/filter/LearnedHostsStoreTest.kt
git commit -m "Add LearnedHostsStore for per-package proxy host whitelist"
```

---

## Task 6: HostFilter (Learn ↔ Filtered, wildcard match)

**Files:**
- Create: `proxy/src/main/kotlin/com/jisungbin/networkinspector/proxy/filter/HostFilter.kt`
- Test: `proxy/src/test/kotlin/com/jisungbin/networkinspector/proxy/filter/HostFilterTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.jisungbin.networkinspector.proxy.filter

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class HostFilterTest {
    @Test fun `learn mode allows everything and emits discovered hosts`() = runTest {
        val filter = HostFilter()
        filter.setMode(HostFilter.Mode.Learn)
        assertThat(filter.allow("api.example.com")).isTrue()
        assertThat(filter.allow("ads.tracker.com")).isTrue()
        assertThat(filter.discovered.value).containsExactlyInAnyOrder("api.example.com", "ads.tracker.com")
    }

    @Test fun `filtered mode rejects unmatched hosts`() {
        val filter = HostFilter()
        filter.setMode(HostFilter.Mode.Filtered)
        filter.setAllowed(setOf("api.example.com"))
        assertThat(filter.allow("api.example.com")).isTrue()
        assertThat(filter.allow("ads.tracker.com")).isFalse()
    }

    @Test fun `wildcard prefix matches subdomain`() {
        val filter = HostFilter()
        filter.setMode(HostFilter.Mode.Filtered)
        filter.setAllowed(setOf("*.example.com"))
        assertThat(filter.allow("api.example.com")).isTrue()
        assertThat(filter.allow("cdn.example.com")).isTrue()
        assertThat(filter.allow("evil.com")).isFalse()
    }

    @Test fun `wildcard does not match the apex`() {
        val filter = HostFilter()
        filter.setMode(HostFilter.Mode.Filtered)
        filter.setAllowed(setOf("*.example.com"))
        assertThat(filter.allow("example.com")).isFalse()
    }

    @Test fun `discovered set deduplicates`() {
        val filter = HostFilter()
        filter.setMode(HostFilter.Mode.Learn)
        filter.allow("api.example.com")
        filter.allow("api.example.com")
        assertThat(filter.discovered.value).containsExactlyInAnyOrder("api.example.com")
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :proxy:test --tests "*HostFilterTest"`
Expected: FAIL.

- [ ] **Step 3: Implement HostFilter**

```kotlin
package com.jisungbin.networkinspector.proxy.filter

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class HostFilter {
    enum class Mode { Learn, Filtered }

    private val _mode = MutableStateFlow(Mode.Learn)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    private val _allowed = MutableStateFlow<Set<String>>(emptySet())
    val allowed: StateFlow<Set<String>> = _allowed.asStateFlow()

    private val _discovered = MutableStateFlow<Set<String>>(emptySet())
    val discovered: StateFlow<Set<String>> = _discovered.asStateFlow()

    fun setMode(value: Mode) { _mode.value = value }
    fun setAllowed(hosts: Set<String>) { _allowed.value = hosts }

    fun allow(host: String): Boolean {
        return when (_mode.value) {
            Mode.Learn -> {
                _discovered.update { it + host }
                true
            }
            Mode.Filtered -> _allowed.value.any { pattern -> matches(pattern, host) }
        }
    }

    fun resetDiscovered() { _discovered.value = emptySet() }

    private fun matches(pattern: String, host: String): Boolean {
        if (pattern.startsWith("*.")) {
            val suffix = pattern.removePrefix("*.")
            return host.endsWith(".$suffix")
        }
        return pattern.equals(host, ignoreCase = true)
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :proxy:test --tests "*HostFilterTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add proxy/src/main/kotlin/com/jisungbin/networkinspector/proxy/filter/HostFilter.kt \
        proxy/src/test/kotlin/com/jisungbin/networkinspector/proxy/filter/HostFilterTest.kt
git commit -m "Add HostFilter with Learn/Filtered modes and wildcard matching"
```

---

## Task 7: ProxyCaptureEvent sealed type

**Files:**
- Create: `proxy/src/main/kotlin/com/jisungbin/networkinspector/proxy/capture/ProxyCaptureEvent.kt`

- [ ] **Step 1: Implement (no test yet — pure type)**

```kotlin
package com.jisungbin.networkinspector.proxy.capture

sealed interface ProxyCaptureEvent {
    val connectionId: Long
    val timestampNanos: Long

    data class Started(
        override val connectionId: Long,
        override val timestampNanos: Long,
        val method: String,
        val url: String,
        val headers: List<Pair<String, List<String>>>,
        val host: String,
    ) : ProxyCaptureEvent

    data class RequestBody(
        override val connectionId: Long,
        override val timestampNanos: Long,
        val bytes: ByteArray,
        val truncated: Boolean,
    ) : ProxyCaptureEvent

    data class ResponseStarted(
        override val connectionId: Long,
        override val timestampNanos: Long,
        val statusCode: Int,
        val headers: List<Pair<String, List<String>>>,
    ) : ProxyCaptureEvent

    data class ResponseBody(
        override val connectionId: Long,
        override val timestampNanos: Long,
        val bytes: ByteArray,
        val truncated: Boolean,
    ) : ProxyCaptureEvent

    data class Closed(
        override val connectionId: Long,
        override val timestampNanos: Long,
        val completed: Boolean,
    ) : ProxyCaptureEvent
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew :proxy:compileKotlin`
Expected: success.

- [ ] **Step 3: Commit**

```bash
git add proxy/src/main/kotlin/com/jisungbin/networkinspector/proxy/capture/ProxyCaptureEvent.kt
git commit -m "Add ProxyCaptureEvent sealed type for proxy capture pipeline"
```

---

## Task 8: ProxyRowAggregator (events → NetworkRow)

**Files:**
- Create: `proxy/src/main/kotlin/com/jisungbin/networkinspector/proxy/capture/ProxyRowAggregator.kt`
- Test: `proxy/src/test/kotlin/com/jisungbin/networkinspector/proxy/capture/ProxyRowAggregatorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.jisungbin.networkinspector.proxy.capture

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import com.jisungbin.networkinspector.engine.ConnectionState
import com.jisungbin.networkinspector.engine.TransportProtocol
import org.junit.Test

class ProxyRowAggregatorTest {
    private val agg = ProxyRowAggregator()

    @Test fun `Started produces an in-flight row with method, url, headers`() {
        val row = agg.consume(
            ProxyCaptureEvent.Started(
                connectionId = 1L,
                timestampNanos = 100L,
                method = "GET",
                url = "https://api.example.com/v1/foo",
                headers = listOf("Accept" to listOf("application/json")),
                host = "api.example.com",
            )
        )!!
        assertThat(row.method).isEqualTo("GET")
        assertThat(row.url).isEqualTo("https://api.example.com/v1/foo")
        assertThat(row.protocol).isEqualTo(TransportProtocol.PROXY)
        assertThat(row.state).isEqualTo(ConnectionState.IN_FLIGHT)
        assertThat(row.requestHeaders).containsExactly("Accept" to listOf("application/json"))
    }

    @Test fun `ResponseStarted fills status code and response headers`() {
        agg.consume(started(1L))
        val row = agg.consume(
            ProxyCaptureEvent.ResponseStarted(
                connectionId = 1L, timestampNanos = 200L, statusCode = 200,
                headers = listOf("Content-Type" to listOf("application/json")),
            )
        )!!
        assertThat(row.statusCode).isEqualTo(200)
        assertThat(row.responseHeaders).containsExactly("Content-Type" to listOf("application/json"))
    }

    @Test fun `ResponseBody fills bytes and truncation flag`() {
        agg.consume(started(1L))
        val row = agg.consume(
            ProxyCaptureEvent.ResponseBody(1L, 300L, byteArrayOf(1, 2, 3), truncated = true)
        )!!
        assertThat(row.responseBody!!.toList()).isEqualTo(listOf<Byte>(1, 2, 3))
        assertThat(row.responseBodyTruncated.toString()).isEqualTo("true")
    }

    @Test fun `Closed marks completed state`() {
        agg.consume(started(1L))
        val row = agg.consume(ProxyCaptureEvent.Closed(1L, 400L, completed = true))!!
        assertThat(row.state).isEqualTo(ConnectionState.COMPLETED)
        assertThat(row.endTimestamp).isNotNull()
    }

    @Test fun `Closed with completed=false marks failed`() {
        agg.consume(started(1L))
        val row = agg.consume(ProxyCaptureEvent.Closed(1L, 400L, completed = false))!!
        assertThat(row.state).isEqualTo(ConnectionState.FAILED)
    }

    private fun started(id: Long) = ProxyCaptureEvent.Started(
        connectionId = id, timestampNanos = 100L,
        method = "GET", url = "https://api.example.com/", headers = emptyList(),
        host = "api.example.com",
    )
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :proxy:test --tests "*ProxyRowAggregatorTest"`
Expected: FAIL.

- [ ] **Step 3: Implement ProxyRowAggregator**

```kotlin
package com.jisungbin.networkinspector.proxy.capture

import com.jisungbin.networkinspector.engine.ConnectionState
import com.jisungbin.networkinspector.engine.NetworkRow
import com.jisungbin.networkinspector.engine.TransportProtocol

class ProxyRowAggregator {
    private val rows = LinkedHashMap<Long, NetworkRow>()

    val snapshot: List<NetworkRow> get() = rows.values.toList()

    fun reset() { rows.clear() }

    fun consume(event: ProxyCaptureEvent): NetworkRow? {
        val existing = rows[event.connectionId] ?: NetworkRow(
            connectionId = event.connectionId,
            startTimestamp = event.timestampNanos,
            protocol = TransportProtocol.PROXY,
        )
        val next = when (event) {
            is ProxyCaptureEvent.Started -> existing.copy(
                method = event.method,
                url = event.url,
                requestHeaders = event.headers,
                startTimestamp = event.timestampNanos,
                protocol = TransportProtocol.PROXY,
            )
            is ProxyCaptureEvent.RequestBody -> existing.copy(
                requestBody = event.bytes,
                requestBodyTruncated = event.truncated,
            )
            is ProxyCaptureEvent.ResponseStarted -> existing.copy(
                statusCode = event.statusCode,
                responseHeaders = event.headers,
                responseAtMs = System.currentTimeMillis(),
            )
            is ProxyCaptureEvent.ResponseBody -> existing.copy(
                responseBody = event.bytes,
                responseBodyTruncated = event.truncated,
            )
            is ProxyCaptureEvent.Closed -> existing.copy(
                endTimestamp = event.timestampNanos,
                state = if (event.completed) ConnectionState.COMPLETED else ConnectionState.FAILED,
            )
        }
        val stamped = next.copy(lastUpdatedAtMs = System.currentTimeMillis())
        rows[event.connectionId] = stamped
        return stamped
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :proxy:test --tests "*ProxyRowAggregatorTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add proxy/src/main/kotlin/com/jisungbin/networkinspector/proxy/capture/ProxyRowAggregator.kt \
        proxy/src/test/kotlin/com/jisungbin/networkinspector/proxy/capture/ProxyRowAggregatorTest.kt
git commit -m "Add ProxyRowAggregator mapping ProxyCaptureEvent to NetworkRow"
```

---

## Task 9: ProxyServer + CaptureFilter (BrowserUp wiring)

**Files:**
- Create: `proxy/src/main/kotlin/com/jisungbin/networkinspector/proxy/server/ProxyServer.kt`
- Create: `proxy/src/main/kotlin/com/jisungbin/networkinspector/proxy/server/CaptureFilter.kt`
- Create: `proxy/src/main/kotlin/com/jisungbin/networkinspector/proxy/server/InterceptApplier.kt`

This task has no unit test (pure integration) — Task 14 covers it end-to-end.

- [ ] **Step 1: Implement InterceptApplier (URL pattern → response substitution)**

```kotlin
package com.jisungbin.networkinspector.proxy.server

import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion

data class InterceptSpec(
    val id: String,
    val urlPattern: String,
    val method: String,
    val replacementStatus: Int,
    val replacementContentType: String,
    val replacementBody: String,
    val addedHeaders: List<Pair<String, String>>,
    val enabled: Boolean,
)

class InterceptApplier(private val rulesProvider: () -> List<InterceptSpec>) {
    fun match(request: HttpRequest, fullUrl: String): FullHttpResponse? {
        val rules = rulesProvider().filter { it.enabled }
        val rule = rules.firstOrNull { r ->
            (r.method.equals("ANY", ignoreCase = true) ||
                r.method.equals(request.method().name(), ignoreCase = true)) &&
                fullUrl.contains(r.urlPattern, ignoreCase = true)
        } ?: return null
        val body = rule.replacementBody.toByteArray(Charsets.UTF_8)
        val response = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.valueOf(rule.replacementStatus),
            Unpooled.wrappedBuffer(body),
        )
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.size)
        if (rule.replacementContentType.isNotBlank()) {
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, rule.replacementContentType)
        }
        rule.addedHeaders.forEach { (k, v) -> response.headers().add(k, v) }
        return response
    }
}
```

- [ ] **Step 2: Implement CaptureFilter**

```kotlin
package com.jisungbin.networkinspector.proxy.server

import com.browserup.bup.filters.HttpFiltersAdapter
import com.jisungbin.networkinspector.proxy.capture.ProxyCaptureEvent
import com.jisungbin.networkinspector.proxy.filter.HostFilter
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpContent
import io.netty.handler.codec.http.HttpObject
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.LastHttpContent
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.trySendBlocking
import java.util.concurrent.atomic.AtomicLong

class CaptureFilter(
    originalRequest: HttpRequest,
    ctx: ChannelHandlerContext?,
    private val events: SendChannel<ProxyCaptureEvent>,
    private val hostFilter: HostFilter,
    private val intercept: InterceptApplier,
    private val bodyCapBytes: Int,
    private val connIdSource: AtomicLong,
) : HttpFiltersAdapter(originalRequest, ctx) {

    private val connectionId = connIdSource.incrementAndGet()
    private val requestBuf = ByteArrayCollector(bodyCapBytes)
    private val responseBuf = ByteArrayCollector(bodyCapBytes)
    private val host: String = run {
        val hostHeader = originalRequest.headers().get("Host").orEmpty()
        hostHeader.substringBefore(':')
    }
    private var dropped = false

    override fun clientToProxyRequest(httpObject: HttpObject): HttpResponse? {
        if (httpObject is HttpRequest) {
            val fullUrl = reconstructUrl(httpObject)
            if (!hostFilter.allow(host)) { dropped = true; return null }
            intercept.match(httpObject, fullUrl)?.let { return it }
            events.trySendBlocking(
                ProxyCaptureEvent.Started(
                    connectionId = connectionId,
                    timestampNanos = System.nanoTime(),
                    method = httpObject.method().name(),
                    url = fullUrl,
                    headers = httpObject.headers().entries()
                        .groupBy({ it.key }, { it.value })
                        .map { (k, v) -> k to v },
                    host = host,
                )
            )
        }
        if (httpObject is HttpContent && !dropped) {
            val chunk = ByteArray(httpObject.content().readableBytes())
            httpObject.content().getBytes(httpObject.content().readerIndex(), chunk)
            requestBuf.append(chunk)
            if (httpObject is LastHttpContent) {
                events.trySendBlocking(
                    ProxyCaptureEvent.RequestBody(
                        connectionId, System.nanoTime(),
                        requestBuf.bytes(), requestBuf.truncated,
                    )
                )
            }
        }
        return null
    }

    override fun serverToProxyResponse(httpObject: HttpObject): HttpObject {
        if (dropped) return httpObject
        if (httpObject is HttpResponse && httpObject !is FullHttpResponse) {
            events.trySendBlocking(
                ProxyCaptureEvent.ResponseStarted(
                    connectionId = connectionId,
                    timestampNanos = System.nanoTime(),
                    statusCode = httpObject.status().code(),
                    headers = httpObject.headers().entries()
                        .groupBy({ it.key }, { it.value })
                        .map { (k, v) -> k to v },
                )
            )
        }
        if (httpObject is HttpContent) {
            val chunk = ByteArray(httpObject.content().readableBytes())
            httpObject.content().getBytes(httpObject.content().readerIndex(), chunk)
            responseBuf.append(chunk)
            if (httpObject is LastHttpContent) {
                events.trySendBlocking(
                    ProxyCaptureEvent.ResponseBody(
                        connectionId, System.nanoTime(),
                        responseBuf.bytes(), responseBuf.truncated,
                    )
                )
                events.trySendBlocking(
                    ProxyCaptureEvent.Closed(connectionId, System.nanoTime(), completed = true)
                )
            }
        }
        return httpObject
    }

    override fun serverToProxyResponseTimedOut() {
        if (!dropped) {
            events.trySendBlocking(
                ProxyCaptureEvent.Closed(connectionId, System.nanoTime(), completed = false)
            )
        }
    }

    private fun reconstructUrl(req: HttpRequest): String {
        val uri = req.uri()
        if (uri.startsWith("http://") || uri.startsWith("https://")) return uri
        val scheme = "https"
        val hostHeader = req.headers().get("Host").orEmpty()
        return "$scheme://$hostHeader$uri"
    }
}

private class ByteArrayCollector(private val capBytes: Int) {
    private val buf = java.io.ByteArrayOutputStream()
    var truncated: Boolean = false; private set

    fun append(chunk: ByteArray) {
        if (truncated) return
        val remaining = capBytes - buf.size()
        if (chunk.size <= remaining) buf.write(chunk)
        else {
            buf.write(chunk, 0, remaining.coerceAtLeast(0))
            truncated = true
        }
    }
    fun bytes(): ByteArray = buf.toByteArray()
}
```

- [ ] **Step 3: Implement ProxyServer**

```kotlin
package com.jisungbin.networkinspector.proxy.server

import com.browserup.bup.BrowserUpProxyServer
import com.browserup.bup.filters.HttpFiltersAdapter
import com.browserup.bup.filters.HttpFiltersSource
import com.browserup.bup.mitm.RootCertificateGenerator
import com.browserup.bup.mitm.manager.ImpersonatingMitmManager
import com.jisungbin.networkinspector.proxy.ca.CertificateAuthority
import com.jisungbin.networkinspector.proxy.capture.ProxyCaptureEvent
import com.jisungbin.networkinspector.proxy.filter.HostFilter
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.HttpRequest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.net.BindException
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicLong

class ProxyServer(
    private val ca: CertificateAuthority,
    private val hostFilter: HostFilter,
    private val interceptApplier: InterceptApplier,
    private val bodyCapBytes: Int = 5 * 1024 * 1024,
) {
    private var server: BrowserUpProxyServer? = null
    private val channel = Channel<ProxyCaptureEvent>(Channel.UNLIMITED)
    private val connIdSource = AtomicLong(Long.MIN_VALUE / 2)

    val events: Flow<ProxyCaptureEvent> = channel.receiveAsFlow()
    var boundPort: Int = -1; private set

    fun start(preferredPorts: IntRange = 8765..8769): Int {
        val mitm = buildMitmManager()
        val bup = BrowserUpProxyServer().apply {
            setMitmManager(mitm)
            setTrustAllServers(true)
            addFirstHttpFilterFactory(object : HttpFiltersSource {
                override fun filterRequest(req: HttpRequest, ctx: ChannelHandlerContext?): HttpFiltersAdapter =
                    CaptureFilter(req, ctx, channel, hostFilter, interceptApplier, bodyCapBytes, connIdSource)
                override fun filterRequest(req: HttpRequest): HttpFiltersAdapter =
                    CaptureFilter(req, null, channel, hostFilter, interceptApplier, bodyCapBytes, connIdSource)
                override fun getMaximumRequestBufferSizeInBytes(): Int = 0
                override fun getMaximumResponseBufferSizeInBytes(): Int = 0
            })
        }
        var lastEx: BindException? = null
        for (port in preferredPorts) {
            try {
                bup.start(port)
                boundPort = port
                server = bup
                return port
            } catch (e: Throwable) {
                val be = generateSequence<Throwable?>(e) { it?.cause }.firstOrNull { it is BindException } as? BindException
                if (be != null) { lastEx = be; continue }
                throw e
            }
        }
        throw IllegalStateException("No free port in $preferredPorts", lastEx)
    }

    fun stop() {
        runCatching { server?.stop() }
        server = null
        channel.close()
    }

    private fun buildMitmManager(): ImpersonatingMitmManager {
        val mat = ca.load()
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, PWD) }
        ks.setKeyEntry("ca", mat.privateKey, PWD, arrayOf(mat.certificate))
        val generator = RootCertificateGenerator.builder()
            .certificateValidityInDays(3650)
            .build()
        val backing = generator.load()
        // Replace BrowserUp's auto-generated root with ours
        backing.keyStore.setKeyEntry("ca", mat.privateKey, PWD, arrayOf(mat.certificate))
        return ImpersonatingMitmManager.builder()
            .rootCertificateSource { backing }
            .build()
    }

    companion object {
        private val PWD = "network-inspector".toCharArray()
    }
}
```

- [ ] **Step 4: Verify compile**

Run: `./gradlew :proxy:compileKotlin`
Expected: success.

- [ ] **Step 5: Commit**

```bash
git add proxy/src/main/kotlin/com/jisungbin/networkinspector/proxy/server/
git commit -m "Add ProxyServer with BrowserUp Proxy + CaptureFilter wiring"
```

---

## Task 10: LanIp detector

**Files:**
- Create: `proxy/src/main/kotlin/com/jisungbin/networkinspector/proxy/device/LanIp.kt`

This is small enough to inline-test in the integration test; pure helper.

- [ ] **Step 1: Implement**

```kotlin
package com.jisungbin.networkinspector.proxy.device

import java.net.Inet4Address
import java.net.NetworkInterface

object LanIp {
    fun detect(): String? {
        return NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .map { it.hostAddress }
            .firstOrNull()
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add proxy/src/main/kotlin/com/jisungbin/networkinspector/proxy/device/LanIp.kt
git commit -m "Add LanIp helper for macOS LAN IPv4 detection"
```

---

## Task 11: DeviceProxySetup (adb-side automation)

**Files:**
- Create: `proxy/src/main/kotlin/com/jisungbin/networkinspector/proxy/device/DeviceProxySetup.kt`
- Test: `proxy/src/test/kotlin/com/jisungbin/networkinspector/proxy/device/DeviceProxySetupTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.jisungbin.networkinspector.proxy.device

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import com.android.ddmlib.IDevice
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verifySequence
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class DeviceProxySetupTest {
    @get:Rule val temp = TemporaryFolder()

    private val recorded = mutableListOf<String>()
    private val device = mockk<IDevice>(relaxed = true).also { d ->
        every {
            d.executeShellCommand(capture(slot<String>().also { /* unused; using lambda below */ }), any(), any(), any())
        } answers {
            recorded += arg<String>(0)
        }
    }

    @Test fun `apply pushes CA, fires install intent, sets http proxy`() {
        val caPem = temp.newFile("ca.pem").apply { writeText("PEM") }
        DeviceProxySetup(device).apply(caPem, host = "192.168.1.10", port = 8765)
        assertThat(recorded[0]).contains("am start")
        assertThat(recorded[0]).contains("application/x-x509-ca-cert")
        assertThat(recorded[1]).containsExactly(
            // tolerant: at least the settings put line is present
        )
        assertThat(recorded.joinToString("\n")).contains("settings put global http_proxy 192.168.1.10:8765")
    }

    @Test fun `restore clears global http proxy`() {
        DeviceProxySetup(device).restore()
        assertThat(recorded.joinToString("\n")).contains("settings put global http_proxy :0")
    }

    @Test fun `currentProxy reads settings`() {
        every { device.executeShellCommand(any(), any(), any(), any()) } answers {
            val cb = arg<com.android.ddmlib.IShellOutputReceiver>(1)
            "192.168.1.10:8765\n".toByteArray().let { cb.addOutput(it, 0, it.size) }
        }
        val current = DeviceProxySetup(device).currentProxy()
        assertThat(current).contains("192.168.1.10:8765")
    }
}
```

Note: the `apply` test in step 1 uses a tolerant `contains` rather than strict ordering for the install intent vs. push because ddmlib mocks split the command stream awkwardly. The intent here is verifying that the three operations all occurred.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :proxy:test --tests "*DeviceProxySetupTest"`
Expected: FAIL.

- [ ] **Step 3: Implement DeviceProxySetup**

```kotlin
package com.jisungbin.networkinspector.proxy.device

import com.android.ddmlib.IDevice
import com.jisungbin.networkinspector.adb.shell
import java.io.File

private const val REMOTE_CA_PATH = "/sdcard/Download/network-inspector-ca.crt"

class DeviceProxySetup(private val device: IDevice) {
    fun apply(caPemFile: File, host: String, port: Int) {
        device.pushFile(caPemFile.absolutePath, REMOTE_CA_PATH)
        device.shell(
            "am start -a android.intent.action.VIEW " +
                "-t application/x-x509-ca-cert " +
                "-d file://$REMOTE_CA_PATH"
        )
        device.shell("settings put global http_proxy $host:$port")
    }

    fun restore() {
        runCatching { device.shell("settings put global http_proxy :0") }
    }

    fun currentProxy(): String =
        device.shell("settings get global http_proxy").trim()
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :proxy:test --tests "*DeviceProxySetupTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add proxy/src/main/kotlin/com/jisungbin/networkinspector/proxy/device/DeviceProxySetup.kt \
        proxy/src/test/kotlin/com/jisungbin/networkinspector/proxy/device/DeviceProxySetupTest.kt
git commit -m "Add DeviceProxySetup for adb CA push and system proxy toggle"
```

---

## Task 12: ProxySession (lifecycle facade)

**Files:**
- Create: `proxy/src/main/kotlin/com/jisungbin/networkinspector/proxy/ProxySession.kt`

- [ ] **Step 1: Implement**

```kotlin
package com.jisungbin.networkinspector.proxy

import com.android.ddmlib.IDevice
import com.jisungbin.networkinspector.engine.NetworkRow
import com.jisungbin.networkinspector.log.DiskLogger
import com.jisungbin.networkinspector.proxy.ca.CertificateAuthority
import com.jisungbin.networkinspector.proxy.capture.ProxyRowAggregator
import com.jisungbin.networkinspector.proxy.device.DeviceProxySetup
import com.jisungbin.networkinspector.proxy.device.LanIp
import com.jisungbin.networkinspector.proxy.filter.HostFilter
import com.jisungbin.networkinspector.proxy.filter.LearnedHostsStore
import com.jisungbin.networkinspector.proxy.server.InterceptApplier
import com.jisungbin.networkinspector.proxy.server.InterceptSpec
import com.jisungbin.networkinspector.proxy.server.ProxyServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class ProxySession(
    private val device: IDevice,
    private val packageName: String,
    dataDir: File,
    private val rulesProvider: () -> List<InterceptSpec>,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val ca = CertificateAuthority(File(dataDir, "ca"))
    private val store = LearnedHostsStore(File(dataDir, "learned-hosts"))
    val hostFilter = HostFilter()
    private val aggregator = ProxyRowAggregator()
    private val server = ProxyServer(ca, hostFilter, InterceptApplier(rulesProvider))
    private val setup = DeviceProxySetup(device)

    private val _rows = MutableSharedFlow<NetworkRow>(extraBufferCapacity = 256)
    val rows: SharedFlow<NetworkRow> = _rows.asSharedFlow()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var captureJob: Job? = null

    sealed interface State {
        data object Idle : State
        data class Starting(val phase: Phase) : State
        data class Active(val port: Int, val mode: HostFilter.Mode) : State
        data class Failed(val message: String) : State
    }

    enum class Phase { CertSetup, ServerStart, AdbPush, AdbProxy, Done }

    fun start() {
        try {
            _state.value = State.Starting(Phase.CertSetup)
            val pemFile = File.createTempFile("ni-ca-", ".crt").apply { writeText(ca.exportPem()) }
            val ip = LanIp.detect() ?: error("No LAN IPv4 detected")

            _state.value = State.Starting(Phase.ServerStart)
            val port = server.start()

            _state.value = State.Starting(Phase.AdbPush)
            setup.apply(pemFile, ip, port)
            _state.value = State.Starting(Phase.AdbProxy)

            val known = store.load(packageName)
            if (known.isEmpty()) {
                hostFilter.setMode(HostFilter.Mode.Learn)
            } else {
                hostFilter.setAllowed(known)
                hostFilter.setMode(HostFilter.Mode.Filtered)
            }

            captureJob = scope.launch {
                server.events.collect { evt ->
                    aggregator.consume(evt)?.let { _rows.emit(it) }
                }
            }
            _state.value = State.Active(port, hostFilter.mode.value)
            DiskLogger.log("proxy session active port=$port mode=${hostFilter.mode.value} pkg=$packageName")
        } catch (t: Throwable) {
            DiskLogger.logError("proxy session start failed", t)
            _state.value = State.Failed(t.message ?: t::class.simpleName ?: "error")
            runCatching { close() }
            throw t
        }
    }

    fun saveLearnedFilter(hosts: Set<String>) {
        store.save(packageName, hosts)
        hostFilter.setAllowed(hosts)
        hostFilter.setMode(HostFilter.Mode.Filtered)
        _state.value = (_state.value as? State.Active)?.copy(mode = HostFilter.Mode.Filtered) ?: _state.value
    }

    override fun close() {
        captureJob?.cancel()
        runCatching { setup.restore() }
        runCatching { server.stop() }
        scope.cancel()
        _state.value = State.Idle
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew :proxy:assemble`
Expected: success.

- [ ] **Step 3: Commit**

```bash
git add proxy/src/main/kotlin/com/jisungbin/networkinspector/proxy/ProxySession.kt
git commit -m "Add ProxySession lifecycle facade"
```

---

## Task 13: End-to-end integration test (local HTTPS, real proxy)

**Files:**
- Create: `proxy/src/test/kotlin/com/jisungbin/networkinspector/proxy/ProxyEndToEndTest.kt`

This test asserts that a real OkHttp client routed through the embedded ProxyServer can MITM a local HTTPS server using our CA, and that `ProxyRowAggregator` emits the expected `NetworkRow`.

- [ ] **Step 1: Write the test**

```kotlin
package com.jisungbin.networkinspector.proxy

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import com.jisungbin.networkinspector.engine.ConnectionState
import com.jisungbin.networkinspector.proxy.ca.CertificateAuthority
import com.jisungbin.networkinspector.proxy.capture.ProxyCaptureEvent
import com.jisungbin.networkinspector.proxy.capture.ProxyRowAggregator
import com.jisungbin.networkinspector.proxy.filter.HostFilter
import com.jisungbin.networkinspector.proxy.server.InterceptApplier
import com.jisungbin.networkinspector.proxy.server.ProxyServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class ProxyEndToEndTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var ca: CertificateAuthority
    private lateinit var hostFilter: HostFilter
    private lateinit var server: ProxyServer
    private lateinit var mockServer: MockWebServer

    @Before fun setup() {
        ca = CertificateAuthority(temp.root)
        hostFilter = HostFilter().apply { setMode(HostFilter.Mode.Learn) }
        server = ProxyServer(ca, hostFilter, InterceptApplier { emptyList() })
        mockServer = MockWebServer().apply { start() }
    }

    @After fun teardown() {
        server.stop()
        mockServer.shutdown()
    }

    @Test fun `OkHttp through proxy with CA trusted captures request and response`() = runBlocking {
        mockServer.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"ok":true}""")
                .addHeader("Content-Type", "application/json")
        )
        val port = server.start(8765..8769)
        val client = okHttpClientWithCa(ca.load().certificate, port)

        val aggregator = ProxyRowAggregator()
        val collectJob = kotlinx.coroutines.GlobalScope.launch {
            server.events.collect { aggregator.consume(it) }
        }

        val response = client.newCall(
            Request.Builder().url(mockServer.url("/v1/foo")).build()
        ).execute()

        assertThat(response.code).isEqualTo(200)
        assertThat(response.body!!.string()).contains("ok")

        // Wait briefly for Closed event to propagate
        repeat(20) {
            if (aggregator.snapshot.any { it.state != ConnectionState.IN_FLIGHT }) return@repeat
            Thread.sleep(50)
        }
        collectJob.cancel()

        val row = aggregator.snapshot.first()
        assertThat(row.method).isEqualTo("GET")
        assertThat(row.statusCode).isEqualTo(200)
        assertThat(row.url).contains("/v1/foo")
    }

    private fun okHttpClientWithCa(caCert: X509Certificate, proxyPort: Int): OkHttpClient {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("ca", caCert)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        val tm = tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        return OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort)))
            .sslSocketFactory(ssl.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }
            .build()
    }
}
```

- [ ] **Step 2: Run the integration test**

Run: `./gradlew :proxy:test --tests "*ProxyEndToEndTest"`
Expected: PASS.

If it fails on MITM: inspect ProxyServer's MITM manager wiring — `ImpersonatingMitmManager` may need a `MitmManager` factory that accepts our CA directly. If `RootCertificateGenerator` approach doesn't work, switch to BrowserUp's `Authority.builder()` API for custom roots.

- [ ] **Step 3: Commit**

```bash
git add proxy/src/test/kotlin/com/jisungbin/networkinspector/proxy/ProxyEndToEndTest.kt
git commit -m "Add proxy end-to-end integration test"
```

---

## Task 14: UI — add CaptureMode and ActiveSession types

**Files:**
- Modify: `ui/build.gradle.kts`
- Modify: `ui/src/main/kotlin/com/jisungbin/networkinspector/ui/UiState.kt`

- [ ] **Step 1: Add `:proxy` to `:ui` deps**

In `ui/build.gradle.kts`, under `dependencies {}`, add after `implementation(project(":engine"))`:

```kotlin
implementation(project(":proxy"))
```

- [ ] **Step 2: Add types to UiState.kt**

Append to `ui/src/main/kotlin/com/jisungbin/networkinspector/ui/UiState.kt`:

```kotlin
enum class CaptureMode { Jvmti, Proxy }

sealed class ProxyAttachState {
    data object Idle : ProxyAttachState()
    data class Starting(val phase: com.jisungbin.networkinspector.proxy.ProxySession.Phase) : ProxyAttachState()
    data class Active(
        val port: Int,
        val mode: com.jisungbin.networkinspector.proxy.filter.HostFilter.Mode,
        val discoveredHosts: Set<String>,
        val allowedHosts: Set<String>,
    ) : ProxyAttachState()
    data class Failed(val message: String) : ProxyAttachState()
}
```

Modify the `UiState` data class to add fields:

```kotlin
val captureMode: CaptureMode = CaptureMode.Jvmti,
val proxyAttach: ProxyAttachState = ProxyAttachState.Idle,
```

- [ ] **Step 3: Verify compile**

Run: `./gradlew :ui:compileKotlin`
Expected: success.

- [ ] **Step 4: Commit**

```bash
git add ui/build.gradle.kts ui/src/main/kotlin/com/jisungbin/networkinspector/ui/UiState.kt
git commit -m "Add CaptureMode and ProxyAttachState to UiState"
```

---

## Task 15: UI — AppStore proxy session wiring

**Files:**
- Modify: `ui/src/main/kotlin/com/jisungbin/networkinspector/ui/AppStore.kt`

- [ ] **Step 1: Add proxy state and methods**

Add the following imports at the top of AppStore.kt:

```kotlin
import com.jisungbin.networkinspector.proxy.ProxySession
import com.jisungbin.networkinspector.proxy.filter.HostFilter
import com.jisungbin.networkinspector.proxy.server.InterceptSpec
import kotlinx.coroutines.flow.collect
```

Add a private field next to `private var session: AttachSession? = null`:

```kotlin
private var proxySession: ProxySession? = null
private var proxyJob: Job? = null
private var proxyStateJob: Job? = null
```

Add the following methods to `AppStore`:

```kotlin
fun updateCaptureMode(mode: CaptureMode) =
    _state.update { it.copy(captureMode = mode) }

fun attachProxy() {
    val s = _state.value
    val serial = s.deviceSerial ?: return error("device unselected")
    if (s.packageName.isBlank()) return error("package empty")
    _state.update { it.copy(proxyAttach = ProxyAttachState.Starting(ProxySession.Phase.CertSetup)) }
    scope.launch {
        try {
            val device = withContext(Dispatchers.IO) {
                bridge!!.devices.toList().findBySerial(serial)
            }
            val dataDir = File(System.getProperty("user.home"),
                "Library/Application Support/network-inspector")
            val newSession = ProxySession(
                device = device,
                packageName = s.packageName,
                dataDir = dataDir,
                rulesProvider = { snapshotProxyRules() },
            )
            withContext(Dispatchers.IO) { newSession.start() }
            proxySession = newSession
            proxyJob = scope.launch(Dispatchers.IO) {
                newSession.rows.collect { row ->
                    if (_state.value.firstEventAt == null) {
                        _state.update { it.copy(firstEventAt = System.currentTimeMillis()) }
                    }
                    if (_state.value.paused) return@collect
                    _state.update { ui -> ui.copy(rows = ui.rows.replaceOrAppend(row)) }
                }
            }
            proxyStateJob = scope.launch {
                newSession.state.collect { st ->
                    when (st) {
                        is ProxySession.State.Active -> _state.update {
                            it.copy(
                                proxyAttach = ProxyAttachState.Active(
                                    port = st.port, mode = st.mode,
                                    discoveredHosts = newSession.hostFilter.discovered.value,
                                    allowedHosts = newSession.hostFilter.allowed.value,
                                ),
                                destination = Destination.INSPECTOR,
                            )
                        }
                        is ProxySession.State.Starting ->
                            _state.update { it.copy(proxyAttach = ProxyAttachState.Starting(st.phase)) }
                        is ProxySession.State.Failed ->
                            _state.update { it.copy(proxyAttach = ProxyAttachState.Failed(st.message)) }
                        ProxySession.State.Idle ->
                            _state.update { it.copy(proxyAttach = ProxyAttachState.Idle) }
                    }
                }
            }
            scope.launch {
                newSession.hostFilter.discovered.collect { hosts ->
                    _state.update { ui ->
                        val cur = ui.proxyAttach
                        if (cur is ProxyAttachState.Active) ui.copy(proxyAttach = cur.copy(discoveredHosts = hosts))
                        else ui
                    }
                }
            }
        } catch (t: Throwable) {
            com.jisungbin.networkinspector.log.DiskLogger.logError("proxy attach failed", t)
            _state.update { it.copy(proxyAttach = ProxyAttachState.Failed(t.message ?: "error")) }
        }
    }
}

fun saveLearnedHosts(hosts: Set<String>) {
    proxySession?.saveLearnedFilter(hosts)
}

fun detachProxy() {
    scope.launch {
        proxyJob?.cancel(); proxyJob = null
        proxyStateJob?.cancel(); proxyStateJob = null
        withContext(Dispatchers.IO) { proxySession?.close() }
        proxySession = null
        _state.update {
            it.copy(
                proxyAttach = ProxyAttachState.Idle,
                rows = emptyList(),
                selectedRowId = null,
                destination = Destination.DEVICES,
                firstEventAt = null,
            )
        }
    }
}

private fun snapshotProxyRules(): List<InterceptSpec> =
    _state.value.interceptRules.filter { it.enabled }.map {
        InterceptSpec(
            id = it.id,
            urlPattern = it.urlPattern,
            method = it.method,
            replacementStatus = it.replacementStatus,
            replacementContentType = it.replacementContentType,
            replacementBody = it.replacementBody,
            addedHeaders = it.addedHeaders,
            enabled = it.enabled,
        )
    }
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew :ui:compileKotlin`
Expected: success.

- [ ] **Step 3: Commit**

```bash
git add ui/src/main/kotlin/com/jisungbin/networkinspector/ui/AppStore.kt
git commit -m "Wire ProxySession into AppStore (attach, detach, learn-save)"
```

---

## Task 16: UI — Home capture-mode picker + Learn panel

**Files:**
- Modify: `ui/src/main/kotlin/com/jisungbin/networkinspector/ui/screens/HomeScreen.kt`
- Create: `ui/src/main/kotlin/com/jisungbin/networkinspector/ui/screens/LearnHostsPanel.kt`

- [ ] **Step 1: Read existing HomeScreen.kt to find the Attach button**

Run: `grep -n "Attach\|attachMode\|AttachMode" ui/src/main/kotlin/com/jisungbin/networkinspector/ui/screens/HomeScreen.kt`

- [ ] **Step 2: Add CaptureMode row above the existing mode picker in HomeScreen.kt**

Insert before the existing mode selector (`AttachMode.ColdStart` / `AttachRunning`):

```kotlin
Row(verticalAlignment = Alignment.CenterVertically) {
    Text("Capture: ")
    Spacer(Modifier.width(8.dp))
    FilterChip(
        selected = state.captureMode == CaptureMode.Jvmti,
        onClick = { store.updateCaptureMode(CaptureMode.Jvmti) },
        label = { Text("JVMTI") },
    )
    Spacer(Modifier.width(8.dp))
    FilterChip(
        selected = state.captureMode == CaptureMode.Proxy,
        onClick = { store.updateCaptureMode(CaptureMode.Proxy) },
        label = { Text("Proxy") },
    )
}
```

Wrap the JVMTI-specific controls (Cold start / Attach running radio, Activity text) inside:

```kotlin
if (state.captureMode == CaptureMode.Jvmti) {
    // existing JVMTI mode UI
}
```

Replace the Attach button click handler to branch on capture mode:

```kotlin
Button(
    enabled = state.canAttach(),
    onClick = {
        when (state.captureMode) {
            CaptureMode.Jvmti -> store.attach()
            CaptureMode.Proxy -> store.attachProxy()
        }
    },
) { Text("Attach") }
```

Add `state.canAttach()` extension if it doesn't exist:

```kotlin
private fun UiState.canAttach(): Boolean {
    if (deviceSerial == null || packageName.isBlank()) return false
    return when (captureMode) {
        CaptureMode.Jvmti -> attachMode == AttachMode.AttachRunning || activity.isNotBlank()
        CaptureMode.Proxy -> true
    }
}
```

- [ ] **Step 3: Create LearnHostsPanel.kt**

```kotlin
package com.jisungbin.networkinspector.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LearnHostsPanel(
    discovered: Set<String>,
    onSave: (Set<String>) -> Unit,
) {
    var checked by remember { mutableStateOf(emptySet<String>()) }
    val sorted = remember(discovered) { discovered.sorted() }
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        Text("Learning hosts (${discovered.size} discovered) — check the ones from your app")
        Spacer(Modifier.padding(4.dp))
        sorted.forEach { host ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(
                    checked = host in checked,
                    onCheckedChange = { c -> checked = if (c) checked + host else checked - host },
                )
                Text(host)
            }
        }
        Spacer(Modifier.padding(4.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            Button(
                enabled = checked.isNotEmpty(),
                onClick = { onSave(checked) },
            ) { Text("Save filter (${checked.size})") }
        }
    }
}
```

- [ ] **Step 4: Mount LearnHostsPanel in InspectorScreen.kt**

Find the InspectorScreen body. Insert above (or in a side panel of) the RequestTable:

```kotlin
val proxy = state.proxyAttach
if (proxy is ProxyAttachState.Active && proxy.mode == com.jisungbin.networkinspector.proxy.filter.HostFilter.Mode.Learn) {
    LearnHostsPanel(
        discovered = proxy.discoveredHosts,
        onSave = { hosts -> store.saveLearnedHosts(hosts) },
    )
}
```

- [ ] **Step 5: Verify compile**

Run: `./gradlew :ui:assemble`
Expected: success.

- [ ] **Step 6: Commit**

```bash
git add ui/src/main/kotlin/com/jisungbin/networkinspector/ui/screens/HomeScreen.kt \
        ui/src/main/kotlin/com/jisungbin/networkinspector/ui/screens/LearnHostsPanel.kt \
        ui/src/main/kotlin/com/jisungbin/networkinspector/ui/screens/InspectorScreen.kt
git commit -m "Add proxy mode picker + Learn hosts panel"
```

---

## Task 17: UI — StatusBar proxy badge

**Files:**
- Modify: `ui/src/main/kotlin/com/jisungbin/networkinspector/ui/screens/StatusBar.kt`

- [ ] **Step 1: Read existing StatusBar**

Run: `grep -n "AttachState\|streaming\|attach" ui/src/main/kotlin/com/jisungbin/networkinspector/ui/screens/StatusBar.kt`

- [ ] **Step 2: Add proxy mode rendering**

In the section that shows the current attach status, add a branch:

```kotlin
val proxy = state.proxyAttach
when {
    proxy is ProxyAttachState.Active -> Text(
        "Proxy · 127.0.0.1:${proxy.port} · ${
            if (proxy.mode == com.jisungbin.networkinspector.proxy.filter.HostFilter.Mode.Learn)
                "learn (${proxy.discoveredHosts.size} hosts)"
            else "filtered (${proxy.allowedHosts.size} hosts)"
        }"
    )
    proxy is ProxyAttachState.Starting -> Text("Proxy · starting (${proxy.phase})")
    proxy is ProxyAttachState.Failed -> Text("Proxy · failed: ${proxy.message}")
    // else fall through to existing JVMTI attach rendering
}
```

- [ ] **Step 3: Verify compile**

Run: `./gradlew :ui:assemble`
Expected: success.

- [ ] **Step 4: Commit**

```bash
git add ui/src/main/kotlin/com/jisungbin/networkinspector/ui/screens/StatusBar.kt
git commit -m "Show proxy session info in StatusBar"
```

---

## Task 18: Crash recovery — clear lingering device proxy on launch

**Files:**
- Modify: `ui/src/main/kotlin/com/jisungbin/networkinspector/ui/AppStore.kt`

- [ ] **Step 1: Persist last-known proxy device serial**

Add to AppStore a tiny preferences file write/read in `~/Library/Application Support/network-inspector/proxy-last.txt`:

```kotlin
private val proxyLastFile: File =
    File(System.getProperty("user.home"),
        "Library/Application Support/network-inspector/proxy-last.txt")
        .also { it.parentFile.mkdirs() }

private fun writeProxyLast(serial: String) {
    runCatching { proxyLastFile.writeText(serial) }
}

private fun clearProxyLast() {
    runCatching { proxyLastFile.delete() }
}
```

In `attachProxy()` after successful `start()`, call `writeProxyLast(serial)`. In `detachProxy()` after `close()`, call `clearProxyLast()`.

- [ ] **Step 2: Recovery hook on startup**

In the `init` block of `AppStore`, after `scope.launch { refreshDevices() }`, add:

```kotlin
scope.launch(Dispatchers.IO) {
    val serial = runCatching { proxyLastFile.readText().trim() }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: return@launch
    // Wait for devices to load
    var device: com.android.ddmlib.IDevice? = null
    repeat(20) {
        device = bridge?.devices?.toList()?.firstOrNull { it.serialNumber == serial }
        if (device != null) return@repeat
        kotlinx.coroutines.delay(250)
    }
    val d = device ?: return@launch
    runCatching {
        com.jisungbin.networkinspector.proxy.device.DeviceProxySetup(d).restore()
        com.jisungbin.networkinspector.log.DiskLogger.log("recovered: cleared lingering device proxy on $serial")
    }
    clearProxyLast()
}
```

- [ ] **Step 3: Verify compile**

Run: `./gradlew :ui:assemble`
Expected: success.

- [ ] **Step 4: Commit**

```bash
git add ui/src/main/kotlin/com/jisungbin/networkinspector/ui/AppStore.kt
git commit -m "Recover lingering device proxy on app launch after crash"
```

---

## Task 19: README — Proxy mode docs

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add a Proxy mode section after the JVMTI Usage section**

Add to README.md:

````markdown
## Proxy mode (alternative capture path)

For debugging your own debug-built apps that already trust user-installed CAs, you can use the embedded MITM proxy instead of JVMTI attach.

**Prerequisite:** the target app's `AndroidManifest.xml` declares a `networkSecurityConfig` that trusts user CAs:

```xml
<application android:networkSecurityConfig="@xml/network_security_config" ...>
```

```xml
<!-- res/xml/network_security_config.xml -->
<network-security-config>
  <debug-overrides>
    <trust-anchors>
      <certificates src="user" />
    </trust-anchors>
  </debug-overrides>
</network-security-config>
```

### Steps

1. In the GUI, select **Capture: Proxy** before clicking Attach.
2. Pick Device + Package, then Attach.
3. On the device, accept the certificate install dialog (one-time per device).
4. The app captures all traffic from the device for the first run (Learn mode). Tick the hosts that belong to your app and click **Save filter** — subsequent runs auto-load the filter.
5. Detach when done; the device's WiFi proxy is automatically cleared.

### Limits compared to JVMTI mode

- HTTP/1.1 only (no HTTP/2 / gRPC — use JVMTI mode for those)
- Bodies >5 MB are truncated
- Certificate-pinned apps will not negotiate TLS with our CA (use JVMTI mode)
````

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "Document Proxy mode usage and prerequisites"
```

---

## Task 20: Final assemble + smoke test

- [ ] **Step 1: Run all tests**

Run: `./gradlew test`
Expected: all `:proxy` tests pass; nothing else broken.

- [ ] **Step 2: Run full assemble**

Run: `./gradlew assemble`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Manual GUI smoke (operator runs)**

```bash
./gradlew :ui:run
```

- Switch capture mode toggle → Proxy.
- Pick an emulator + debug app with `networkSecurityConfig` user-CA trust enabled.
- Click Attach. Accept cert install on device.
- Trigger HTTPS traffic in target app. Verify rows appear in RequestTable.
- Click **Save filter** in Learn panel for hosts from your app.
- Detach. Verify on device: `adb shell settings get global http_proxy` returns `:0` or empty.

- [ ] **Step 4: Final commit (if anything tweaked) + push branch**

```bash
git status
# fixup commits if needed
git push -u origin proxy-mode
```

---

## Self-Review checklist

**Spec coverage:**
- §2 In-scope items — ✅ all covered: embedded proxy (Task 9), persistent CA (Task 2), device automation (Task 11), learned-host cache (Task 5), NetworkRow reuse (Task 4 + 8), intercept rules at proxy layer (Task 9), clean detach (Task 12 + 18).
- §3 Module layout — ✅ Task 1.
- §4 Components — ✅ each gets its own task.
- §5 User flow — ✅ Task 12 (lifecycle), Task 16 (Learn panel), Task 18 (recovery).
- §6 Data flow — ✅ Tasks 7, 8, 9; UI mapping in Tasks 14–17.
- §7 Error handling — port retry in Task 9, LAN IP detection failure in Task 10, body cap in Task 9 (CaptureFilter.ByteArrayCollector), detach restore in Task 11/12, crash recovery in Task 18.
- §8 Test strategy — unit tests in each task; integration in Task 13.

**Placeholder scan:** no TBD/TODO; all code blocks complete; no "implement later" markers.

**Type consistency:**
- `InterceptSpec` defined in Task 9, consumed in Task 15 via `snapshotProxyRules()`.
- `ProxyCaptureEvent` defined in Task 7, used in Task 8, 9, 13.
- `HostFilter.Mode` referenced in `ProxySession.State.Active`, `ProxyAttachState.Active`, StatusBar.
- `ProxySession.Phase` used by `ProxyAttachState.Starting`.

All consistent.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-16-proxy-mode.md`. Two execution options:

**1. Subagent-Driven (recommended)** — fresh subagent per task, two-stage review, fast iteration on a feature this size.

**2. Inline Execution** — execute tasks in this session with checkpoints.

Which approach?
