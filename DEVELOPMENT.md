# Development notes

Engineering notes for Simple 3DOF TV: what was built, how it fits together, the device quirks that shaped it, and
how to test and debug it. User-facing documentation is in [README.md](README.md).

## Target hardware

- **Pocket TV Pro** (SEI Robotics), Android 14, **32-bit `armeabi-v7a` only**, 3 GB RAM, Mali-G310, adb over
  `<device-ip>:5555`.
- **VITURE Pro 2** glasses over USB-C. The Viture SDK (`SDK\`) ships `armeabi-v7a` `libglasses.so` (+ `libcloud_protocol`,
  `libcarina_vio`); the app talks to it through a small JNI bridge.
- Because the device is 32-bit only, 64-bit apps (SpaceWalker, CRT XR) will not install
  (`INSTALL_FAILED_NO_MATCHING_ABIS`); that is why this is its own app.

## How it was built (stages)

1. **Proof of concept**: pose from the glasses (not the Pocket TV's own sensor) driving a 3D scene.
2. **Floating screen**: a 16:9 curved/flat screen in space playing video (ExoPlayer).
3. **Media player**: library with internal + USB/SD browsing, subtitles, resume, settings without a menu button,
   smoothing/prediction, 120 Hz.
4. **Browser**: WebView on a virtual display rendered to a texture; D-pad and gyro pointer; on-screen keyboard.
5. **Native YouTube** (replacing the browser as the main YouTube route, because the browser was too heavy):
   search, trending, history, then subscriptions, channels, feed, import picker.

## Architecture

### Rendering and UI
- **UI conventions** (from the Phase 1 UI pass; several follow lessons from CRTXR): shared colours and helpers live in
  `Ui`; footers use `Ui.hints(...)` with only the keys that act on the focused row; section titles are
  non-selectable rows (`Library.HEADER`, `YouTubeUi.T_HEADER`) and scrolling keeps the title above the first item
  visible; lists do not wrap at the ends. Text floor is ~30 px (about 0.5 degrees at 56-60 px per degree).
- **Flat screen for menus**: `PlayerUi.wantsFlatScreen()` sets `SceneRenderer.setUiFlat()`; the renderer eases
  `curveEff` toward 0 (menus) or the user's curve (video) over ~0.25 s. CRTXR dropped its curved menu because bent
  text shimmers through the optics.
- **Lost-screen indicator**: `SceneRenderer.drawLostIndicator()` projects the screen centre into view space each frame;
  when it leaves the view (hysteresis back in at 80%) it draws a view-locked arrow and a "Hold OK to recentre" label
  (a small texture made once in `onSurfaceCreated`).
- **Options panel** (`PlayerUi.quickOpen`) crosses into the bottom band, so it is painted into both the main bitmap
  and the band (`paintQuick` is called from `paintAll` and `paintBandLocked`).
- `SceneRenderer` (GLES2, 24-bit depth) draws the room, a flat or curved screen and layers on it: letterboxed video
  (external OES texture), the browser texture (1280x720) and the **overlay**, a 1920x1080 Canvas bitmap with premultiplied
  alpha. A 3D cursor is drawn for the browser.
- All UI (library, HUD, settings, YouTube) is painted by Java into `Overlay` and uploaded as a texture; there are no
  Android views. `PlayerUi.run()` ticks every 100 ms and repaints when a hash of the visible state changes.
- **Bottom band**: the bottom 480 rows of the overlay are a separate bitmap that can be uploaded on its own (HUD,
  subtitles, keyboard) so frequent updates do not hitch the frame. While `Overlay.bandActive` is true the band *replaces*
  that part of the overlay, so a mode must not leave it active unless it paints into it. The YouTube UI only activates
  it while its keyboard is open; leaving it on with nothing painted blanked the lower half of the screen.

### Head tracking
- `GlassesProbe` opens the USB device, hands the file descriptor to the SDK, and runs a thread that blocks in
  `waitPose` (condition variable in `p3dof_bridge.cpp`); there is no polling and the pose path allocates nothing.
- The glasses' pose quaternion is in a GL-style frame (x right, y up, z back), not NWU. World mapping `M = T*R` with
  `T = [[1,0,0],[0,0,-1],[0,1,0]]`.
- Recentre is done in world space (`M = K1 * (R * Rref^T) * K2`). Body-frame recentring tilted and warped the view.
- **Head pose only ever comes from the glasses.** Without them `SceneRenderer.clearOrientation()` gives a fixed,
  level view of the screen (`PlayerUi.watchGlasses` calls `Host.glassesLost()` on disconnect, `recenter()` on
  connect).
- The Pocket TV's own IMU reports a constant event timestamp, so its fusion (Mahony) times samples on arrival.
  The Pocket TV fusion drives only the air mouse (gyro pointer); it never publishes head pose. (It used to be a
  fallback head-tracking source; that was removed on purpose: only the glasses move the view.)
- `PoseFilter`: 1-euro-style adaptive low-pass plus velocity prediction. The 120 Hz display mode is requested when
  available. FOV defaults to 23.5 degrees, screen width 32 degrees, screen distance 5.

### Playback
- `MediaEngine` wraps ExoPlayer (Media3 1.4.1): sidecar subtitles, HLS/DASH, tracks, speed, volume. `ResumeStore`
  keys by URI (`youtube://<id>` for YouTube).
- YouTube playback uses a `MergingMediaSource` of a video-only and an audio-only stream (progressive muxed 360p and
  HLS for live as fallbacks). H.264 is preferred up to the chosen height because the hardware decoder handles it; VP9
  is only used when it gives clearly higher resolution. Requests use `YtDownloader.USER_AGENT`.
- **Large local files** (found 2026-11-06 comparing against the built-in `com.nes.seihplayer`, which is also ExoPlayer
  plus an FFmpeg audio library, so there is no special codec to copy; the video decoders are the same
  `c2.amlogic.*`):
  - `ReadAheadDataSource` serves local files through a 256 KiB read-ahead buffer. `/storage` is FUSE and ExoPlayer's
    MKV parsing issued ~20,000 reads/s of ~260 bytes, which kept the loader at 60% CPU. Akira (2160p x265 10-bit,
    28 GB) now reaches its first frame in ~4 s instead of ~10 s, and seeks resume in ~300 ms.
  - `DefaultLoadControl` is bounded to 30 s / 64 MiB. The default wanted ~130 MB of Java heap for 4K on a 3 GB device
    that is already swapping.
  - **TrueHD / DTS are decoded in-app** (`P3dFfmpeg` + `com.google.android.exoplayer2.ext.ffmpeg.FfmpegAudioDecoder`,
    decoded in the app with FFmpeg built by `tools/build_ffmpeg.sh`; found by comparing against the built-in `com.nes.seihplayer`). Left alone, ExoPlayer picks
    passthrough and the audio HAL decodes it in a real-time `writer` thread at ~50% of a core, which pushed GL frame
    gaps from ~20 ms to 22-68 ms and made head movement stutter. The class name is fixed because the library's JNI
    symbols are named after it. Input buffers must be direct (`BUFFER_REPLACEMENT_MODE_DIRECT`); heap buffers make
    the native code report AVERROR_EOF. If the library will not load the old passthrough path is used.
  - The library loads only from our own APK: the linker namespace refuses `/system/app/.../lib`, and targetSdk 34
    cannot execute libraries copied into app storage.
  - **YouTube, 1080p and above** (2026-11-06):
    - `YtChunkedDataSource` fetches googlevideo streams as 5 MiB `range=a-b` chunks (length from the URL's `clen`).
      One open-ended request per stream made googlevideo send a burst and then nothing: wlan0 receive dropped to
      exactly 0 for ~26 s (our read timeout plus a retry), a rebuffer a few seconds into playback. Chunked, downloads
      run at 50-70 Mbps and the 30 s buffer fills in ~3 s. `--es exp nochunk` restores the old behaviour for A/B.
    - Quality options now go to 1440p and 2160p (`Settings.ytQuality` 0..5; `--ei ytq N` overrides it for a launch
      without saving). The picker takes the highest resolution within the limit and uses H.264 only on a tie in height
      and fps (it used to take H.264 whenever it was over half the height, so 720p H.264 beat 1080p VP9). AV1 is
      skipped. The hardware VP9 decoder plays 4K60 with no dropped frames.
    - **60 fps content loses frames at a vsync-locked 60 Hz loop.** ExoPlayer releases frames up to 50 ms early, so two
      can arrive between GL latches and the SurfaceTexture queue replaces the older one (decoder counters say 60/s
      rendered, the GL thread latched ~53/s; 4K ~47/s). 25 and 30 fps are unaffected. `SceneRenderer.paceLoop` switches
      to swap interval 0 with a ~120 Hz paced loop while 38+ frames/s are arriving (two windows to enter, three to
      leave): 1080p60 now latches 59.9/s, 1440p60 59.4/s, 4K60 ~57/s. `--es exp nofast` disables it.
    - Resolving a video is ~5.5 s cold and ~2.3 s warm (about nine sequential requests plus parsing). `PlayerUi`
      resolves the focused video in the background after 500 ms of dwell (`prefetchTick`, small cache with a 15 min
      TTL); pressing OK uses the finished or in-flight result. Measured OK to first frame ~2.7 s including a resume seek.
  - Diagnostics: `adb shell am start -n com.portable3dof/.MainActivity --es exp perf --es url <file>` then
    `adb logcat -s P3D_PERF` shows decoder names, first-frame time, dropped frames, ExoPlayer decoder counters, bandwidth,
    stream choice, per-request HTTP timings and one line per second of GL frame gaps and frames arrived/latched.
    Extra `exp` switches: `noaudio`, `nopcm` (old passthrough path), `nochunk`, `nofast`.

### Browser
- `BrowserScreen`: `WebView` in a `Presentation` on an app-owned `VirtualDisplay` (1280x720, 160 dpi) drawing into a
  `SurfaceTexture`. Input is synthetic: hover, a press sequence (`DOWN` + button press, 40 ms, release, `UP`), scroll
  and key events through `KeyCharacterMap`. `BrowserController` owns cursor, toolbar, keyboard and gyro pointer.
- **Do not call `WebView.requestFocus()` after clicks**: Chromium focuses the page's first focusable element and steals
  focus from the field that was just clicked. Editable-field detection is a JS focus watcher plus `HitTestResult`;
  the viewport is inset so the keyboard does not cover the field.
- Gyro pointer: yaw about world-up, pitch about the axis selected by `Settings.pointerAxis`.

### YouTube
- **Extraction**: `YouTubeClient` wraps NewPipeExtractor v0.26.5 (GPL-3.0; via JitPack; needs core-library
  desugaring below API 33 and OkHttp). `YtDownloader` is the OkHttp-based `Downloader`; its client is reused for
  thumbnails. Everything blocks on the network and is called from background executors.
- **Search** uses the `all` content filter (videos and channels), skipping shorts and playlists. Paging uses
  `SearchInfo.getMoreItems` for search and `ChannelTabInfo.getMoreItems` for a channel's Videos tab.
- **Channels**: `ChannelInfo.getInfo` -> the tab whose content filters contain `videos` -> `ChannelTabInfo`.
  Items on a channel tab often lack the uploader, so the channel's name/URL are used as fallbacks.
- **Feed**: per channel, `StreamingService.getFeedExtractor(url)` (the RSS feed) with the channel's Videos tab as
  fallback; four parallel workers; 6 items per channel, merged, sorted by upload time, capped at 100; cached.
  One failing channel never stops the feed.
- **Audio choice** (`YouTubeClient.audioScore`): English locale +1000, original track +300 (dubbed +100, descriptive
  -5000), AAC +50, small bitrate tie-break. The chosen track is logged under the tag `YouTubeClient`.
- **UI model** (`YouTubeUi`): a stack of `Frame`s (home, results, feed, channels, channel, import picker). Each frame
  owns its items, paging state and status, so BACK restores a screen exactly. Async results carry the frame they were
  requested for and are dropped if it has been popped (`dead`). Rows are a flat list (`Row` types: search, header,
  item, more, nav) rebuilt from the current frame.
- **Storage** (`SharedPreferences` file `youtube`): `history` (JSON, max 40), `subs` (JSON array of `{n,url,th}`),
  `feed` + `feedTime` (cached feed). Channel identity is the id/handle part of the URL (`Subscriptions.key`), so
  http/https/www variants match.
- **Import** (`Subscriptions.findFiles` / `importFile(file, replace)`): parses Takeout CSV (header-driven columns;
  quoted fields), NewPipe JSON (`subscriptions[]`, service 0 only) or a plain URL list. Replace builds the new list
  from the file and reports added/removed/renamed; merge only adds and renames.

## Device quirks and decisions

- No MENU button: Settings is a library row; hold OK = recentre; OK short-press fires on key-up.
- Storage needs `MANAGE_EXTERNAL_STORAGE`; the TV has a settings screen for it. The "USB / SD" volume seen in testing
  was the device's own SD slot (exFAT, ~1.1 MB/s), which made a 4K MKV unwatchable; other videos play fine.
- Gradle needs a small heap on a PC with a nearly exhausted commit limit: `org.gradle.jvmargs=-Xmx640m
  -XX:+UseSerialGC -XX:MaxMetaspaceSize=256m`.
- Debug builds update in place because Gradle signs them with `.toolchain\debug.keystore` (alias `debug`, password
  `android`) when that file exists; a different key gives `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Release builds are
  signed from `keystore.properties` (git-ignored) and are never signed with the debug key.
- Tooling: very large heredocs failed in the shell, so bigger edits were written with file tools or small patch scripts.

## Name, icons and branding

- The app is called **Simple 3DOF TV** (`res/values/strings.xml`, `app_name`). The internal package / application id is still
  `com.portable3dof` on purpose: changing it would make Android treat the app as a different one and lose existing installs,
  settings, resume positions and YouTube history. APK files are `simple-3dof-tv-<edition>-<buildType>.apk`.
- **Icons are generated, not hand-made.** The two master artworks live in `icons/` (`master-square.png` square,
  `master-banner.png` wide). `python tools/make_icons.py` (Pillow + NumPy) rebuilds everything in `app/src/main/res/`:
  legacy `mipmap-*/ic_launcher(.png|_round.png)` (48-192 px), the adaptive icon (`mipmap-anydpi-v26/ic_launcher.xml`:
  solid `#4B84EA` background colour + a transparent foreground keyed out of the artwork and shrunk into the 66 dp safe zone,
  also used as the Android 13 monochrome layer), and the Android TV banner (`drawable-{hdpi,xhdpi,xxhdpi}/banner.png`,
  16:9 crop of the wide artwork) set as `android:banner`. The generated files are committed so builds need no Python.
  To change the artwork, replace the masters and rerun the script. `icons/icon-512.png` is the store / README size.

## Editions, release builds and developer options

- **Two product flavors** (`edition` dimension). `core` has no YouTube; `youtube` adds NewPipeExtractor (GPL-3.0) and
  OkHttp. Everything YouTube-specific lives in `app/src/youtube/java/` (`YouTubeClient`, `YouTubeUi`, `Subscriptions`,
  `YtDownloader`, `YtChunkedDataSource`, `YtMediaSource`); `app/src/core/java/` holds empty stand-ins for the three
  classes the shared code references (`YouTubeClient`, `YouTubeUi`, `YtMediaSource`). Shared code gates YouTube-only
  UI on `BuildConfig.YOUTUBE`. Keep it that way: no NewPipe import may appear under `src/main`, or the core edition
  would stop being GPL-free (check with `unzip -l` / a dex search for `org/schabi/newpipe`).
- **Why**: VITURE's proprietary libraries can be redistributed only as part of an app, and GPL-3.0 code cannot be combined
  with them in one distributed binary, so the published APK is `core`. See README "Which version do you want?".
- **Developer options are debug-only.** `MainActivity.applyDebugExtras` and `handleLaunchIntent` run only when
  `BuildConfig.DEBUG`; release builds ignore every extra, and `MediaEngine.exp` stays empty so the diagnostics are inert.
  Extras (`adb shell am start -n com.portable3dof/.MainActivity ...`):

  | Extra | Effect |
  |---|---|
  | `--es url <path or uri>` | play it straight away |
  | `--es yt <id or url>` | play a YouTube video (YouTube edition) |
  | `--es browse <url>` | open the browser |
  | `--es exp perf,noaudio,nopcm,nochunk,nofast` | diagnostics (`perf`) and A/B switches, described under Playback |
  | `--ei ytq 0..5` | YouTube quality for this launch (not saved) |
  | `--ef fov 25` | render field of view |
  | `--ez debug true` | orientation markers and crosshair |
  | `--ez eula true` | show the first-launch licence agreement (debug builds otherwise skip it) |

- **FFmpeg audio decoders (TrueHD / MLP / DTS), built from source.** `tools/build_ffmpeg.sh <ndk>` downloads FFmpeg
  n6.0 into `third_party/` (git-ignored) and builds `libavcodec.a`, `libavutil.a` and `libswresample.a` for armeabi-v7a
  with `--disable-gpl --disable-nonfree --disable-everything` plus `--enable-decoder=truehd,mlp,dca` (LGPL 2.1+).
  `app/src/main/cpp/CMakeLists.txt` links them with `ffmpeg_jni.cc` into `libffmpegJNI.so` **only if those libraries exist**,
  so the app still builds (and falls back to passthrough) without them. The JNI source and the three Java classes
  (`app/src/main/java/androidx/media3/decoder/ffmpeg/`) are vendored from AndroidX Media3 1.4.1 (Apache-2.0); they must stay
  in that package because the native symbols are named after it. `P3dFfmpeg.Factory` puts `FfmpegAudioRenderer` ahead of
  the platform audio renderer when `FfmpegLibrary.isAvailable()`, otherwise ExoPlayer would pick passthrough for TrueHD.
  To add a format, add it to `DECODERS` in the script and rebuild (the Java side already maps TrueHD, DTS, AC-3, E-AC-3,
  AAC, ALAC, Vorbis, Opus, FLAC, MP3 and the PCM variants to FFmpeg decoder names).
- **Licence agreement.** `LegalUi` shows `legal/EULA.md` on first launch (Accept / Exit; stored as
  `Settings.eulaAccepted` = `LegalUi.EULA_VERSION`, bump the constant to ask again) and the About and legal pages under
  Settings. The documents are packaged from `legal/` (plus `viture_LICENSE.txt` / `viture_NOTICE.txt`, copied from
  `SDK/android` by the `copyVitureNotices` Gradle task, so they are never committed).

## Testing and debugging recipes

All over adb (`adb.exe` in the platform-tools folder, device `<device-ip>:5555`).

```powershell
adb -s <device-ip>:5555 install -r dist\simple-3dof-tv-youtube-debug.apk
adb -s <device-ip>:5555 shell am start -n com.portable3dof/.MainActivity --es yt aqz-KE-bpKQ   # play a video
adb -s <device-ip>:5555 shell input keyevent 20      # 19 up, 20 down, 21 left, 22 right, 23 OK, 4 back
adb -s <device-ip>:5555 shell input keyevent --longpress 23                                     # recentre
adb -s <device-ip>:5555 shell screencap -p /sdcard/p.png ; adb pull /sdcard/p.png
adb -s <device-ip>:5555 logcat -d -s AndroidRuntime YouTubeClient
```

- Wait ~12 s after launching before sending keys; early keys are lost and the sequence ends up in the wrong screen.
  The head view follows the device, so if a screenshot shows only the room grid, recentre.
- `adb shell run-as com.portable3dof` works, so preferences can be inspected or edited (stop the app first):
  `run-as com.portable3dof cat shared_prefs/youtube.xml`, edit locally, push to `/data/local/tmp`, then
  `run-as com.portable3dof cp /data/local/tmp/youtube.xml shared_prefs/youtube.xml`.
- A test CSV in Downloads that the picker finds will be offered for import; remove stray test files.

## Verified so far (on the device, over adb)

Search with the on-screen keyboard, results with thumbnails, playback and BACK to results; full-height lists without
the keyboard; subscriptions feed with thumbnails and relative dates; channel list; Takeout CSV import (384 channels),
replace and merge accounting; HTML-derived JSON import; English/original audio selection on a multi-language video;
unsubscribed/removed channels persisting across restarts.

## Known gaps and ideas

- **Not tested by hand**: the gyro pointer (axis/speed tuning), head tracking with the glasses in the latest builds,
  HTML5 fullscreen in the browser, Google sign-in in the WebView, long sessions (device memory is tight).
- **YouTube**: relies on scraping (see README limitations); chunked range requests (as NewPipe does) may be needed if
  streams throttle; no related-videos list, playlists, comments, or per-channel notification; channel avatars are only
  loaded for channels reached through search/channel pages.
- **UI, done in Phase 2**: alphabetical search keyboard (`OnScreenKeyboard(true)`) with recent searches (prefs
  `searches`) and NewPipe suggestions (debounced 300 ms); A-Z letter jump in Channels; background avatars
  (`YouTubeUi.requestAvatar`, newest-first queue, two workers, saved via `Subscriptions.setThumb`); shelves
  (`T_TILES` / `T_SHELF` rows in YouTubeUi, `Library.SHELF` on Home; columns kept per row label); `Sounds`
  (AudioTrack blips, played by `PlayerUi.onKey` only when the press visibly changed something).
- **Backgrounds** (`Backdrops`, drawn by `SceneRenderer.drawBackground`): a 40-unit dome (positions only) whose
  colour is computed **per pixel** from the view direction (`FS_SKY` / `FS_PANO`), with
  interleaved-gradient-noise dither of ~1.5/255 (`Backdrops.FS_HEAD`). The first version used per-vertex colours and showed strong banding on the glasses: Gouraud faceting plus
  only a few 8-bit levels in dark gradients. Stars are `GL_POINTS` (`uPt`). No motion in any background (vection).
- The *Ambient glow* background and the dark halo around the screen were removed (2026-11-06): the glow did not work
  well and the halo read as a glitch. Saved settings are migrated in `Settings.load` (old Ambient falls back to
  Night sky; Grid room and 360 photo moved down one index).
- **360 photos**: `Backdrops.findPanoramas` lists 1.85-2.15 aspect images (headers only) in Pictures/Panoramas and
  Download; decoded off the GL thread (`decodePanorama`, <= min(4096, GL_MAX_TEXTURE_SIZE) wide), uploaded in
  `ensurePanorama`, freed when another background is chosen. Mapping in `FS_PANO`: image centre = straight ahead,
  u from `atan(x, y)`, v from `acos(z)`. Check orientation with a labelled test image:
  `python tools/make_test_panorama.py test.jpg` (FRONT/RIGHT/BACK/LEFT), push it to `/sdcard/Pictures/Panoramas/`.
- Shader check without a device: translate to `#version 310 es` (attribute/varying -> in/out, gl_FragColor ->
  out var) and run the NDK's `shader-tools/.../glslc.exe --target-env=opengl -fauto-map-locations`.
- **Edit lists**: Home's `Library.KEY_MANAGE` folder and YouTube's `V_HISTORY` frame; OK arms a row (red, 4 s),
  a second OK removes it. `Library.forget` clears the resume point (`ResumeStore.markFinished`).
- **Shorts**: `YouTubeClient.convert` skips `isShortFormContent()` / `/shorts/` URLs when `Settings.hideShorts`;
  feed items are checked with `YouTubeClient.isShort` (HEAD, no redirects, `SOCS=CAI` consent cookie: without it
  UK/EU requests get a consent redirect). Cache: prefs `shorts` (id -> bool). The feed keeps 160 candidates so 100
  remain after filtering.
- **Lost-screen arrow** samples a 13x7 grid over the screen surface each frame; `Settings.lostWhen` sets how much
  must be out of view (0 = all of it). Not yet checked by eye with the glasses turned away.
- Subscriptions imported earlier stored the avatar as JSON null, read back as the text "null"; `Sub` now treats
  that as no avatar.
- **Possible next steps**: resolve names/avatars for imported channels in the background, a watched marker in the feed,
  playlists, Stage 3 (real Android apps on virtual displays; ROM support unconfirmed), an in-app storage speed test.

## README screenshots

`docs/screenshots/` (and `docs/banner.png`, a resized copy of `icons/master-banner.png`) are device screenshots taken with
`adb exec-out screencap -p`, from the YouTube edition with no glasses attached, playing the CC-BY "Big Buck Bunny".
To retake them: back up `shared_prefs/resume.xml` and `youtube.xml` first (`run-as com.portable3dof`), replace the resume
file with a single Big Buck Bunny entry so Home shows no personal history, capture, then restore the originals. Video
frames are stored as JPEG (quality 86-90), plain UI shots as optimised PNG or JPEG; avoid palette-quantising images that
contain thumbnails (it posterises them).
