# @@ AWAGAM Android 1.0.0 Launch Plan

What is left before the first release.

## Releasing

### Play Console

* [x] Promote the same bundle to production, and allow several days for review
* [ ] Decide whether to exclude Chromebooks under device targeting (_Test and release_ → _Reach and devices_ → _Device catalog_)

### F-Droid

Optional for 1.0.0—the Play listing does not depend on it. Listing text and images are already in place under `fastlane/`.

* [ ] Tag `v1.0.0`; F-Droid builds from a tag, not from `main`
* [ ] Open a merge request against [fdroiddata](https://gitlab.com/fdroid/fdroiddata) adding `metadata/com.awagam.android.yml` (`AutoName`, `RepoType: git`, `Repo`, a `builds` entry pinning the tag and `gradle: yes`, `AutoUpdateMode`, `UpdateCheckMode: Tags`)

## Post-Release

* [ ] Fill the store URL(s) on the IA Defensa solution page
* [ ] Publish the announcement post
* [ ] Delete this plan file