# @@ AWAGAM Android Launch Plan

1.0.0 is out, distributed directly and through our own F-Droid repository. What is left is the F-Droid listing, the download page, and the announcement.

Google Play is deferred, not ruled out—the AAB build and the Play Console declarations in the README stay where they are, so the option remains open at the cost of one upload.

## F-Droid

Listing text and images are already in place under `fastlane/`, and `v1.0.0` is tagged.

* [ ] Submit to [IzzyOnDroid](https://apt.izzysoft.de/fdroid/) first—it takes our own signed APKs straight from GitHub releases, so it reaches F-Droid clients without waiting on the main repository’s build queue
* [ ] Open a merge request against [fdroiddata](https://gitlab.com/fdroid/fdroiddata) adding `metadata/com.awagam.android.yml` (`AutoName`, `RepoType: git`, `Repo`, a `builds` entry pinning the tag and `gradle: yes`, `AutoUpdateMode`, `UpdateCheckMode: Tags`)
* [ ] Use the reproducible-build path (`Binaries` plus `AllowedAPKSigningKeys`) rather than letting F-Droid sign: F-Droid then verifies its build against ours and publishes our APK. This keeps direct and F-Droid installations interchangeable instead of forcing an uninstall to switch, and it keeps the APK under the one certificate registered for developer verification—F-Droid’s own signing key is not ours to register

## Open

* Rotating the app signing key means registering the new certificate with Google again, and rotating the repository key means every subscriber re-adds the repository.
* Developer verification is enforced from 2026-09-30 in Brazil, Indonesia, Singapore, and Thailand, and elsewhere from 2027.

## Post-Release

* [ ] Publish the announcement post
* [ ] Delete this plan file