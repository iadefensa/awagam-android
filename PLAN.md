# @@ AWAGAM Android 1.0.0 Launch Plan

What is left before the first release. Delete this file once 1.0.0 is out.

## Signing

Note: `keystore.properties` currently points at `awagam-test-only.jks`, and a release APK built today is signed `CN=AWAGAM TEST ONLY - DO NOT DISTRIBUTE`. Point it at the real keystore before building anything for upload—whatever key signs the first upload becomes the upload key for the life of the listing.

* [ ] Generate the release keystore, as described under “Release Build” in [the README](README.md)
* [ ] Store keystore and credentials somewhere they survive this machine—losing them means the Play listing can never be updated
* [ ] Build and install a signed release APK on a device, and verify filtering end to end against a real blocklist

## Releasing

### Play Console

Answer text for all four is prepared under “Play Console Declarations” in [the README](README.md).

* [ ] Declare `VpnService` use, and justify the `specialUse` foreground service type
* [ ] Complete the Data safety form—the app collects nothing; see [the privacy policy](PRIVACY.md)
* [ ] Link the privacy policy
* [ ] Complete the content rating questionnaire

### F-Droid

* [ ] @@