# @@ AWAGAM Android Launch Plan

1.0.0 is out, distributed directly and through the own F-Droid repository, and the download page is live. What is left is the F-Droid listing and the announcement.

Google Play is deferred, not ruled out—the AAB build and the Play Console declarations in the README stay where they are, so the option remains open at the cost of one upload.

## F-Droid

Listing text and images are already in place under `fastlane/`, and `v1.0.0` is tagged. A local build of that tag reproduces the released APK byte for byte, so the reproducible-build path is available.

* [ ] Open a merge request against [fdroiddata](https://gitlab.com/fdroid/fdroiddata) adding `metadata/com.awagam.android.yml` (`AutoName`, `RepoType: git`, `Repo`, a `Builds` entry pinning the tag and `gradle: yes`, `AutoUpdateMode`, `UpdateCheckMode: Tags`)
* [ ] Use the reproducible-build path (`Binaries` plus `AllowedAPKSigningKeys`) rather than letting F-Droid sign: F-Droid then verifies its build against the one here and publishes this repo’s APK; this keeps direct and F-Droid installations interchangeable

## Open

* Rotating the app signing key means registering the new certificate with Google again, and rotating the repository key means every subscriber re-adds the repository.
* Developer verification is enforced from 2026-09-30 in Brazil, Indonesia, Singapore, and Thailand, and elsewhere from 2027.
* Whether Play App Signing accepts the existing key. (If it does, Play builds keep the certificate the README documents and stay update-compatible with direct and F-Droid installations; if not, adding Play later splits the installed base, and every month of deferral makes that split bigger. The README currently assumes the latter.)

## Post-Release

* [ ] Publish the announcement post
* [ ] Delete this plan file