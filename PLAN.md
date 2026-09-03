# @@ AWAGAM Android Launch Plan

1.0.0 is out, distributed directly and through the own F-Droid repository, and the download page is live. What is left is the F-Droid listing and the announcement.

Google Play is deferred, not ruled out—the AAB build and the Play Console declarations in the README stay where they are, so the option remains open at the cost of one upload.

## F-Droid

* [ ] Wait for [merge request #47708](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/47708) to be processed
  - [ ] Update the README’s _Distribution_ table (which describes the listing as in review)
* [ ] Once F-Droid publishes, check that the APK it serves carries the certificate the README documents rather than F-Droid’s (`apksigner verify --print-certs`)—a different fingerprint means the reproducible-build path broke and every install would have to be redone to switch channels

## Play Store

* [ ] Establish whether Play App Signing accepts the existing key—if it does, Play builds keep the certificate the README documents and stay update-compatible with direct and F-Droid installations; if not, adding Play later splits the installed base, and every month of deferral makes that split bigger (the README assumes the latter)

## Post-Release

* [ ] Publish the announcement post
* [ ] Delete this plan file