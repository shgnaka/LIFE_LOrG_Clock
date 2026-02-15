# Org Clock Android Manual Smoke Test

## 1. Build debug APK
```bash
./gradlew clean assembleDebug
```
Expected APK:
- `app/build/outputs/apk/debug/app-debug.apk`

## 2. Install to connected Android device
```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 3. App startup
1. Launch `Org Clock`.
2. Tap `Select org root` and grant access to your `org` directory.
3. Confirm status shows `Root set` or `Root restored`.

## 4. Start clock
1. Input heading path, for example `Work/Project A`.
2. Tap `Start`.
3. Confirm status is `Clock started`.
4. Open today's org file and verify a new open clock line exists under `:LOGBOOK:`.

Expected pattern:
```org
CLOCK: [YYYY-MM-DD Ddd HH:MM:SS]
```

## 5. Stop clock (same day)
1. Tap `Stop` on the same heading.
2. Confirm status includes `Clock stopped`.
3. Verify the open line changed to closed format.

Expected pattern:
```org
CLOCK: [start]--[end] =>  H:MM
```

## 6. Recovery flow
1. Start a clock and force close app before stopping.
2. Reopen app.
3. Confirm `Recovery candidates` appears.
4. Tap `Close recovered clocks now`.
5. Confirm status reports recovered count and open clock list is cleared.

## 7. Midnight split validation
1. Keep an open clock across day boundary (or mock by editing test file).
2. Run stop after midnight.
3. Verify previous day closes at `23:59:59` and current day starts at `00:00:00`.

## 8. Conflict safety validation
1. Start or stop from app.
2. Before save completes, edit same daily file externally.
3. Confirm operation either succeeds after retry or shows explicit failure without file corruption.

## 9. Backup validation
1. Trigger save operations repeatedly.
2. Verify backup files are created in root directory with pattern:
   - `.<daily-filename>.bak.<timestamp>`
3. Confirm old generations are trimmed beyond configured limit.
