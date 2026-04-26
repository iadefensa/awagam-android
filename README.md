# IA Defensa AWAGAM TLD and Domain Blocker

Visit IA Defensa for [general information about this app](@@).

## Development

### Requirements

* Android Studio Otter 3 Feature Drop (2025.2.3) or newer
* JDK 21
* Android SDK 36

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
# Signed APK (for direct distribution)
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
| **Direct** | APK | Distribute the signed `.apk` file |

### Project Structure

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

Tests: `BlocklistParserTest`, `BlocklistValidatorTest`, `DnsPacketTest`, `DomainMatcherTest`, `ExternalBlocklistManagerTest`, `HomeViewModelTest`

## Contributing

[Contributions are welcome.](CONTRIBUTING.md) They are subject to the [Contributor License Agreement](CLA.md).