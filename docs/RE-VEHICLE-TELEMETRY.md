# RE notes: vehicle telemetry (fuel/RPM/…) is not reachable without a T-Box

> Field notes from mapping whether a **CFMoto 450NK (2027), no T-Box** exposes any live vehicle
> data (fuel, RPM, gear, temps) to the phone. Short answer: **no** — not over BLE, not over PXC, not
> over the dash's Wi-Fi Direct network. Method follows §8 of `01-REVERSE-ENGINEERING.md`: best-effort
> read-only probes shipped in a debug build, run against a live projecting bike with verbose logging.
> Bike-specific identifiers (HUID, SN, VIN, MAC, OTA password) are redacted; only mechanism is shared.

## Why this might interest the project

- It **corrects one line in §6**: the BLE the bike advertises is not (only) "vehicle control
  (lock/telemetry)" — on this unit it's an **audio module**, and it carries no telemetry (details
  below). There's also an **undocumented GATT service** the wake-up path is blind to.
- It closes the "can we read vehicle data?" question with evidence, so nobody re-runs it hoping.

## 1. BLE — an audio module, and an undocumented service

A read-only GATT survey next to the powered-on bike found it advertising as `CFMOTO-LE-XXXXXX`
(RSSI −55) with an **undocumented service** alongside the known wake-up one:

```
service 0000b360-d6d8-c7ec-bdf0-eab1bfc6bcbc
  char  0000b362-...   props = WRITE | WRITE_NR | NOTIFY
Device Information (0x180A):
  Manufacturer = "Feasycom"   Model = "FSC-BT1026C"   FW = V6.4.2
```

- Same vendor suffix (`…-d6d8-c7ec-bdf0-eab1bfc6bcbc`) as the wake-up service `b354`, but a **different
  service** (`b360`). `BleWakeUp` matches the bike by name and then looks only for `b354`, so it never
  sees `b360` — worth noting if anyone extends the BLE path.
- The **FSC-BT1026 is a dual-mode BT audio module** (stereo codec, I2S, transparent-UART bridge). So
  `b362` is a serial bridge to the dash's audio MCU — the hands-free / media-button path — not a
  telemetry channel. Consistent with the bike's `CLIENT_INFO` (`supportBTCall:true`, `btPin`,
  `bluetoothPolicy:2`).
- **Subscribed to the NOTIFY char for two full 60 s windows while revving the engine and toggling
  lights: zero notifications.** The dash pushes nothing on its own here.

## 2. The official app's protocol schemas (from the APK, unencrypted)

`CFMOTO RIDE` is ijiami-packed (DEX encrypted), but the protobuf schemas sit in the clear at the APK
root. `Meter.proto` (the T-Box BLE protocol) is **control-only**: auth, `Display`, `FindCar`
(lights/horn), `KL15`, `Lock`, `Preference`, `Navi`, charging; `PatchObtainInfo` only returns
`CHARGER_INFO`. No fuel / RPM / speed. `bluetooth.proto` *does* carry telemetry (`speed`, `mileage`,
`TransGearPos`, temps, GPS) but with **electric-bike + external-receiver** fields and no fuel field —
and the app gates all of it behind the T-Box (`"not equipped with T-Box, this function cannot be
used"`; `virtual_vehicle.json` carries `simRemainingDays`, i.e. a subscription).

## 3. PXC / projection plane — screen and touch only

With unknown control frames hex-dumped during a live session, the only non-standard frame
(`0x10470` → ack `0x10471`) was the **voice-command grammar** (Chinese regex for music control,
`"cnt":9`). `RVINFO (0x60004110)` is a screen event (`sendRvInfo(ECPAppScreenEvent*)` in
`librvserver.so`), not vehicle info. The AA SENSOR channel only ever starts `DRIVING_STATUS` + `NIGHT`.

## 4. The dash's own network — swept wide, closed

During projection the dash is `192.168.49.1`. Read-only sweep from the phone:

- **Full TCP scan 1–65535: 0 open** (only `10930`, the PXC probe port, and only transiently).
- **UDP probes: no response.**
- **Passive listen (25 s) for any broadcast/multicast the dash emits: nothing** — only the phone's
  own mDNS. The dash advertises **no** mDNS services.

## 5. The OTA SOCKS — the dash asks the phone to be its uplink, but never dials out

During projection the dash sends `SOCK_SERVER_INFO (0x104a0)`:

```
{ "ctrlPort": 11026, "dataPort": 11025, "userName": "carbit_ota_user", "pwd": "<redacted>" }
```

This is **not** a proxy *into* the dash — a connect to `11026` on the dash side is refused. It's the
dash asking **the phone** to run a SOCKS5 server it can dial out through to reach CFMoto's cloud
(OTA), since a T-Box-less dash has no internet of its own. Standing up that SOCKS5 server on the phone
(observe-and-forward, over a cellular uplink) and waiting through a full projection session: **the
dash advertised the proxy but never connected to it.** It seems to only phone home on a schedule /
at boot / when the official app triggers it — not during a normal ride.

## Conclusion

Across BLE, PXC, the dash network, and the OTA path, **no live vehicle data reaches the phone without
a T-Box.** CFMoto gates telemetry behind the T-Box (a paid 4G module) by design. The dash *has* the
data — it renders it — but serves it on none of these surfaces.

The remaining path with the data guaranteed is the **CAN bus** at the diagnostic connector (a
commercial scanner reads live engine/cluster data there). On a Euro5 bike, standard OBD-II PIDs via a
cheap ELM327 are worth trying before raw-CAN sniffing.

*One untried free lever: power-cycle the dash while the phone is projecting with the SOCKS server up —
if its OTA check runs at boot, it would dial out through us then.*

---

*If any of this is useful in the docs, happy to adapt it into `01-REVERSE-ENGINEERING.md` §6/§8
directly — kept it as a standalone note to avoid presuming edits to your canonical file.*
