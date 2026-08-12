# @@ AWAGAM Android 1.0.0 Launch Plan

What is left before the first release. Delete this file once 1.0.0 is out.

## Store assets

* [ ] Take screenshots of the finished app (at least 2 [1080×1920?], phone-sized)
  - [ ] Add them to `fastlane/metadata/android/en-US/images/phoneScreenshots/`
* [ ] Add a 1024×500 feature graphic as `featureGraphic.png` in `fastlane/metadata/android/en-US/images/` (Play Store requires it; the 512×512 `icon.png` is already in place there)
* [ ] Fill the screenshot and release placeholders in the [iadefensa.com solution page](https://iadefensa.com/solutions/awagam-android/)

## Version

* [ ] Set the release date in `CHANGELOG.md` (currently `@@`)

## Signing

* [ ] Generate the release keystore, as described under “Release Build” in [the README](README.md)
* [ ] Store keystore and credentials somewhere they survive this machine—losing them means the Play listing can never be updated
* [ ] Build and install a signed release APK on a device, and verify filtering end to end against a real blocklist

## Play Console

* [ ] Declare `VpnService` use, and justify the `specialUse` foreground service type
* [ ] Complete the Data safety form—the app collects nothing; see [the privacy policy](PRIVACY.md)
* [ ] Link the privacy policy
* [ ] Complete the content rating questionnaire