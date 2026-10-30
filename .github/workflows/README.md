# CI

`android-ci.yml` runs on pull requests and pushes to `main` with `contents: read`.
It uses no repository secrets and has no signing or release-publishing step. The
existing build job runs car unit tests, car lint, and car/phone debug builds; its
debug APK and test-report uploads remain review artifacts, not signed releases.

Security checks are scoped to prevent disclosing existing sensitive material:

- Gitleaks 8.30.1 scans commits introduced by the pull request or push. If a
  push has no prior base SHA, it scans available history instead. Its Linux
  binary is SHA-256 verified, results are fully redacted, and reports and
  scanner logs are not uploaded or printed.
- Semgrep 1.182.0 runs the `p/default` ruleset with metrics disabled and reports
  only findings new relative to the PR base or prior `main` commit; without a
  prior base SHA, it scans the full checkout. Reports and scanner logs are not
  uploaded or printed.
- Dependency Review runs on pull requests for runtime, development, and unknown
  scopes, failing on any newly introduced vulnerability at low severity or
  above. License checking is enabled, but no allow-list is enforced until
  maintainers approve a license policy.

The secret scan is not a whole-tree or history scan in normal PR/push events:
pre-existing files and findings outside the introduced commit range are
intentionally not inspected by this phase. No platform signing key, receiver,
or Telegram-token configuration is changed here.

## Repository settings to apply manually

1. Enable **Settings → Code security and analysis → Dependency graph** so
   Dependency Review can compare pull-request manifests.
2. Protect `main` with a pull request requirement, at least one approval, stale
   approval dismissal, up-to-date branches, and restrictions on force-pushes
   and deletion. Require these successful status checks:
   - `Build (github debug)`
   - `Unit tests (car debug)`
   - `Secret scan (introduced commits)`
   - `Dependency review`
   - `Semgrep SAST (new findings)`
3. Enable secret scanning and push protection under **Code security and
   analysis** wherever the repository plan supports them. These GitHub
   settings are separate from the read-only Gitleaks CI job.
4. Approve a license allow-list before adding one to Dependency Review; no
   project-wide license policy is assumed by this workflow.

The Pages workflow separately deploys only `site/` after regenerating all five
language editions. Pages uses the GitHub Actions deployment source.

## Releases

`release.yml` runs only when a tag matching `vX.Y.Z` is pushed. It requires the
tagged commit to be on `main` and the tag to equal `versionName` in
`app/build.gradle.kts`, runs the car unit tests, builds `phoneRelease` and `carRelease`, signs the phone APK with the release key and the car
APK with the platform key in `tools/platform.*` (the MG4 firmware requires it for
`android.uid.system`; the job fails unless the certificate matches the expected
SHA-256), verifies both with `apksigner`, and publishes `DiAuto-MG4-vX.Y.Z-car.apk`, `DiAuto-MG4-vX.Y.Z-phone.apk`
and one `.sha256` per APK to a GitHub release (an existing release for the same
tag is replaced). Signing runs in a read-only job; only the final publish job has
`contents: write`.

Create a GitHub **environment** named `release` (optionally with required
reviewers) holding these secrets:

- `RELEASE_KEYSTORE_BASE64` — the keystore, e.g. `base64 -w0 headunit-release-key.jks`
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

To release: bump `versionCode`/`versionName`, merge to `main`, then
`git tag vX.Y.Z && git push origin vX.Y.Z`.
