# Smart Watering Android App

The app uses the authenticated public `/api/v2` API. It supports device and
watering status, watering history, and a device-control screen for:

- configuration (`name`, API-provided device type, dry weight, and tare);
- sleep mode and sleep interval;
- scale zero and calibration;
- viewing and clearing queued commands.

The device-type selector is populated from `GET /api/v2/device-types`.
Device-control screens refresh every three seconds. Current controller values
remain in the inputs while queued values are shown separately until their
operations are applied.

## API Environment Per Build Type

The app reads public API settings per Android build type, so debug and release builds can use different servers without editing files between builds.

Create local files from the templates:

```powershell
Copy-Item app\.env.debug.example app\.env.debug
Copy-Item app\.env.release.example app\.env.release
```

Set the URL for each build:

```dotenv
SMART_WATERING_PUBLIC_API_BASE_URL=http://10.0.2.2:8081/
SMART_WATERING_GOOGLE_WEB_CLIENT_ID=
```

Use the public API server root URL. Do not include `/api/v2` in new env files; the Retrofit service paths already include it. For compatibility with older local env values, the app strips a trailing `/api/v1` or `/api/v2` from `SMART_WATERING_PUBLIC_API_BASE_URL`.

`app/.env.debug` is used by debug builds. `app/.env.release` is used by release builds. Both files are ignored by git.

Resolution order for each build type:

1. `local.properties` with build-type keys, for example `SMART_WATERING_PUBLIC_API_BASE_URL_DEBUG`
2. OS environment variables with build-type keys
3. generic keys in `app/.env.debug` or `app/.env.release`
4. built-in safe placeholder `https://api.example.com/`

Supported keys:

```text
SMART_WATERING_PUBLIC_API_BASE_URL
SMART_WATERING_PUBLIC_API_URL
SMART_WATERING_GOOGLE_WEB_CLIENT_ID
```

Build-type-specific override suffixes are also supported:

```text
SMART_WATERING_PUBLIC_API_BASE_URL_DEBUG
SMART_WATERING_PUBLIC_API_BASE_URL_RELEASE
SMART_WATERING_GOOGLE_WEB_CLIENT_ID_DEBUG
SMART_WATERING_GOOGLE_WEB_CLIENT_ID_RELEASE
```

Build commands:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

## Automatic Release Publishing

The app checks `GET /api/v2/app/latest` on startup and offers to download a newer APK
when the published `version_code` is greater than the installed one.

`.github/workflows/android-release.yml` runs on a self-hosted Linux x64 runner. Pushes
and manual runs build the debug APK as a CI check. A manual run publishes a signed
release only when its `publish` input is enabled; the input defaults to `false`.
The runner, Smart Watering backend and Prometheus may live on the same machine. When
enabled, the runner publishes directly to a host directory mounted read-only by
`public-api`, so publishing does not restart the backend and does not affect Prometheus.

Configure the GitHub environment named `dev` with these variables:

- `PUBLIC_API_BASE_URL` — public backend root URL, without `/api/v2`;
- `RELEASES_DIR` — shared host directory, for example `/srv/smart-watering/releases`;
- `SIGNING_DIR` — runner-only signing directory, for example
  `/var/lib/smart-watering-builder/signing`.

Configure the environment secret `GOOGLE_WEB_CLIENT_ID`. On the runner create
`$SIGNING_DIR/release.env` with mode `600`:

```dotenv
SIGNING_STORE_PASSWORD=<secret>
SIGNING_KEY_PASSWORD=<secret>
```

Store the keystore at `$SIGNING_DIR/release.jks` with mode `600`. It must use the
alias `release`. The runner account needs read access to both signing files and write
access to `RELEASES_DIR`. Configure the backend's `docker/.env` with the identical
host path:

```dotenv
SMART_WATERING_ANDROID_RELEASES_DIR=/srv/smart-watering/releases
```

Create the first version base, or a later major/minor base, from this Android
repository:

```bash
bash scripts/create-release-tag.sh 1.0.0
git push origin app-v1.0.0
```

The tag defines the visible version base. Later successful builds from `master`
increment the patch stored in `latest.json`; a newer tag changes the base. Android
`versionCode` is `1000 + github.run_number` and is checked for monotonic growth.
Publishing jobs are serialized and update `latest.json` atomically. Pushes, including
tag pushes, never publish by themselves; start the workflow manually with `publish`
enabled to publish the version resolved from the checked-out commit.
