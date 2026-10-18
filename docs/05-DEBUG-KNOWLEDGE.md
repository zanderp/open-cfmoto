# OpenCfMoto — Debug Knowledge & Android Auto Black-Screen Fix

Working notes built from reading the whole codebase + bike-test captures. Read alongside
`04-APP-KNOWLEDGE-BASE.md` (which covers protocol/architecture). This file focuses on **runtime
behavior, the actual data flow between components, and the Android Auto black-screen bug**.

> **Note:** the raw `logs/*.log` / `.h264` captures referenced below are diagnostic artifacts and
> are **no longer committed to the repo** (see `.gitignore`). Their decisive findings are quoted
> inline here, so this document stands on its own. Regenerate fresh captures with the **Share Logs**
> button (and `VideoPipeline.DUMP_H264` for stream dumps) when debugging on hardware.

---

## 1. End-to-end runtime flow (what actually happens at runtime)

### Two user modes, one video pipeline + one bike client
- **Mirror mode** (`btn_mirror_start`): MediaProjection → `VideoPipeline` (AUTO_MIRROR VirtualDisplay)
  → H.264 encoder → `EasyConnProber` → bike. **Confirmed working.**
- **Android Auto mode** (`btn_aa_start`): Google AA → `AaReceiver` (loopback :5288) → `VideoDecoder`
  → `AaCompositor` (GL letterbox) → `VideoPipeline` encoder → `EasyConnProber` → bike.

### The AA → bike sequence (the fragile part)
1. Tap **Start Android Auto** → scan bike QR. QR `modelId` selects `BikeProfileHolder.active`
   (drives the AA resolution, which must be fixed before AA starts).
2. `AndroidAutoService` (foreground service) starts: creates `VideoPipeline(compositor=true)` →
   `AaCompositor` (input surface up, **no output canvas yet**) → `AaReceiver` listening on :5288.
   Pipeline published to `AaVideoBridge.pipeline`.
3. `AaSelfMode.trigger()` launches Google AA. On AA 16.4+ the activity is not exported
   (`Permission Denial`) → **broadcast fallback 1** (`WirelessStartupReceiver`). On AA 17.4+ that
   is often ignored too → **broadcast fallback 2**
   (`WifiBluetoothReceiver` / `START_WIRELESS_PROJECTION` with a BT MAC). MainActivity also
   re-triggers once after ~4.5s if AA video never arrives. This is normal, not a hard error.
   On AA 17.4+ all three inbound pokes can fail (`WirelessStartupReceiver` disabled, activity not
   exported). After ~4s `AaReceiver` dials **out** to gearhead's head unit server on
   `127.0.0.1:5277` (every 2s × 20). The rider must tap **Start head unit server** in Android Auto
   developer settings first. Do not tell riders to uninstall AA updates.
4. Google AA connects to :5288 → AAP version + SSL handshake → video channel negotiated → AA H.264
   flows into `VideoDecoder`, which decodes into the compositor's input surface.
5. When decode fps ≥ 25, `AaReceiver` fires `AaVideoBridge.onSteadyVideo` **once**.
6. `onSteadyVideo` (armed by `MainActivity`) → `joinAndStart(qr)` → `BikeWifi.join()` (system dialog)
   → `EasyConnProber.start()`.
7. Bike connects back to ports 10920/10921/10922. On `REQ_CONFIG_CAPTURE` the prober learns the bike
   **canvas** size and calls `VideoPipeline.configureBikeCanvas(w,h)`, which lazily creates the
   encoder at the canvas size and calls `AaCompositor.setOutput(...)` → **now** the compositor
   letterboxes decoded AA frames into the encoder. Frames drain to `frameQueue`.
8. Bike pulls frames via `REQ_RV_DATA_NEXT(114)` → `pollFrame()` → raw Annex-B access units on :10920.

Key ordering fact: the compositor has **no output** until step 7. Before that it just drains decoded
frames (keeps AA alive) and draws nowhere. So AA reaching "steady video" is decoupled from the bike
canvas — good design, but it means **the bike hand-off (steps 5–6) is essential** or the dash stays
black forever even though AA is decoding at 30fps.

