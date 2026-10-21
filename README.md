# ckReader Lite 1.7.0

Lite is an ARM64 phone/tablet edition of ckReader. It keeps local folders,
WebDAV, FTP/SFTP, BookOasis, RClone, reading, font packs and optional voice
modules. Its automatic book metadata search uses the existing public web
fallback and local YAML. RClone remains available as an independent storage extension.

## Build in the complete source tree

```bash
./gradlew :app:assembleLiteRelease --no-daemon --console=plain
```

Run `:app:testLiteReleaseUnitTest` to check the Lite behavior and shared reader
logic. 

The APK is `app/build/outputs/apk/lite/release/app-lite-release.apk` when
`signing/release.properties` and its original JKS are available. Without them,
Gradle produces `app-lite-release-unsigned.apk`. `-PckreaderUnsigned=true`
explicitly selects the unsigned build. Debug builds use `assembleLiteDebug`.


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
