# OpenCfMoto 2.0.18 (77)

> **Google updated Android Auto (17.4+). This app still works.** After Connect: AA settings →
> **Version** ~10× (first time) → ⋮ → **Start head unit server**. Repeat the ⋮ step after a reboot
> or an AA update. Do **not** uninstall Android Auto updates.

**Latest.** Slim APK (arm64 + R8). `versionCode` **77** — uninstall first when switching from 2.0.13
or a soak pre.

**Download (64-bit, most phones):**
[OpenCfMoto.apk](https://github.com/zanderp/open-cfmoto/releases/latest/download/OpenCfMoto.apk)

**32-bit ARM** (install says “not compatible” — Galaxy A13 5G / SM-A136B and other 32-bit Android):
[OpenCfMoto.armv7.apk](https://github.com/zanderp/open-cfmoto/releases/latest/download/OpenCfMoto.armv7.apk)

About should read **2.0.18 / 77**.

X-Cape 1200 stills stay on **2.0.14-pre** (Setup → profile **X-Cape 1200**). They are not in this build.

Clock defaults match 2.0.13: empty `0x10451`, echo `0x10600`. Filled query and `timeSync=phone` are
opt-in in Setup → Clock lab — leave them alone unless you are clock-testing.

## Android Auto 17.4+

Google blocked the automatic start. Once per reboot / AA update:

1. OpenCfMoto → **Connect** (let it sit on bike Wi-Fi).
2. Setup → Android Auto → open AA settings.
3. Tap **Version** ~10 times → ⋮ → **Start head unit server**. Leave the notification on.
4. Go back — the dash often paints by itself.

Do **not** uninstall Android Auto updates.

## What’s in this cut

- **Mirror orientation** — Match dash (from 2.0.15-pre)
- **Clock lab** — one Setup card: query / time-sync / Bluetooth listen / stay-on-bike-Wi-Fi. Defaults match 2.0.13
- **P2P keep-alive** (PR #21), **AA bind-defer**, **map lap timer** (2.0.17-pre)
- **Bluetooth remote pad** — pair a gamepad / ring / keypad; HID keys stand in for handlebar gestures
- **Android Auto text size (DPI)** — Auto / 160 / 180 / 240 / 320
- **Connect when a Bluetooth device joins** — optional helmet remote / watch trigger
- **Bulgarian** strings (draft)
- Vehicle-telemetry field notes (`docs/RE-VEHICLE-TELEMETRY.md`)
- Telemetry no longer uploads the AA 17.4 “Start head unit server” banner as an error
- **MapLibre hotfix:** tile HTTP is no longer applied from the cellular `onAvailable` callback
- **32-bit ARM APK** for phones that still run 32-bit Android

Not in this cut: X-Cape 1200 stills, filled `{time,dateTime}` on every channel, Cockpit/Overtake,
speed-based volume.

## Thanks

- **Glifaus** — PR #21 Wi-Fi Direct keep-alive
- **diesersinger** — lap timer idea
- **mgb1982** — clock lab / Zontes HCI
- **sashop2001** — DPI, Bluetooth trigger, Bulgarian
- **sr.chacho** — remote pad idea
- **Authoritt** — PR #29 vehicle telemetry docs
- **Martin Escudero** — Android Auto 17.4 head unit server (already in 2.0.13)
- **joyfulyak** — bind-defer field log
- **ionutradu252** — original handlebar bridge