---

## 2. THE BLACK-SCREEN BUG (root cause)

**Symptom:** AA mode → phone shows "CFmoto connected wirelessly" (Google AA connected to our
receiver), dash is black. Mirror mode works.

**What the logs show** (`logs/opencfmoto-20260713-194015.log`, `-194222.log`):
- AA connects, handshakes, decodes at 29–31 fps continuously. All good.
- Mid-flow: `stopped` + `Wi-Fi released` + `Ready. tap Start...` appear — these are
  `EasyConnProber.stop()`, `BikeWifi.leave()`, and `MainActivity.onCreate()`. **MainActivity was
  destroyed and recreated** (the process survives — AA keeps decoding).
- `steady video reached — signalling ready for bike hand-off` fires **after** the recreation.
- But **no** `→ joining bike Wi-Fi`, no bike PXC connection, no `COMPOSITOR output set`. The bike
  is never contacted → nothing is ever encoded/streamed → **black dash**.

**Root cause:** the AA→bike hand-off (`onSteadyVideo` + prober) was owned by `MainActivity`.
`MainActivity.onDestroy()` set `AaVideoBridge.onSteadyVideo = null` and called `prober.stop()` /
`BikeWifi.leave()`. Launching Google AA can destroy/recreate the activity **before** steady video
arrives; onDestroy then cancels the pending hand-off, so it never fires.

**Why it regressed (worked before, black now):** the last commit changed the 800MT
(`Cfdl26LandscapeProfile`) AA resolution from **800×480 → 1280×720**. In the 800×480 era
(`logs/...-192828.log`) the hand-off fired *before* the recreation (won the race) and the bike
streamed 540+ real frames successfully. 1280×720 reaches steady-video slightly later, so the
recreation now wins the race and kills the hand-off. It is a **lifecycle race, not a codec problem.**

**Evidence the bike accepts our stream** (from 192828, AA @ 800×480):
- `configured default profile (Main/High)` (Landscape profile has `forceBaseline=false`), encoder
  1280×576, frames #1..#540+ at ~30fps (9–17 KB each = real content), bike heartbeating happily,
  no disconnect. So Main/High @ 1280×576 decodes fine on this dash (mirror mode confirms the same).

### The fix (this branch)
Make the hand-off survive MainActivity recreation:
- **`BikeLink.kt`** (new): process-global holder for the single `EasyConnProber` instance (matches
  the existing `AaVideoBridge` / `ProjectionHolder` / `BikeProfileHolder` singleton style).
- **`MainActivity.onCreate`**: reuse `BikeLink.prober` if present instead of constructing a new one
  (a recreated activity would otherwise orphan the running prober and make Stop hit the wrong object).
- **`onSteadyVideo` closure**: no longer wrapped in `runOnUiThread`; `joinAndStart` now uses
  `applicationContext` + `BikeLink.prober`, so it does not depend on the (possibly dead) activity.
- **`MainActivity.onDestroy`**: guarded — when `AndroidAutoService.isRunning`, it does **not** null
  `onSteadyVideo`, stop the prober, or leave the bike Wi-Fi. The AA→bike chain lives in the FGS +
  process globals and must outlive the activity. Full teardown remains the **Stop** button.

Result: activity can be freely destroyed/recreated during the AA flow; the pending hand-off still
fires and the bike still connects.

---

## 2b. SECOND BUG — bike accepts AA frames but shows black (Main/High profile)

**Confirmed after the hand-off fix** (`logs/opencfmoto-20260713-222541.log`): the full chain now
completes — Wi-Fi joins, PXC handshake runs, `COMPOSITOR output set canvas=1280x576 src=800x480`, and
720+ real frames (3–14 KB, varying = real moving content) stream to the bike, which heartbeats and
never disconnects. **Dash still black.**

