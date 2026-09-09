# Release Signing — AI-V0

Release signing comes **exclusively from environment variables / CI secrets**;
no keystore material is ever committed to the repository.

## Where it is configured

`app/build.gradle.kts` → `signingConfigs.release`:

| Variable | Purpose |
|---|---|
| `KEYSTORE_PATH` | Path to the upload keystore (`.jks`). Defaults to `${rootDir}/my-upload-key.jks` for local builds. |
| `STORE_PASSWORD` | Keystore password. |
| `KEY_ALIAS` | Key alias — the workflow uses `upload`. |
| `KEY_PASSWORD` | Key password. |

## CI secrets (release.yml)

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 my-upload-key.jks` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | `upload` |
| `KEY_PASSWORD` | key password |

## Producing a release

1. Generate the upload keystore once (keep it safe — it is the release identity):
   ```bash
   keytool -genkeypair -v \
     -keystore my-upload-key.jks -alias upload \
     -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Add the four repository secrets above.
3. Tag a release: `git tag v1.0.0 && git push origin v1.0.0` — `release.yml`
   runs release unit tests, builds the minified signed AAB, publishes a
   SHA-256 provenance manifest and opens a **draft** GitHub release.
4. Verify the artifact hash against `aab-sha256.txt` before publishing the
   draft.

## Local verification (no secrets needed)

```bash
./gradlew :app:bundleRelease          # unsigned R8 build — release-readiness gate
```

The debug build type uses the standard AGP auto-generated debug keystore
(`~/.android/debug.keystore`) — a clean clone builds with zero manual steps.
