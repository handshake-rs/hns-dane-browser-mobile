# Store assets

This directory contains reviewed, version-controlled inputs for the Apple App
Store and Google Play listings: metadata, screenshots, artwork, validation
code, and submission records.

`dist/` is reserved for ignored generated release artifacts such as APKs,
AABs, mapping archives, and staged upload outputs. Generated artifacts must not
be copied into this source directory or committed. Store workflows should read
listing inputs from `store-assets/` and write distributable outputs beneath
`dist/`.
