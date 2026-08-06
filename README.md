# IA Defensa AWAGAM TLD and Domain Blocker

Visit IA Defensa for [general information about this app](https://iadefensa.com/solutions/awagam-android/).

## Development

### Requirements

* Android Studio Otter 3 Feature Drop (2025.2.3) or newer
* JDK 21
* Android SDK 37

### Debug Build

```shell
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/awagam-*.apk`

Debug builds are not minified and keep debug logging, so they don’t reflect what ships. Verify behavior against a release build before distributing.

### Tests

```shell
./gradlew testDebugUnitTest
```

Unit tests also run automatically before `assembleDebug` and `assembleRelease`.

### Release Build

Without a signing key the release build still succeeds, but the APK is unsigned and cannot be installed on a device. To produce an installable build, first create a keystore:

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

Each release after the first needs `versionCode` incremented in `app/build.gradle.kts`; Android refuses to install a build whose `versionCode` is not higher than the installed one.

### Running on a Device

```shell
./gradlew assembleRelease && adb install -r app/build/outputs/apk/release/awagam-*.apk
```

`-r` replaces the installed app while preserving its configuration. Switching signing keys requires `adb uninstall com.awagam.android` first, as Android rejects an update signed with a different key. Expect to grant VPN consent again after reinstalling.

The app ships with no blocking rules, so a fresh install blocks nothing until a blocklist is added under Settings. [The AWAGAM blocklists repository](https://github.com/j9t/awagam-blocklists) has ready-made lists for testing.

### Distribution

| Platform | Format | Notes |
| --- | --- | --- |
| **Play Store** | AAB | Upload the `.aab` file |
| **Direct** | APK | Distribute the signed `.apk` file |
| **F-Droid** | APK | Built from source by F-Droid; listing metadata in `fastlane/` |

The build is configured for reproducibility (`org.gradle.reproducibleFileOrder`, `org.gradle.reproducibleArchiveContents`, and `dependenciesInfo` omitted from APK and bundle). Release builds also strip debug logging via ProGuard, so no DNS query data reaches logcat—see [the privacy policy](PRIVACY.md).

### Project Structure

```
com.awagam.android/
├── AWAGAMApplication.kt     # Init, DI, notifications, WorkManager
├── MainActivity.kt          # Entry, VPN permission, navigation
├── data/blocklist/          # BlocklistRepository, DomainMatcher, Exporter, Models, Validator, ExternalBlocklistManager
├── data/preferences/        # UserPreferences (DataStore), DnsProviders (upstream catalog)
├── di/                      # DependencyContainer
├── dns/                     # DnsCache (LRU+TTL), DnsResolver (DoH)
├── receiver/                # BootReceiver
├── statistics/              # StatisticsManager
├── ui/screens/              # Home, Settings, Statistics
├── ui/viewmodel/            # Home, Settings, Statistics ViewModels
├── ui/theme/                # Material 3 theme
├── util/                    # NumberFormatting
├── vpn/                     # AWAGAMVpnService
└── worker/                  # BlocklistUpdateWorker (6h check, 24h per list), VpnWatchdogWorker (15min)
```

Tests: `BlocklistParserTest`, `BlocklistRefreshIntervalTest`, `BlocklistValidatorTest`, `DnsCacheTest`, `DnsPacketTest`, `DnsProvidersTest`, `DnsResolverTest`, `DomainMatcherTest`, `ExternalBlocklistManagerTest`, `HomeViewModelTest`, `NumberFormattingTest`, `SettingsViewModelTest`, `StatisticsManagerTest`

## Contributing

[Contributions are welcome.](CONTRIBUTING.md) They are subject to the [Contributor License Agreement](CLA.md).

## License

AWAGAM Android is free software, licensed under [the GNU General Public License, version 3 or later](LICENSE.txt). It comes with no warranty.

Commercial terms are available on request for use that the GPL does not accommodate.