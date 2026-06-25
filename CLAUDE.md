# PSV Driver — Project Guide

## What this is
The driver-side Android app for the PSV Tracker system (Barbados public service
vehicle tracking). A driver logs in, signs on to a vehicle + route, and the app
streams GPS pings to the server while in service (with a seat-availability
toggle), then signs off. This is a SEPARATE product from the server
(repo: caritechsolutions/psv-tracker); the two share only an HTTP API contract.

## Stack and conventions
- Native Kotlin, Android. **Views, not Jetpack Compose** (Empty Views Activity
  scaffold). Keep it that way.
- Min SDK 24 (Android 7.0). Package: com.example.psvdriver.
- Minimal dependencies — plain Android plus a lightweight HTTP client (OkHttp is
  fine). Avoid heavy frameworks.
- The repo already contains a working Android Studio scaffold that builds and
  runs on a real device ("Hello World" confirmed on a Samsung over USB). Build
  ON it — do not recreate the project.

## How development works here (different from a server repo)
- Claude Code writes Kotlin and commits to a branch.
- The human builds and runs on a real phone in Android Studio (Run -> USB to a
  Samsung). Claude Code CANNOT deploy to the phone.
- So: keep every change buildable, and after each step state exactly what the
  human should see/do on the phone to verify it.
- Work in small steps; propose the approach before writing code; wait for
  approval; no scope creep beyond the current step.

## The server it talks to
- Base URL is currently a LAN address (http://192.168.110.119) and MUST be
  configurable in the app (a settings field), because it will change (public
  domain / TLS later).
- The server is currently plain HTTP (no TLS yet). Android blocks cleartext HTTP
  by default, so the app must permit cleartext to the server host via a network
  security config (or equivalent) until TLS is in place. Without this, network
  calls fail silently — handle it up front.
- Store the auth token securely (EncryptedSharedPreferences or similar), not in
  plaintext.

## API contract (all POST, JSON; tested and live on the server)
After login, send `Authorization: Bearer <token>` on signon/ping/signoff.

POST /api/driver-login.php
  body: {"username":"...", "password":"..."}
  ok:   {"ok":true, "token":"<token>", "driver":"<name>"}
  bad:  401 {"ok":false, "error":"invalid_credentials"}   (generic; no enumeration)

POST /api/signon.php            (Bearer)
  body: {"vehicle_id":<int>, "route_id":<int>}
  ok:   {"ok":true, "shift_id":<int>, "driver":"<name>"}
  err:  401 missing/invalid token; 422 missing/unknown vehicle or route

POST /api/ping.php              (Bearer)
  body: {"shift_id":<int>, "lat":<float>, "lng":<float>,
         "speed":<float, opt>, "heading":<int 0-359, opt>,
         "seat_status":"available"|"full"|"unknown" (opt),
         "recorded_at":<ISO-8601 or epoch, opt>}
  ok:   201 {"ok":true, "position_id":<int>}
  err:  401; 422 missing/out-of-range lat/lng or missing shift_id; 409 no_open_shift

POST /api/signoff.php           (Bearer)
  body: {"shift_id":<int>}   (optional; if omitted, closes the driver's open shift)
  ok:   {"ok":true, "closed":<int>}

NOTE: there is NOT yet a server endpoint that lists vehicles/routes for the
sign-on screen. That must be added to the psv-tracker server repo before the
sign-on screen can show real choices; until then sign-on can use known test IDs
(vehicle_id 1, route_id 1). Flag this when reaching the sign-on step.

## App scope / build order
1. Server-URL config + driver login (store token securely).   <- first
2. Sign-on screen — pick vehicle + route, POST signon, keep shift_id.
   (needs the server list endpoint noted above.)
3. Foreground location service — runtime location permission, a foreground
   service with the required persistent notification, POST ping every few
   seconds; seat-available toggle; battery-sensible cadence; only while signed
   on.
4. Sign-off — close the shift, stop the service.

## Behaviour / privacy notes
- Background location requires a foreground service + visible notification
  (Android requirement) and a runtime permission flow (fine location).
- Track only while signed on / in service. Stop cleanly on sign-off.
- Use a driver account created in the admin Fleet -> Drivers for testing.