Decisive comparison: `logs/opencfmoto-20260713-192509.log` contains a **mirror session and an AA
session against the same bike in the same run**. Their PXC/media exchanges are **byte-for-byte
identical** (same commands, order, `REQ_CONFIG_CAPTURE`→`RLY 17`→`REQ_RV_DATA_START`→`RLY 113`→frame
pulls, same encoder `1280x576 Main/High`). The ONLY difference is the pixel source: MediaProjection
VirtualDisplay (mirror, works) vs the GL `AaCompositor` (AA, black). So the fault is in the AA video
*encoding*, not the protocol.

**Prime suspect:** `Cfdl26LandscapeProfile` overrode `forceBaseline = false` → **Main/High** output,
even though §5 of `04-APP-KNOWLEDGE-BASE.md` calls **Baseline@3.1 "critical for compatibility with the
embedded car/bike decoders."** Embedded decoders commonly accept a Main/High stream (keep pulling
frames) but fail to render it — CABAC entropy coding and/or B-frame reordering, and the bike wire
format carries **no timestamps**, so any reordering is unrecoverable → black.

**Fix applied this branch:**
- `Cfdl26LandscapeProfile`: removed the `forceBaseline=false` override → defaults to **Baseline@3.1**.
  Strict subset, so it cannot regress the working mirror path; `createEncoder` still falls back to
  default profile if a device can't configure Baseline.
- `VideoPipeline` encoder: added `KEY_LATENCY=1` (no B-frame reordering) — also protects the fallback.

Confirm in the next log: `[VIDEO] configured Baseline@3.1` (not "default profile (Main/High)").

**RESULT: Baseline did NOT fix it** (`logs/opencfmoto-20260713-224722.log`, `-224916.log`):
`[VIDEO] configured Baseline@3.1` confirmed, 720+ real frames (5–20 KB, varying) streamed, bike
heartbeating AND sending touch events (`media cmdType=32` = dashboard touchscreen drag,
action `02`=down/`03`=move/`01`=up) — so the dash is fully alive and interactive — yet still black.

That eliminates protocol AND codec profile. The only remaining variable is the actual encoded picture
from the GL `AaCompositor`. Static analysis is exhausted; we need ground truth on the pixels.

## 2c. Diagnostic added: H.264 stream dump

`VideoPipeline` now writes the exact Annex-B access units it sends to the bike into
`<externalFiles>/video/opencfmoto-video-<tag>.h264` (`tag` = `aa` | `mirror` | `own`), bounded to
`DUMP_CAP=600` frames (~20s) so it never stalls the send path. The **Share Log** button now attaches
any `.h264` dumps alongside the log (ACTION_SEND_MULTIPLE). Flag: `VideoPipeline.DUMP_H264` (turn off
for release).

