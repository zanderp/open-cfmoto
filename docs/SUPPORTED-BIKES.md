# Supported bikes

OpenCfMoto talks to dashes that show a **MotoPlay / EasyConnect pairing QR** — the Carbit software
framework from Wuhan CARBIT Information Co., Ltd. (also seen as Yi Lian / EasyConn). CFMoto is the
best-tested brand; several other manufacturers license the same stack.

**You do not need a T‑BOX.** T‑BOX is for the official CFMOTO RIDE cloud / subscription features.
OpenCfMoto only needs the dash Wi‑Fi (or Wi‑Fi Direct) QR — **US and international** markets both work.

**Quick test:** on the bike, open the phone-connection / MotoPlay / EasyConnect screen. If you see a
QR code, OpenCfMoto can try to connect. No QR → this app cannot join that dash.

Stock Carbit Ride / brand companion apps (MotoFun, etc.) are separate. OpenCfMoto uses the same QR
path to project **wireless Android Auto** (not Apple CarPlay).

**Android Auto 17.4+:** OpenCfMoto still works. Google blocked the automatic start — after Connect,
open AA settings → tap **Version** ~10× (first time) → ⋮ → **Start head unit server**. Repeat the
⋮ step after a reboot or an AA update. Do not uninstall Android Auto updates. Full steps:
[README — Android Auto setup](../README.md#3-android-auto-setup).

Community reports welcome in [Discord](https://discord.gg/KNTjJhmFZ6) so we can keep this list current.

### Brands (supported & welcome)

| | |
| --- | --- |
| **Confirmed** | **CFMoto** · **Voge** · **Zontes** · **Moto Morini** · **Morbidelli** · **QJ Motor** · **Benelli** · **Rieju** · **GOES** / **Gladiator** (CFORCE rebadges) · **UM** (DSR 250 Rally) |
| **Same Carbit / EasyConnect path — try Connect** | **Longjia** · other TFT dashes with a pairing QR |
| **Experimental (SoftAP join only)** | **Kove** (Thinkerride SoftAP — phone AA may start; dash video not yet) |

**📸 Dash showcase:** **[SHOWCASE.md](SHOWCASE.md)** — curated Android Auto photos from the community
(one hero shot per confirmed model). Add yours in Discord `#confirmed-working`.

---

## Confirmed with OpenCfMoto

Riders have projected Android Auto with these (US + international as noted):

### CFMoto

| Model | Notes |
| --- | --- |
| **800MT** (Explore / Explore GT) | Landscape touch (CFDL26) |
| **800MT‑X** / **1000MT‑X** | Portrait CFDL26; handlebar-primary by default |
| **800NK** (US CRCP / sdk 0.9.23.x) | Non‑touch; dual PXC heartbeat |
| **800NK Advanced** | Near-square touch (~720×712); use Screen margins for the MotoPlay pull-down |
| **450SR** (+ SR‑S / TC class) | Non‑touch CFDL16; handlebar + on-screen pad |
| **675SR‑R** | Community-confirmed (2026-08) — SoftAP / EasyConnect QR |
| **450CL‑C** / **CL‑C450** | Often Wi‑Fi Direct (P2P) — Setup → Wi‑Fi **Auto** or **P2P**; Discord `#450cl-c` |
| **150SC** (scooter) | Community-confirmed; same QR / EasyConnect path |
| **450NK** | Community-confirmed — works without T‑Box / MotoPlay subscription |
| **675NK** | Community-confirmed — SoftAP QR; handlebar→AA needs bike Bluetooth |
| **700MT Adventure** | Community-confirmed — showcase in Discord `#confirmed-working` |
| **450MT** | Community-confirmed — SoftAP / EasyConnect QR |
| **Ibex 800** (US) | Community-confirmed — US 800MT-class EasyConnect dash |
| **CFORCE 850 / 1000** (ATV TFT) | Community-confirmed (incl. Touring Pro reports on 2.0.7) |
| **GOES Terrox 1000** / **Gladiator G3 1000** | Community-confirmed — CFORCE 1000 rebadges; same EasyConnect QR path; **1280×720** often looks clean; Terrox 1000 Pro (2026) showcased |

### Other brands (community-confirmed)

| Model | Notes |
| --- | --- |
| **Voge DS800 Rally** | Community-confirmed — Carbit / EasyConnect QR |
| **Voge DS900X** / **900 DSX** | Community-confirmed (2026-08) — Apple QR / Carbit path; Discord `#ds900x` |
| **Moto Morini X-Cape 649** | Community-confirmed (also styled Xcape 649) |
| **Moto Morini X-Cape 700** | Community-confirmed |
| **Moto Morini Seiemmezzo** | Community-confirmed (incl. 2026) — MotoFun / EasyConnect QR |
| **Moto Morini X-Cape 1200** | SoftAP / Yunmo joins; **TFT paint experimental** (2.0.7 vc50+ map-nav/split IDR) — Discord `#xcape-1200` |
| **Benelli TRK 702 / 702X** | Manual SSID/pwd or QR when shown; grant Nearby devices / Bluetooth for AA |
| **Rieju 307** | Community-confirmed (2026-08) — Connect + AA on dash; try Fit/Stretch if letterboxed — Discord `#rieju-307` |
| **Zontes 125X** | Community-confirmed (2026-08) — Carbit / EasyConnect QR |
| **Zontes** (other TFT + pairing QR) | Same Carbit / EasyConnect path — try Connect; report logs in Discord |
| **Morbidelli T1002VX** | Community-confirmed (Argentina) — Carbit / EasyConnect QR |
| **Morbidelli T352X** | Community-confirmed (2026-08) — AA split on Carbit dash — Discord `#t352x` |
| **UM DSR 250 Rally** (2026) | Community-confirmed (Nicaragua, 2026-08) — Carbit / EasyConnect QR |
| **QJ Motor SRK800RR** (2025) | Community-confirmed — iOS QR; AA + media OK; handlebar→AA needs bike Bluetooth + Controls ON |
| **QJ Motor SRK250RD** (2026) | Community-confirmed — AA works; dash uses half-screen layout (unlike 800RR); buttons untested |
| **QJ Motor SRK450RR** (2026) | Community-confirmed — AA works; dash uses half-screen layout (unlike 800RR); buttons untested |
| **QJ Motor SRT 600** / **SRV600** | Community-confirmed (2026-08) — AA on dash (HW `SS655-L7` class); tune margins/fit for rounded panels |
| **QJ Motor 600SX / 550SX** (2026) | In progress — QR works (`qj-5G-*`, modelId 37501); use Setup → Wi‑Fi **AP** if Auto mis-picks P2P; clock reset → retest **2.0.10** (echo bike stamp) |

---

## Other brands (Carbit / EasyConnect)

These brands commonly ship TFT dashes that license the same Carbit EasyConnect-style pairing
(QR → bike Wi‑Fi → projection). **If your unit shows a pairing QR, try Connect.** Unknown model IDs
fall back to the Legacy profile. Please report success or failure (with a log) in Discord so we can
promote models to “confirmed.”

| Brand | Notes / examples |
| --- | --- |
| **Voge** | **DS800 Rally** + **DS900X** confirmed; other EasyConnect TFT models welcome |
| **Zontes** | **125X confirmed**; other TFT dashes with pairing QR welcome |
| **Moto Morini** | **X-Cape 649 / 700** and **Seiemmezzo** (incl. 2026) confirmed; **1200** SoftAP joins (keep MotoFun/pairing QR open — EasyConn may not sit on `:10930`). Pairing QR may be `admin.motomorini.com/…?Wifi=SSID#password#mac&MachineID=…&ProductID=…` — supported. Do **not** scan the vehicle info QR (`code:…color:…`). Dash clock jumping hours / **1970** after connect: **2.0.10** echoes the bike stamp unless it is epoch (log `HU_TIME_SYNC … mode=echo|phone`). |
| **Benelli** | TRK 702 / 702X class — SoftAP SSID/password or QR when shown; grant **Nearby devices / Bluetooth** |
| **Rieju** | **307 confirmed**; other EasyConnect TFTs welcome (`#rieju-307`) |
| **QJ Motor** | **SRK800RR 2025**, **SRK250RD / SRK450RR 2026**, **SRT/SRV 600** confirmed; **600SX / 550SX (2026) testing**; Fort 4.0 and other EasyConnect TFTs |
| **Morbidelli** (formerly MBP) | **T1002VX** + **T352X** confirmed; other Carbit dashes welcome |
| **Longjia** | e.g. **V-Bob 650** — Europe often uses **MotoFUN** / **Carbit Ride**. Unconfirmed; try Connect (or Mirror) if the dash shows a pairing QR. Close the official companion app first so it does not hold the link ports. |
| **UM** | **DSR 250 Rally 2026** confirmed (Nicaragua) — same Carbit / EasyConnect QR path |

### Experimental — not Carbit (Thinkerride)

| Brand / model | Notes |
| --- | --- |
| **Kove** (e.g. 800X Pro, 450RR SoftAP) | **Thinkerride SoftAP**, not EasyConn/Yunmo. Pairing QR looks like `http://g.thinkerride.com?<SSID>&<PWD>&ap=1` (SSID often `CQKY_*`). OpenCfMoto can join SoftAP and start AA on the phone; **TFT paint is not supported yet**. Skip “download KOVE APP” QRs — use the SoftAP screen (SSID+password) or Garage → enter creds. After Connect fails EasyConn/Yunmo, Share Logs and look for `[DISC] thinkerride-scan`. |

---

## Full list — known CFMoto MotoPlay / EasyConnect dashes

If your model appears below **or** shows a pairing QR, try OpenCfMoto. Trim / year / region variants
(e.g. “Sport”, “TC”, “Explore GT”) usually share the same dash protocol when the QR is present.

### Naked (NK)

- 125NK
- 450NK
- 675NK
- 800NK
- 800NK Advanced
- 800NK Sport
- 800NK (US CRCP)

### Sport (SR)

- 300SR
- 450SR
- 450SR‑S
- 450SR TC
- 500SR VOOM
- 675SR
- 675SR‑R (community-confirmed)

### Touring / Adventure (MT)

- 450MT
- 700MT
- 700MT Adventure
- 800MT‑X
- 800MT Explore
- 800MT Explore GT
- 1000 MT‑X

### Cruiser (CL)

- 450CL‑C
- CL‑C450

### Scooter

- 150SC

### ATV / SSV (TFT dash)

- CFORCE 800 (TFT, typically 2024+)
- CFORCE 850 Touring
- CFORCE 1000 (TFT, typically 2024+)
- CFORCE 1000 Touring
- GOES Terrox 1000 / Gladiator G3 1000 (CFORCE 1000 rebadges)

### Other / regional

- U10 Pro (where the dash offers MotoPlay / EasyConnect QR)

---

## Usually no EasyConnect QR (won’t work with OpenCfMoto)

These are commonly listed **without** a MotoPlay phone-projection QR. If your unit somehow has the
QR anyway, try it and tell us.

- 800MT Sport
- 800MT Touring
- 450SR World Champion Edition
- 700CL‑X (Adventure / Heritage / Sport)
- PAPIO (and similar mini bikes without the EasyConnect projection screen)

---

## How to use this list in the app

Setup → **Supported bikes** shows the same list. Profiles you can force in Setup:

**Auto** · **Legacy** (CFDL16) · **800NK** · **800MT** · **1000 MT‑X** · **800NK Adv** · **CL‑C450**

Touch dashes → use the screen (and **Dash view**). Non‑touch / focus-mode → **Controls** + Bluetooth
handlebars.
