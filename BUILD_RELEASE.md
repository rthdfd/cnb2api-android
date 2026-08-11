# Build and Release

This document records the working build and upload procedure for the
`cnb2api-android` project.

## Repository

- Repository: `https://github.com/SUN-0v/cnb2api-android`
- Default branch: `main`
- Release visibility: private
- APK artifact: `artifacts/cnb2api-android-debug.apk`

Do not put a GitHub token in this file, a commit, a remote URL, or shell
history. Set `GITHUB_TOKEN` only in the current shell, or enter it through a
secret prompt.

## Build Versions

- JDK: 17
- Gradle: 8.10.2
- Android Gradle Plugin: 8.6.1
- `compileSdk`: 34
- `targetSdk`: 34
- `minSdk`: 26
- Java source/target compatibility: 17

The original build ran on an ARM64 Linux worker. The Android SDK's default
x86 AAPT2 caused `Exec format error`, so the build uses the ARM64 AAPT2 binary
at `/tmp/bcode/android-build/aapt2`.

## Build Environment

These paths are the provisioned worker paths used for the successful build.
They can be changed together if the toolchain is installed elsewhere.

```bash
export JAVA_HOME="/tmp/bcode/android-build/jdk"
export ANDROID_HOME="/tmp/bcode/android-build/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="/tmp/bcode/android-build/gradle-home"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

export GRADLE_BIN="/tmp/bcode/android-build/gradle/gradle-8.10.2/bin/gradle"
export AAPT2_BIN="/tmp/bcode/android-build/aapt2"
```

For a fresh SDK, install at least `platform-tools`, `platforms;android-34`,
and `build-tools;34.0.0` with `sdkmanager`, then accept the Android SDK
licenses.

## Compile

Run from the project root:

```bash
"$GRADLE_BIN" :app:assembleDebug \
  --project-cache-dir "/tmp/bcode/android-build/project-cache" \
  --no-daemon \
  -Pandroid.aapt2FromMavenOverride="$AAPT2_BIN" \
  --stacktrace
```

The Gradle output is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Copy the release artifact into the tracked directory:

```bash
mkdir -p artifacts
cp app/build/outputs/apk/debug/app-debug.apk artifacts/cnb2api-android-debug.apk
sha256sum artifacts/cnb2api-android-debug.apk
```

The checked APK from release `v1.0.0` is 42,201 bytes with SHA-256:

```text
ef23b65bfc46cd004c25f0182c20885f0af286adc705618a4301083d65edc48d
```

Build intermediates under `app/build/`, root `build/`, and `.gradle/` are
excluded by `.gitignore`.

## Create Repository

The following uses the GitHub REST API because the worker did not have the
`gh` CLI installed. Set the token without placing it in a file:

```bash
read -rsp "GitHub token: " GITHUB_TOKEN
printf '\n'
export GITHUB_TOKEN
export OWNER="SUN-0v"
export REPO="cnb2api-android"
```

Create the private repository once. Skip this step if it already exists:

```bash
curl --fail-with-body -sS -X POST \
  -H "Authorization: Bearer $GITHUB_TOKEN" \
  -H 'Accept: application/vnd.github+json' \
  -H 'Content-Type: application/json' \
  "https://api.github.com/user/repos" \
  -d '{"name":"cnb2api-android","description":"Android gateway for cnb2api and ToolForge XYML fallback","private":true,"has_issues":false,"has_projects":false,"has_wiki":false}'
```

## Commit and Push

Only source files, configuration, documentation, and the APK under
`artifacts/` should be tracked:

```bash
# Run once if this project directory is not already a Git repository.
git init -b main

git add .
git status --short
git diff --cached --stat
git -c user.name='BrowserCode' \
  -c user.email='browsercode@users.noreply.github.com' \
  commit -m 'Add Android cnb2api gateway'
```

Push without putting the token in the remote URL:

```bash
AUTH=$(printf 'x-access-token:%s' "$GITHUB_TOKEN" | base64 -w0)
git -c http.extraHeader="Authorization: Basic $AUTH" push -u origin main
```

If the remote has not been configured yet:

```bash
git remote add origin "https://github.com/$OWNER/$REPO.git"
```

## Create Release and Upload APK

Use a new tag for each release. For the current app version:

```bash
export VERSION="1.0.0"
export TAG="v$VERSION"
```

Create the release and capture its numeric ID:

```bash
RELEASE_JSON=$(curl --fail-with-body -sS -X POST \
  -H "Authorization: Bearer $GITHUB_TOKEN" \
  -H 'Accept: application/vnd.github+json' \
  -H 'Content-Type: application/json' \
  "https://api.github.com/repos/$OWNER/$REPO/releases" \
  -d "{\"tag_name\":\"$TAG\",\"target_commitish\":\"main\",\"name\":\"cnb2api Android $TAG\",\"body\":\"Android Debug APK release.\",\"draft\":false,\"prerelease\":false}")
export RELEASE_ID=$(printf '%s' "$RELEASE_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
```

Upload the APK as a release asset:

```bash
curl --fail-with-body -sS -X POST \
  -H "Authorization: Bearer $GITHUB_TOKEN" \
  -H 'Accept: application/vnd.github+json' \
  -H 'Content-Type: application/vnd.android.package-archive' \
  --data-binary @"artifacts/cnb2api-android-debug.apk" \
  "https://uploads.github.com/repos/$OWNER/$REPO/releases/$RELEASE_ID/assets?name=cnb2api-android-debug.apk"
```

## Verify

Check the release and asset state:

```bash
curl -fsS \
  -H "Authorization: Bearer $GITHUB_TOKEN" \
  -H 'Accept: application/vnd.github+json' \
  "https://api.github.com/repos/$OWNER/$REPO/releases/tags/$TAG" \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["html_url"]); [(print(a["name"], a["state"], a["size"])) for a in d["assets"]]'

git status --short
```

The `assets` entry must show `state=uploaded`, and the remote asset size and
SHA-256 should match the local APK. Private release downloads require a
GitHub-authenticated session.

## Known Fixes

- `No locks available`: use the writable `GRADLE_USER_HOME` and
  `--project-cache-dir` paths shown above.
- `aapt2: Exec format error`: use an AAPT2 binary matching the worker CPU via
  `-Pandroid.aapt2FromMavenOverride`.
- `JAVA_HOME is not set`: export `JAVA_HOME` before invoking Gradle; do not
  rely on the system `java` command.
- `StringBuilder cannot be converted to String`: in
  `EmbeddedProxyServer.java`, pass `upstream.content.toString()` where a
  `String` is required.