**Plan:** capture BOTH an `aa` dump (black) and a `mirror` dump (works) from the same bike, then diff
them with ffprobe/ffmpeg:
- If the `aa` stream decodes to **real images** in a player → our stream is valid → the fault is
  bike-side rendering of the AA session (much harder; may need a control-plane trigger we're missing).
- If the `aa` stream shows **errors / black** but `mirror` decodes fine → our GL-path encoding is
  subtly broken (fixable) — compare SPS (profile/level/VUI/color), slice types, NAL structure.

## 2d. ROOT CAUSE FOUND & FIXED (CONFIRMED ON HARDWARE) — bike starts mid-GOP on a P-frame (keyframe evicted)

> **Status: RESOLVED.** With `onBikeDataStart()` in place, Android Auto renders correctly on the 800MT
> dash on the real bike. The three bugs, in order of discovery: (1) hand-off race, (2) profile/res
> hardening, (3) this keyframe-eviction bug — which was the actual black screen.

The H.264 dumps (captured via `VideoPipeline.DUMP_H264`; the raw `.h264` files are not kept in the
repo — see `.gitignore`) were decisive. Both AA and mirror streams are **byte-identical
in structure** — same SPS `67 42 00 1f da 01 40 12 69 a8…`, same PPS `68 ce 0d 88`, same keyframe
cadence (every 30 frames), both play fine in a PC player. So the encoding was never the problem.

The giveaway is the FIRST frame the bike actually receives (`sent frame #1` in the log) vs the encoder's
first output (the dump):

| | encoder 1st output (dump) | bike `sent frame #1` (wire) |
|---|---|---|
| mirror (works) | keyframe, 33445 b | **33445 b = the keyframe** ✓ |
| AA (black) | keyframe/IDR, 23040 b | **9722 b = a P-frame** ✗ |

**Cause:** the frame queue holds only 8 frames and drops oldest when full.
- Mirror creates its encoder lazily at `REQ_RV_DATA_START(112)`; the bike opens the data socket ~70 ms
  later and gets the encoder's very first output — the IDR. Decoder initialises → video.
- AA creates its encoder ~**1 s earlier**, at `REQ_CONFIG_CAPTURE` (needed to size the compositor). By
  the time the bike opens the data socket, ~30 frames have been produced and the initial IDR (with
  SPS/PPS) is long evicted. The bike's first frame is a mid-GOP P-frame with no SPS/PPS → the dash
  decoder never initialises and stays **black**, even though it keeps pulling frames. (`sent frame #1`
  = 9722 b ≠ dump frame #1 = 23040 b proves the served stream started mid-GOP.)

This explains everything: identical protocol, identical stream format, valid dumps, mirror works,
AA black (not garbage — the decoder simply never starts).

**Fix (this branch):** `VideoPipeline.onBikeDataStart()` — called from `EasyConnProber` on
`REQ_RV_DATA_START(112)` — flushes the queue, sets `awaitKeyframe` (drain loop drops output until the
next keyframe), and requests an immediate encoder sync frame (`PARAMETER_KEY_REQUEST_SYNC_FRAME`). So
the FIRST access unit the bike pulls is always a full SPS+PPS+IDR, regardless of how long the encoder
has been running. Also hardens mirror and any reconnect. Confirm in the next log: AA `sent frame #1`
is large (keyframe-sized, ~20 KB+) and `[VIDEO] bike attached → flushed queue + requested IDR` appears.

## 2e. Touchscreen input — dash → Android Auto (implemented, needs bike verification)

Goal: drive Android Auto (Maps/Waze) from the CFMoto dash touchscreen. Data flow:

```
dash touch → PXC media cmdType 32 (:10921) → EasyConnProber.handleTouch
   → AaVideoBridge.touchSink → AaCompositor letterbox-inverse map (canvas→AA)
   → AaInput → AAP INPUT channel (chan 3, type 0x8001 InputReport/TouchEvent) → Gearhead
```

**Bike touch frame (PXC media cmdType 32, 18-byte body, little-endian)** — decoded from logs:
`action u16@0 (2=DOWN, 3=MOVE, 1=UP) | x u16@2 | y u32@4 | timestamp u32@8 | (rest unknown)`.
Coordinates are assumed to be in the **bike canvas** we negotiated in REQ_CONFIG_CAPTURE
(`negW`×`negH`, here 1280×576). `handleTouch` normalises the action to 0/1/2.

**Mapping:** `AaCompositor.mapCanvasToSource()` inverts the letterbox (AA content occupies e.g.
960×576 @ (160,0) in the 1280×576 canvas) → AA video space (800×480). Touches in the black bars are
dropped. AA's INPUT service was already advertised as a touchscreen sized to the AA video
(`ServiceDiscoveryResponse`), so Gearhead accepts these coordinates directly (no key binding needed).

**AAP send:** `AaInput.sendTouch()` builds `InputReport{ timestamp(µs), TouchEvent{ Pointer{x,y,id=0},
actionIndex=0, action } }` and sends it as `AapMessage(Channel.ID_INP, EVENT_VALUE=0x8001, …)` via
`AapTransport.send()` (posts to the send thread — safe from the prober's media thread).

**Verify on the bike:** logs show `[:10921] TOUCH DOWN bike=(x,y) canvas=…` and
`[AA] touch action=… bike=(x,y) → AA=(ax,ay)`. Tap a known on-screen AA control and check the mapped
`AA=(…)` lands on it. **If taps are offset/scaled**, the bike's touch coordinate space is NOT the
1280×576 canvas (e.g. it's the physical panel res) — adjust `mapCanvasToSource`'s assumed input space
accordingly (add a bike→canvas pre-scale). Confirm corners: top-left dash ≈ AA (0,0), bottom-right ≈
AA (799,479). Multi-touch/fling not handled yet (single pointer id 0); good enough for tap + scroll.

## 2f. "Crash" on All-Apps / incoming call — bike starved during AA video pause (fixed)

Logs `opencfmoto-20260714-093029.log` (All Apps) and `-093125.log` (call). Not a process crash — a
cascade that drops the whole projection:

1. AA pauses video during a heavy UI transition (opening the app launcher) or a call — it stops
   sending frames for several seconds, then does `Media Sink Stop VIDEO` → `Media Start VIDEO
   session=1`.
2. The AA decoder produces no output → the compositor stops → **the encoder starves** (`[:10920]
   REQ_RV_DATA_NEXT: no frame ready`). The encoder's `KEY_REPEAT_PREVIOUS_FRAME_AFTER` did NOT keep
   it alive once the input surface got zero buffers.
3. The bike's `socketTimeoutPeriodWifi` (=9, from CLIENT_INFO) expires → `media closed / bike closed`
   → projection drops (looks like a crash).
4. Our 3 s decoder-stall watchdog also fired and restarted the decoder mid-transition, fighting AA's
   own stop/start and slowing recovery (fps 1–11).

**Fixes:**
- **Compositor keep-alive** (`AaCompositor`): once a frame has been decoded, re-emit the last frame to
  the encoder at ~15 fps whenever the AA decoder goes quiet (`keepAlive` runnable on the GL thread,
  monotonic presentation time so repeats aren't dropped). The bike now keeps receiving video (a frozen
  last frame) through any AA pause and never times out — a transition/call becomes a brief freeze, not
  a disconnect. This is the primary fix.
- **Smarter stall watchdog** (`VideoDecoder`): only force a decoder restart on a *real* stall — input
  actively flowing (`lastInputMs` < 1 s ago) but no output for 3 s. If no input is arriving (AA merely
  paused), stay idle and let AA resume; don't tear down a healthy decoder.

**Verify:** during All-Apps / a call, expect the dash to freeze briefly then resume, with the bike
staying connected (no `media closed / bike closed`, `hb#… framesSent` keeps climbing). The encoder
should no longer log `no frame ready` during pauses.

## 3. Things to watch next (if the dash is still black after this fix)

Ordered by likelihood, to make the next bike test diagnosable:
1. **Hand-off now fires but Wi-Fi dialog is missed.** After the fix, `BikeWifi.join` shows the system
   "connect to CFMOTOxxxx?" dialog. If the user is on Google AA's screen they must return to accept
   it. Watch for `requesting Wi-Fi join` → `Wi-Fi joined` in the log.
2. **1280×720 vs the panel.** If the dash shows video but wrong/garbled, reconsider the 800MT AA
   resolution. 800×480 is the proven-good value; 1280×720 is unproven end-to-end on the dash.
   Change `Cfdl26LandscapeProfile.aaVideo` back to `LANDSCAPE_800x480` to isolate.
3. **Baseline vs Main/High.** `Cfdl26LandscapeProfile.forceBaseline=false`. Mirror + 192828 both
   worked with Main/High on this dash, so this is unlikely — but if a different dash rejects it, flip
   it to `true` (the docs call Baseline@3.1 the safe value).
4. **Compositor output.** Confirm `[COMPOSITOR] output set canvas=... src=...` appears and frame
   sizes on :10920 are non-trivial (KB, not tens of bytes). Tiny frames ⇒ compositor is emitting
   black (texture/letterbox bug); large frames ⇒ real content is reaching the bike.

---

## 4. Component map (quick reference)

| File | Role | Lifecycle owner |
| :--- | :--- | :--- |
| `MainActivity` | UI, QR scan, permissions, **arms** the hand-off | Activity (may be recreated!) |
| `AndroidAutoService` | FGS: hosts `VideoPipeline`(compositor) + `AaReceiver`; **survives bg/lock** | Service |
| `AaReceiver` / `AapTransport` / `VideoDecoder` | loopback AAP receiver + H.264 decode | inside FGS |
| `AaCompositor` | GL letterbox: AA source → bike canvas | inside `VideoPipeline` |
| `VideoPipeline` | H.264 encoder (+ mirror/own-content sources) | FGS (AA) or Activity (mirror) |
| `EasyConnProber` / `PxcHandshake` | bike PXC client (phone = server) | **process-global via `BikeLink`** (this fix) |
| `BikeWifi` | joins bike AP, process-binds the network | singleton object |
| `AaVideoBridge` | shares the encoder pipeline + `onSteadyVideo` hand-off signal | process-global |
| `BikeProfileHolder` | active bike profile (AA resolution, caps) | process-global |

Global singletons that carry state across the Activity's lifecycle: `AaVideoBridge`,
`ProjectionHolder`, `BikeProfileHolder`, and now **`BikeLink`**. Anything the AA→bike chain needs
must live in one of these (or the FGS), never solely in `MainActivity`.

---

## 4b. Bluetooth remote — Android key routing (verified on a Moto Edge 50 Neo, Android 16)

Findings behind `RemoteKeyService`, `MediaKeyForwarder` and remote-only mode in `MediaButtonBridge`:

- **Accessibility key filtering only sees keys while the screen is on.** With the screen off, a
  remote's media keys skip the filter and go straight to the *media-button session*; its
  VOLUME / HOME keys go to the system (volume change / nothing). POWER is never filterable.
- **Media-button session = the session of the app that most recently *started* a player**, even
  if its session is inactive. OpenCfMoto plays AA audio, so it often wins by accident (and used to
  swallow helmet keys in media mode). A music app wins again on every track change / resume.
  Remote-only mode restarts its silent track **new-before-old** on every playback change and each
  keep-alive; stopping first leaves a ~25 ms gap where the music app is newest.
- **Holding `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` ducks other players to 20 %** (VolumeShaper
  `[1.0, 0.2]`). Handlebar-capture mode still does this; remote-only takes no focus.
- **MediaSessionService flattens a held PLAY_PAUSE into a ~1 ms tap** before the session sees it.
  The accessibility filter sees the real down/up, so holds only work with the screen on.
- **Device identity:** a HID remote has a real external `InputDevice`; Cardo / bike AVRCP keys
  arrive with `deviceId=-1` (virtual). `RemotePad.isExternalPad` also rejects the phone's built-in
  keys (`!isExternal`), so phone volume buttons are never treated as a remote.
- **Cheap "SmartRemote" chips do gesture detection themselves:** tap → NEXT / PREVIOUS / PLAY_PAUSE;
  hold ▶▶ / ◀◀ → VOLUME_DOWN / UP every ~300 ms (folded into one press via `HOLD_STREAM_GAP_MS`);
  double-tap ▶▶ → HOME, ◀◀ → POWER, ⏯ → nothing.
- **Helmets (Cardo) decide PLAY vs PAUSE from what the media-button owner reports *and* whether
  audio is streaming.** While OpenCfMoto said PLAYING, or its silent track kept the Bluetooth stream
  open, a Cardo re-sent PAUSE every 2 s and its Play never got through. Remote-only mode mirrors the
  music app's state and stops the silent track while music is paused; it stays media-button owner.
- **Notification access is only needed *granted*:** `getActiveSessions` works after the listener
  calls `requestUnbind()`, so the pass-through receives no notifications at all.
- **Dialogs have their own window:** an activity's `dispatchKeyEvent` never sees keys while a
  dialog is up — the teach dialog forwards via `setOnKeyListener`.
- Tools: `adb shell getevent -lt /dev/input/eventN` (raw remote keys), `adb logcat -s
  MediaSessionService:D` (who gets each media key, and media-button session changes).

---

## 5. This bike (from the QR in the logs)
`ssid=CFMOTO1565 modelId=37426 sn=0rLs action=1 (ap=true, p2p=false)` → matches
`Cfdl26LandscapeProfile` ("CFDL26 / MotoPlay Landscape (800MT)"). Bike gateway `192.168.0.1`;
negotiated canvas seen in logs = **1280×576**.
