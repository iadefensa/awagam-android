# @@ AWAGAM Android 1.0.0 Launch Plan

What is left before the first release.

## Releasing

### Play Console

Answer text for all four is prepared under “Play Console Declarations” in [the README](README.md).

* [ ] Declare `VpnService` use, and justify the `specialUse` foreground service type
* [ ] Complete the Data safety form—the app collects nothing; see [the privacy policy](PRIVACY.md)
* [ ] Link the privacy policy
* [ ] Complete the content rating questionnaire

### F-Droid

Optional for 1.0.0—the Play listing does not depend on it. Listing text and images are already in place under `fastlane/`.

* [ ] Tag `v1.0.0`; F-Droid builds from a tag, not from `main`
* [ ] Open a merge request against [fdroiddata](https://gitlab.com/fdroid/fdroiddata) adding `metadata/com.awagam.android.yml` (`AutoName`, `RepoType: git`, `Repo`, a `builds` entry pinning the tag and `gradle: yes`, `AutoUpdateMode`, `UpdateCheckMode: Tags`)

## Post-Release

* [ ] Fill the store URL(s) on the IA Defensa solution page
* [ ] Publish the announcement post
* [ ] Delete this plan file