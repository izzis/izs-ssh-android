# Releasing izs SSH (Android)

Signed release APKs are built on demand through GitHub Actions — never on
every commit. This document covers the one-time setup and the per-release
flow. No secrets are stored in this repository; only secret *names* are
referenced by the workflow.

## One-time setup: release key

1. Generate a release keystore (keep the passwords somewhere safe, e.g. a
   password manager):
   ```bash
   keytool -genkeypair -v -keystore release.jks -alias sshclient \
     -keyalg RSA -keysize 2048 -validity 10000
   ```
   Never commit `release.jks` — it is gitignored.
2. Register four Actions secrets (`Settings → Secrets and variables →
   Actions`):
   | Secret | Value |
   |---|---|
   | `ANDROID_KEYSTORE_BASE64` | `base64 -w0 release.jks` output |
   | `KEYSTORE_PASSWORD` | keystore password |
   | `KEY_ALIAS` | key alias (`sshclient`) |
   | `KEY_PASSWORD` | key password |
3. Back up `release.jks` plus its passwords in at least two places besides
   the build machine. Losing the key means published apps can never be
   updated again (a new `applicationId` would be required); leaking it
   lets anyone sign APKs in your name.

The CI job recreates `keystore.properties` from these secrets at build
time only — see `app/build.gradle.kts` (local builds without that file
keep working; the release APK is then left unsigned instead of failing).

## Cutting a release

1. Make sure `main` is green: `./gradlew :app:testDebugUnitTest`.
2. Push the commits to release. The `versionName` fallback in
   `app/build.gradle.kts` may stay on its last value (dev-build display
   in About only) — the release version always comes from the tag via
   `-PversionNameOverride` in CI, never from code. Bump it only if you
   want local dev builds to display the new version.
3. Open GitHub → **Actions** → **Build APK release** → **Run workflow**,
   enter the `tag_name` (e.g. `v1.0.0`, no spaces), tick `prerelease`
   for betas/RCs only. CLI equivalent:
   ```bash
   gh workflow run "Build APK release" --ref main -f tag_name=v1.0.0
   ```
4. When the run is green, the APK (`izs-ssh-client-v1.0.0.apk`) is attached to
   the **Releases** page together with an auto-generated changelog (diff
   since the previous tag). Run artifacts are kept for 90 days.
5. On-device install: uninstall any debug build first. Debug and release
   APKs carry different signatures, so Android refuses to overwrite one
   with the other. (Uninstalling wipes local profiles — sync first.)

## Fixing a bad release

- Bad APK, good commit: delete just the release and re-run —
  ```bash
  gh release delete v1.0.0 --cleanup-tag --yes
  ```
  `--cleanup-tag` is required: without it only the Release is removed and
  the remote tag stays behind (re-running then reuses the stale tag).
  Never reuse a tag for different contents without deleting it first.
- Orphaned tag only (release already deleted without `--cleanup-tag`):
  ```bash
  git push origin :v1.0.0
  ```
- Bad commit: fix it, rewrite history if the repo is still private,
  delete the release/tag as above, and re-run.
- Do not add `push` triggers to `.github/workflows/build-apk.yml` —
  releases stay strictly on demand.
