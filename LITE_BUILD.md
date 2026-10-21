# ckReader Lite 1.7.0

Lite is an ARM64 phone/tablet edition of ckReader. It keeps local folders,
WebDAV, FTP/SFTP, BookOasis, RClone, reading, font packs and optional voice
modules. Its automatic book metadata search uses the existing public web
fallback and local YAML. It does not offer in-app Google sign-in or contact the
Aladin, National Library of Korea or Data4Library OpenAPIs. Their key settings
and connection tests are hidden. The direct Google Drive source option is hidden;
RClone remains available as an independent storage extension.

## Build in the complete source tree

```bash
./gradlew :app:assembleLiteRelease --no-daemon --console=plain
```

Run `:app:testLiteReleaseUnitTest` to check the Lite behavior and shared reader
logic. Tests for the removed direct Google Drive and official OpenAPI clients
are skipped in Lite and continue to run in the full editions.

The APK is `app/build/outputs/apk/lite/release/app-lite-release.apk` when
`signing/release.properties` and its original JKS are available. Without them,
Gradle produces `app-lite-release-unsigned.apk`. `-PckreaderUnsigned=true`
explicitly selects the unsigned build. Debug builds use `assembleLiteDebug`.

The lite, standard and e-ink editions have the same application ID
`com.ckchoi.ckreader` and version code 85. They replace one another when signed
with the same certificate; they cannot be installed side by side. Lite reports
version `1.7.0-lite` and displays `ckReader Lite`. Switching editions preserves
the app's existing library data. A saved in-app Google Drive source cannot be
opened in Lite; switch back to standard/e-ink to use it. RClone sources still
work when the extension APK is installed.

## Build from the Lite-only source ZIP

The standalone ZIP includes the app, the two reader library modules, Gradle
wrapper and build instructions. It omits optional module projects and all
personal keys. Extract it, then run:

```bash
./gradlew :app:assembleLiteRelease --no-daemon --console=plain
```

On Windows, use `gradlew.bat`. The archive's `gradle.properties` sets
`ckreaderLiteOnly=true`, so only the Lite product flavor is configured. To sign
an APK that updates an installed ckReader, copy the original JKS to a private
build host, create `signing/release.properties` from the example, and use the
same certificate. Never put those files back into the source ZIP.

To rebuild the standalone ZIP from the complete tree:

```bash
python3 scripts/package-lite-source.py --output releases/ckReader-v1.7.0-lite-source.zip
python3 scripts/package-lite-source.py --verify releases/ckReader-v1.7.0-lite-source.zip
```
