# @@ AWAGAM Android 1.0.0 Launch Plan

What is left before the first release.

## Releasing

### Play Console

* [ ] Install the Play-signed build from the internal track and verify filtering, the first-run VPN consent prompt, and auto-start on boot
* [ ] Decide whether to exclude Chromebooks under device targeting—on ChromeOS `VpnService` covers Android app traffic only, not the browser or the system
* [ ] Promote the same bundle to production, and allow several days for review

### F-Droid

Optional for 1.0.0—the Play listing does not depend on it. Listing text and images are already in place under `fastlane/`.

* [ ] Tag `v1.0.0`; F-Droid builds from a tag, not from `main`
* [ ] Open a merge request against [fdroiddata](https://gitlab.com/fdroid/fdroiddata) adding `metadata/com.awagam.android.yml` (`AutoName`, `RepoType: git`, `Repo`, a `builds` entry pinning the tag and `gradle: yes`, `AutoUpdateMode`, `UpdateCheckMode: Tags`)

## Post-Release

* [ ] Fill the store URL(s) on the IA Defensa solution page
* [ ] Publish the announcement post
* [ ] Delete this plan file