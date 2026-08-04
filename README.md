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
