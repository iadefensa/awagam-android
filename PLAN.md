# @@ AWAGAM Android Launch Plan

1.0.0 is out, distributed directly and through the own F-Droid repository, and the download page is live. F-Droid merged the listing and built it reproducibly; what’s left is its publication and the announcement.

Google Play is deferred, not ruled out—the AAB build and the Play Console declarations in the README stay where they are, so the option remains open at the cost of one upload.

## F-Droid

* [ ] Wait for [the listing](https://f-droid.org/packages/com.awagam.android/) to go live
  - [ ] Update the README’s _Distribution_ table (which describes the listing as in review) and the landing page (which offers only the own repository)
* [ ] Once F-Droid publishes, check that the APK it serves carries the certificate the README documents rather than F-Droid’s (`apksigner verify --print-certs`)—a different fingerprint means the reproducible-build path broke and every install would have to be redone to switch channels

## Play Store

* [ ] Establish whether Play App Signing accepts the existing key—if it does, Play builds keep the certificate the README documents and stay update-compatible with direct and F-Droid installations; if not, adding Play later splits the installed base, and every month of deferral makes that split bigger (the README assumes the latter)

## Post-Release

* [ ] Publish the announcement post
* [ ] Delete this plan file