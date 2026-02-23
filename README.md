# AWAGAM Android TLD and Domain Blocker

A DNS-based TLD and domain blocker for Android, and companion app to the [AWAGAM browser extension](https://codeberg.org/user/awagam).

## Features

* Block TLDs and domains at the DNS level
  - Local VPN for DNS filtering (no external routing)
  - DoH (DNS-over-HTTPS) upstream (DNS4EU, Cloudflare, Google, Quad9, and others)
  - Compatible with AWAGAM browser extension blocklist format ([spec](https://github.com/iadefensa/awagam-chromium#blocklist-format), [converter](https://hell.meiert.org/awagam/))
* Add, edit, and manage blocklist URLs
* Temporarily disable protection (5 mins, 15 mins, 1 hour) with auto-restart
* DNS query statistics (queries, blocked requests, cache performance)
* Import/export configuration (AWAGAM format, Pi-hole, AdGuard Home, hosts file)
* Background blocklist updates (every 6 hours)
* VPN watchdog for automatic service recovery
* Battery optimization guidance for reliable background operation
* Auto-start on boot

## Requirements

* Android 9 (API 28) or higher

## How It Works

AWAGAM creates a local VPN that intercepts DNS queries only. Blocked domains resolve to `0.0.0.0`, preventing connections.

```
┌───────────┐     ┌───────────────────────────┐     ┌─────────────┐
│  Android  │────▶│  AWAGAM Local VPN         │────▶│  Upstream   │
│  Apps     │     │  (DNS interception only)  │     │  DNS (DoH)  │
└───────────┘     └───────────────────────────┘     └─────────────┘
                              │
                              ▼ blocked
                        ┌───────────┐
                        │  Return   │
                        │  0.0.0.0  │
                        └───────────┘
```

Your actual Internet traffic is not routed through the VPN—only DNS lookups are filtered.

## Limitations

| Limitation | Explanation |
| --- | --- |
| **No URL blocking** | DNS only sees domain names, not full URLs. Use the browser extension for URL-level blocking. |
| **VPN slot conflict** | Android allows only one VPN at a time. Cannot run alongside other VPN apps. |
| **DoH bypass** | Apps using their own DNS-over-HTTPS bypass system DNS filtering. |

### For VPN Users

If you use a VPN for privacy or work, AWAGAM cannot run simultaneously. Alternatives:

* Export blocklists and import them into Pi-hole, AdGuard Home, or your router
* Use your VPN provider’s DNS filtering if available

## Blocklist Format

Uses [the same JSON format as the browser extension](https://github.com/iadefensa/awagam-chromium#blocklist-format):

```json
{
  "group-id": {
    "name": "Human-readable group name",
    "context": "Optional description or context URL(s)",
    "tlds": [
      ".example"
    ],
    "domains": [
      "example.com",
      "example.org"
    ],
    "urls": []
  }
}
```

Note: URL patterns are parsed but ignored at DNS level.

## Privacy

* All filtering happens locally on device
* No analytics or tracking
* No data collection
* Open source for full transparency

External connections:

* DNS queries to user-selected upstream (DNS4EU Protective default)
* HTTPS fetches to user-configured blocklist URLs

## DNS Providers

The default upstream is [DNS4EU](https://www.joindns4.eu/), an EU-based, GDPR-compliant resolver that blocks malware and phishing. Supported providers:

| Provider | Features |
| --- | --- |
| **DNS4EU** | EU-based, GDPR-compliant; variants: protective (default), child-safe, no-ads, child-safe plus no-ads, unfiltered |
| **Cloudflare** | Fast global network |
| **Google** | Reliable, widely used |
| **Quad9** | Security-focused, blocks malicious domains |
| **OpenDNS** | Cisco-operated |
| **AdGuard** | Privacy-focused |

Provider IPs are hardcoded to avoid DNS lookup loops when the VPN is active.

## Building

### Requirements

* Android Studio Hedgehog or newer
* JDK 17
* Android SDK 35

### Debug Build

```shell
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/awagam-*.apk`

### Release Build

Release builds require a signing key. First, create a keystore:

```shell
keytool -genkey -v -keystore awagam-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias awagam
```

Then create `keystore.properties` in the project root (this file is gitignored):

```properties
storeFile=awagam-release.jks
storePassword=your_store_password
keyAlias=awagam
keyPassword=your_key_password
```

Build the release:

```shell
# Signed APK (for direct distribution or F-Droid)
./gradlew assembleRelease

# Android App Bundle (required for Play Store)
./gradlew bundleRelease
```

Output:
* APK: `app/build/outputs/apk/release/awagam-*.apk`
* AAB: `app/build/outputs/bundle/release/awagam-release.aab`

### Distribution

| Platform | Format | Notes |
| --- | --- | --- |
| **Play Store** | AAB | Upload the `.aab` file |
| **F-Droid** | Source | Submit repository; F-Droid builds from source |
| **Direct** | APK | Distribute the signed `.apk` file |

## Project Structure

```
com.awagam.android/
├── AWAGAMApplication.kt     # Init, DI, notifications, WorkManager
├── MainActivity.kt          # Entry, VPN permission, navigation
├── data/blocklist/          # BlocklistRepository, Exporter, Models, Validator, ExternalBlocklistManager
├── data/preferences/        # UserPreferences (DataStore)
├── di/                      # DependencyContainer
├── dns/                     # DnsCache (LRU+TTL), DnsResolver (DoH)
├── receiver/                # BootReceiver
├── statistics/              # StatisticsManager
├── ui/screens/              # Home, Settings, Statistics
├── ui/viewmodel/            # Home, Settings, Statistics ViewModels
├── ui/theme/                # Material 3 theme
├── vpn/                     # AWAGAMVpnService
└── worker/                  # BlocklistUpdateWorker (6h), VpnWatchdogWorker (15min)
```

Tests: `BlocklistParserTest`, `BlocklistValidatorTest`, `DnsPacketTest`, `DomainMatcherTest`, `HomeViewModelTest`