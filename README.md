# NMAX Control v2 - Phase 1 (connection lang)

## Paano i-build (GitHub Actions, walang Android Studio)
1. Gumawa ng bagong repo sa GitHub (o i-replace ang laman ng `nmax-control`).
2. I-upload ang LAHAT ng files dito (kasama ang folder na `.github/workflows`).
3. GitHub > tab na **Actions** > "Build APK" > hintayin ang green check (~3-5 min).
   Kung wala pang tumatakbo: **Run workflow**.
4. Buksan ang run > **Artifacts** > i-download ang `nmax-control-apk` (zip) > i-extract > `app-debug.apk`.
5. I-install sa phone (allow "install unknown apps").

## Paano subukan
1. I-off muna ang Y-Connect/SDMV (isang app lang ang puwedeng kumonekta sa bike).
2. Buksan ang app > Scan > i-tap ang `YCCU_...` > hintayin ang status.
3. Pindutin ang **Copy log** at i-paste kay Claude kahit mag-fail.
