# Hunter & Prey – native Android app

Same UI, same Firebase + Supabase data as the web app (bundled locally in `app/src/main/assets/`),
wrapped in a native shell with:

* Saved PIN (encrypted) – no typing after the first login
* Always-on listener service → message notifications with **Reply box**, *Mark as read*, previews, sounds, vibration, quiet hours
* Incoming calls ring like a phone call (your ringtone + vibration) with a Decline/Answer notification and a lock-screen card – even when the app is closed
* Settings: web app Settings → **Notifications & calls**

## Build
Push to GitHub → Actions → "Build APK" → download `HunterPrey-apk` (or the `latest` release).
Use a **private repo**: the signing key is in `app/hunterprey.jks` so every build updates the previous install.

## Update the web UI
Edit `app/src/main/assets/index.html` (keep the `./native.js` and `./vendor/...` lines).
