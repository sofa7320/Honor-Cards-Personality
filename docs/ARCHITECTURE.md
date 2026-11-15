# 卡面工坊 — Module Architecture & Source Map (module-dev)

LSPosed module for 荣耀钱包 (com.hihonor.wallet 11.0.3.305). Display-layer card-face replacement.
All code paths are fail-open: any exception is caught + logged, the wallet keeps its original face.

## 1. Packaging / build

| Item | Value |
|---|---|
| Gradle | 8.13 (`scripts/build.ps1`, exclusive lock `work/.build.lock`, env JAVA_HOME/D:env/jdk-17.0.12, ANDROID_HOME/D:env/android-sdk, GRADLE_USER_HOME/D:env/gradle-home) |
| AGP / Kotlin | 8.11.2 / 2.3.20 (+ compose plugin 2.3.20) |
| compileSdk / targetSdk / minSdk | 37 / 36 / 28 (Miuix 0.9.0 AAR metadata requires compileSdk 37) |
| UI | Miuix 0.9.0 (`miuix-ui`, `miuix-icons`, `miuix-preference`), Compose AndroidX 1.10.5 |
| Xposed API | **legacy Xposed API, compileOnly `:xposed-stub`** (never packaged; verified 0 `de/robv` class descriptors in APK) |
| Module entry | `app/src/main/assets/xposed_init` = `com.cardpersonality.honor.hook.XposedEntry` |
| Manifest meta-data | `xposedmodule`, `xposeddescription`, `xposedminversion=93`, `xposedsharedprefs` |

Artifacts: `work/module/poc/card-personality-v10-final.apk`.

## 2. Source map (module-dev owned files)

```
xposed-stub/                                   # compileOnly legacy Xposed API stubs (java-library + android.jar)
app/src/main/assets/xposed_init                # entry class pointer
app/src/main/AndroidManifest.xml               # module meta-data, MainActivity, exported FaceProvider
app/src/main/java/com/cardpersonality/honor/
  MainActivity.kt                              # launcher; setContent { ui.CardPersonalityApp() }
  FaceProvider.kt                              # read-only ContentProvider (see §4)
  hook/XposedEntry.kt                          # IXposedHookLoadPackage entry + hook helpers
  hook/FaceHookEngine.kt                       # ALL wallet hooks (H1..H14) + refresh + reporting
  hook/ProviderClient.kt                       # wallet-side provider client: 5s TTL config, URI builder, report
  hook/CardIdentity.kt                         # reflection identity chains per card type (§13)
  hook/FaceImageLoader.kt                      # background decode of provider JPEG + bitmap cache + 12dp rounding
  hook/FaceEnforcer.kt                         # weak view registry + OnPreDraw re-pin of our bitmap
  hook/HLog.kt                                 # logcat + XposedBridge log with rate limiting
# ui-dev owned, consumed: data/CardModels.kt, data/FaceRepository.kt, FaceRenderer.kt, ui/**
```

## 3. Hook list (FaceHookEngine, all fail-open)

| Id | Target | Mode | Purpose |
|---|---|---|---|
| H1 | `nn0#p(ImageView,String,Object,mh5)` | pre: record only | ThreadLocal current card, source→identity map, report card. Never skips/modifies. |
| H2 | `nh5#c(ImageView,Context,Object,int,Drawable,int,int,boolean,boolean,e11,boolean,boolean,vw7,boolean)` | **skip + re-invoke** | Primary replacement point: when source maps to a configured card, args[2] := our `content://…/card/<sha256>.jpg?v=N` Uri (other args unchanged, ThreadLocal re-entry guard). Covers home/list/detail/notification. |
| H3 | `mv1#a(Object)` | after | Returns `android.net.Uri` for configured cards → wallet preload (`qc9`→`hv1.c` key `q0(card)+source`) caches OUR bitmap, so `nn0#p`'s synchronous cache-hit path shows the custom face. |
| H3b | `hv1#a(String)` | pre (time window) | After a config change (<15 s), force a cache miss for keys containing our authority so stale preload cannot win. |
| Face getters | `TransitCard#getFaceImage`, `CampusCard#getCardFaceImage`, `BankCard#getCardPic`, `BleDkBean#getImageSelected`, `AccessCard#getCardPicUrl` | after | Diagnostic + URL→configured-face registry (lyra/list/detail pages pass these URLs straight to nh5#c). |
| H4 | `mv1#c(ImageView,Object)` | after | Card-list / thumbnail loader: register view + apply configured face. |
| S3 | `to6#B(yn0,Object)` | after | 卡包/我的卡片 rows: identity → afterBind (face + element hide). |
| S1 | `z79#d(holder,int,boolean,boolean)` / `z79#f(CopyOnWriteArrayList)` | after | Swipe adapter bind/data-set: registry + card report (surface absent on current wallet UI but supported). |
| S2 | `la8#a(Context,Object,View,int)` / `ia8#setNewData(List)` | after | Home-card entry (lyra page) enforcement + list report. |
| H5 | `xi#j(ImageView,AccessCard,Context,Integer,boolean,e11,int)` | after | Access-card face (incl. classic bundled faceId<1000). Skips style-preview Activity. |
| H6 | `pi3#c(ImageView,EBikeKeyBean,Context,boolean,e11,boolean,int)` | after | eBike card face. |
| H7-H12 | `v8a#l`, `ml1#l`, `hp1#l`, `ff0#u`, `rc3#u`, `jg#u` | after | Detail page adapters (transit/campus/carkey/bank/ebike/access). Identity from adapter card list @ position / tag. |
| H13/H14 | `SwipeCardStackView#setAdapter`, `HwViewPager#setAdapter` | after | Capture containers for refresh. |
| Guard | `android.widget.ImageView#setImageDrawable(Drawable)` | pre | For registered views, redirect to our BitmapDrawable (last-resort enforcement). |
| Refresh | `CONFIG_CHANGED` broadcast receiver (RECEIVER_EXPORTED) + ContentObserver on `content://…/config` | — | Invalidate bitmap cache, force config reload, clear `hv1.c`/`mv1.c`, `SwipeCardStackView#m()`, `pager.setAdapter(adapter)`, reload registered views. |

Element KEEP/HIDE (requirement 3): after every bind, ids in `hiddenAppElements` (∩ whitelist) are set `View.GONE`;
functional views (payment QR, ride code, location button, lock tips) are never touched. Default KEEP.

Transit-detail geometry: that layout has no clipping → 12dp corner radius is baked into the bitmap for `iv_traffic_card_bg`.

## 4. Provider contract (`FaceProvider`, authority `com.cardpersonality.honor.face`, exported, read-only)

```
content://com.cardpersonality.honor.face/card/<sha256hex(fileKey)>.jpg?v=<configVersion>  -> JPEG 1008x624
content://com.cardpersonality.honor.face/card/<type>/<urlEncodedIdentity>?v=..            -> same file via FaceRepository
content://com.cardpersonality.honor.face/original/<type>/<urlEncodedIdentity>             -> hook-captured wallet face
ContentResolver.call():
  "config"         -> Bundle{json = <filesDir>/config.json, version = N}      (hook caches 5 s)
  "getFaceConfig"  -> (type,id) {json, version, hasCustomFace}
  "getAllConfigs"  -> {json, version}
  "report"         -> (json = JSON array of CardInfo) {ok, version}           (hook -> app card report)
  "getReport"      -> {json = <filesDir>/report.json}
  "saveOriginalFace" -> (type, id, data: ByteArray JPEG) {ok, skipped}        (round 2: current-face preview)
  "originalExists" -> (type, id) {exists}
  "ping"           -> {pong, version}
```
- Round 2 #1 "current face": hook reports `CardInfo.faceUrl` (transit getFaceImage / bank getCardPic /
  campus getCardFaceImage / carkey getImageSelected / eBike getCardPicUrl) in `call("report")`; for cards
  without a URL (classic access-card bundled drawable) the hook snapshots the already-drawn ImageView face
  after frame draw (long side <= 640, JPEG q85, <= 512 KB, once per card per wallet process, only when no
  custom config) and pushes it via `call("saveOriginalFace")`; provider stores
  `<filesDir>/originals/<sha256hex(fileKey)>.jpg` (type whitelist, idempotent) and serves it through the
  `original/` URI. Panel uses this for Home row thumbnails + editor "current face" base.
- `fileKey` = `"<type>:<sanitizedIdentity>"`, type defaults = `"type:<type>"` (ui-dev's `CardKey`/`FaceRepository`).
- Exports are rendered by the UI at save time (`<filesDir>/exports/<sha256hex(fileKey)>.jpg`); the provider only streams files.
- If our APK is uninstalled/disabled or config is empty → `call("config")` fails or returns no keys → hooks resolve nothing and leave the wallet's original images untouched (fail-open).

## 5. Rollback / disable

1. LSPosed Manager → Modules → 卡面工坊 → disable (and/or remove scope `com.hihonor.wallet`).
2. `adb shell am force-stop com.hihonor.wallet` then launch → original server faces are back (module code never runs).
3. Full clean: `adb uninstall com.cardpersonality.honor` (removes module APK + its config/exports).
4. App data reset (keep APK installed): Settings → Apps → 卡面工坊 → 存储 → 清除数据, or
   `adb shell rm -rf /data/data/com.cardpersonality.honor/files` (as root on the KSU shell) → empty config → all hooks no-op.
5. NEVER reboot the device (bootloader locked); wallet restart is the soft path.

## 6. Diagnostics

Logcat tag `CARD_PERSONALITY` (also forwarded to LSPosed log):
`CARD_PERSONALITY_ALIVE`, `hooked <target>`, `nin0#p RECORD`, `nh5#c SUBSTITUTED`, `PROVIDER_OK/PROVIDER_FAIL`,
`applied face <fileKey> v=N to K view(s)`, `element HIDE <id>`, `CONFIG_CHANGED: refreshing faces`, `reported N cards`.

## 7. Known limitations (v11-final)

1. **卡包 list-row live refresh**: row thumbnails (`to6#B`/`mv1#c` → `iv_icon`) are replaced correctly on a normal
   bind/re-entry, but after a save while the wallet stays open the *already bound* row thumbnail may keep the
   previous image until the list re-binds/scrolls or the wallet is restarted (the wallet keeps its own
   `hv1.c`/`mv1.c` preload caches keyed per card; our refresh clears them and reloads detail/registered views, but
   the list-row ImageView is not always re-bound). Documented limitation, not required for the demo.
2. **Swipe card page (`SwipeCardStackView`)**: hooks H1/`nn0#p` + `z79#d/f` are installed, but this wallet UI
   build (11.0.3.305 on the test device) has no swipe card stack (首页 shows widgets, cards live under 卡包), so
   that surface cannot be exercised on-device.
3. **Element components** are KEEP/HIDE only (no reposition/restyle of wallet-drawn views); functional views are
   never hideable.
4. **Notification big images / other remote-image surfaces** are best-effort via the `nh5#c` replacement point plus
   the URL→face registry; surfaces that never route through `nh5#c` will keep the original face.
5. **Access card style-preview page** (`AccessCardCustomActivity`) is intentionally excluded from replacement.
6. Bank/campus/car-key/eBike visual replacement depends on a user config existing for that card; the shared
   `nh5#c` path is the same one proven for transit/access.


## 8. Threading / ANR-safety contract (v14 hotfix)

The wallet main thread must never block on our ContentProvider, and hook after-hooks must never
re-enter hooked getters.

1. `FaceHookEngine.hookFaceSourceGetters` after-hook wraps its whole body in a ThreadLocal
   re-entrancy guard (`inFaceSourceHook`); if re-entered it returns immediately and only counts
   (`getter hook re-entered; ignored (guard n=…)`).
2. The getter after-hook takes the URL from `param.getResult()` and extracts identity with
   `CardIdentityExtractor.of(receiver, includeFaceUrl = false)` — it never calls `faceUrlOf` and
   never reflectively invokes a hooked getter. `rememberFaceSources` (reflective getter probing)
   was removed entirely; `sourceToIdentity`/`sourceToFace` are filled from `param.getResult()` only.
3. `ProviderClient` never performs IPC on the caller thread: `config(false)` is cache-only and just
   schedules `prefetchAsync()`; forced refreshes run on a private single-thread executor
   (`cardpersonality-provider-client`). `resolve()/isConfigured()/hiddenElements()` are cache-only.
   Any IPC failure (incl. `IllegalArgumentException "Unknown URL"`) enters a 30 s no-IPC backoff and
   is logged at most once per window.
4. `report()` is fire-and-forget (`reportAsync`) from hook paths. `saveOriginalFace` /
   `originalExists` are only called from the `FaceCapture` worker thread
   (`cardpersonality-face-capture`); `FaceCapture.schedule()` only registers a pre-draw listener.
5. `CONFIG_CHANGED` handling runs forced config refresh + wallet cache clearing on the worker and
   hops back to the main thread only for view work.
6. Hot bind paths (`nn0#p`, `nh5#c`, `xi#j`, `pi3#c`, `z79#d`, detail adapters) use
   `CardIdentityExtractor.of(card, includeFaceUrl = false)`; the reported `faceUrl` is merged into
   `detected[key]` exclusively by the getter after-hook from `param.getResult()`.
## 9. Version policy (round 4)

- **versionCode is permanently pinned to `1`.** It is never auto-incremented by Gradle, scripts,
  CI or hooks; `scripts/build.ps1` only runs Gradle tasks and never rewrites the manifest/build file.
- **versionName is semantic / user-visible** and is displayed in-app (Home status area reads
  `BuildConfig.VERSION_NAME`). Bump it once per release:
  - `0.1.0` — first functional build (round 1–2)
  - `0.2.0` — round 4: text styles + font picker, snapping, image flip, page/tab animations
  - `0.2.1` — round 5: slider keypoints/reset, CJK fonts, pinned preview, soft snap + haptics,
    zip export, color picker, opacity, z-order, rename, thumbnails, flicker fixes
  - `0.2.2` — round 6: ZIP import, auto base image on add, tap-to-set sliders, softer snap,
    layer-order fix, compact font dialog, stroke fill+outline, key-point haptics, flicker and
    selection-speed fixes
  - `0.2.3` — round 7: rotation snap (±tolerance, snap-on-release), key-point magnet/haptics
    polish, size slider/gesture clamp, rename-save fix, drag freedom, new-on-top, real font
    names, outer-only stroke, selection latency, and ROBUST app-element hiding (see §12)
  - `0.2.4` — round 8: free sticker placement (drag clamp removed for oversized/long image
    stickers; any cx/cy allowed, preview and export share the same values) + font picker
    language annotations (西文/中文/自定义)
  - `0.2.5` — round 9: cold-start quick-card fix (wallet-local config/face caches + pending
    face retry), higher-resolution original-face capture / current-face base, rotation ±270,
    weaker slider magnet, numeric input dialog, 2x render resolution (2016×1248)
- Release APKs are frozen as `work/module/poc/card-personality-v<N>-final.apk`; the current
  delivery build is `v32-final` (versionName 0.2.5 / versionCode 1), verified with
  `aapt2 dump badging` (`versionCode='1' versionName='0.2.5'`).

Round-4 model additions (owned by ui-dev; JSON is backward-compatible: every new field has a
default, and the hook layer never parses sticker/config geometry — it only uses `version` and
`hasCustomFace`, so these additions require no hook/provider changes):
- `StickerSpec` (TEXT style): `fontWeight` (100..900, default 700), `italic`, `underline`,
  `glow` + `glowColor` + `glowRadius` (design units), `stroke` + `strokeColor` + `strokeWidth`
  (outline drawn before fill), `fontFamily` (built-in: sans-serif / sans-serif-medium /
  sans-serif-condensed / sans-serif-black / serif / monospace / cursive / casual), `customFontPath`
  (imported .ttf/.otf copied to `files/fonts`, runtime path stored relative on disk).
- `StickerSpec` (IMAGE): `flipX` / `flipY` (mirror the sticker image).
- `CardFaceConfig`: source-image `flipX` / `flipY`. Preview (`ui/components/CardFacePreview.kt`)
  and export (`FaceRenderer`) share the same style/flip rendering, so what you see is what the
  wallet gets.
- Editor "自动吸附" toggle is an App-level SharedPreferences setting (`ui/util/EditorPrefs.kt`),
  not part of the card config JSON / exports.
- In-app version display reads `BuildConfig.VERSION_NAME` (0.2.0 for v16-final).
## 10. Round-5 additions (0.2.1)

Hook/provider layer unchanged in round 5 — everything below is app/data/UI side (ui-dev owned).

### 10.1 Data model (JSON backward-compatible, defaults when absent)
- `StickerSpec.name: String?` — user-visible sticker label (long-press rename in the editor rows).
- `StickerSpec.alpha: Float` (0..1, default 1.0) — sticker opacity, applied to text AND image
  stickers in both the editor preview and `FaceRenderer` export (preview/export parity).
- CJK font support: the renderer's font registry adds Chinese families (荣耀黑体 / HONORSansVFCN,
  Noto Sans CJK, Noto Serif CJK, DroidSansChinese …) resolved through
  `Typeface.CustomFallbackBuilder` so CJK glyphs fall back correctly even for Latin-first families.
  Sticker stores the built-in family id in `fontFamily`, or an imported font file path in
  `customFontPath` (`files/fonts`, import cap 32 MiB with an explicit oversize reason).
- Editor UX state only (not card config JSON): pinned preview, slider keypoint/reset, soft-snap
  toggle, haptics — persisted via `EditorPrefs` (App-level SharedPreferences).

### 10.2 ZIP export format ("保存当前卡面 ZIP")
`FaceRepository.exportCardZip(ctx, key, cfg, title)` writes
`Download/honor_cards_personality/<safeTitle>_<yyyyMMdd-HHmmss>.zip` through MediaStore Downloads
(API 29+; falls back to the app external files `exports/` directory). Entry layout:

```
manifest.txt     honourable text summary:
                 appVersion=<BuildConfig.VERSION_NAME>, exportedAt=<ISO-ish>, cardKey,
                 cardTitle, sourceImage, stickerCount, configVersion + file list
config.json      CardFaceConfig JSON with paths RELATIVE to filesDir (portable archive)
source/bg.<ext>  custom face source image (when configured)
images/<file>    image sticker files (when any)
fonts/<file>     imported custom font files (when any)
```

Paths inside `config.json` are relative, so an exported archive can be re-imported/moved without
depending on absolute app paths. The hook and provider never touch the ZIP; it is produced/consumed
entirely in the app process.
## 11. Round-6 additions (0.2.2)

Hook/provider layer unchanged. App/data/UI changes:

### 11.1 ZIP import (round-trip with §10.2 export)
`FaceRepository.importCardZip(ctx, uri)` accepts a SAF `OpenDocument` stream (application/zip or */*)
and restores a card customization exported by §10.2. Validation is explicit and fail-fast:
- path traversal / `..` entries rejected; `MAX_ZIP_ENTRIES` and 128 MB total cap enforced;
- `config.json` is required (searched at root or depth ≤2); missing/invalid config surfaces a
  concrete Chinese error (not a generic IO message);
- `source/bg.*`, `images/*`, `fonts/*` entries are matched to the config's relative paths and copied
  into the app store (`files/images`, `files/fonts`) with collision-safe renamed files;
- the imported `CardFaceConfig` has its paths rewritten to the new absolute app paths, then replaces
  the edited card's config (`hasCustomFace = true`), is saved + notified, and the editor reloads.
Export UI is now only the 图片-tab row ("保存当前卡面 ZIP"); the TopAppBar export icon from 0.2.1 was
removed per user request, and "导入卡面（ZIP）" sits in the same tab.

### 11.2 Layer order semantics (fixed)
Sticker list index 0 = TOP-most layer. Preview and `FaceRenderer` export both draw the list in
reverse order (index 0 painted last). The ↑/↓ arrows keep "up = higher layer"; row order == z-order.
Hit-testing picks the top-most (lowest index) sticker under the pointer.

### 11.3 Stroke rendering (fixed)
Text with `stroke = true` now draws the FILL first (including glow) and then the outline with
`STROKE` + `strokeColor` + `strokeWidth` + round join — the glyphs keep their fill color instead of
becoming hollow, and the stroke color picker edits `strokeColor` (it previously showed white).

### 11.4 Flicker / theme
- `AppRoot` and tab switches use fade-only `AnimatedContent` (no sliding scroll container), per-tab
  scroll state is preserved; the editor preview stays pinned.
- New app theme `Theme.HonorCards` (`res/values/themes.xml` + `res/values-night/themes.xml`) matches
  the Miuix light/dark background; `MainActivity` now uses `@style/Theme.HonorCards` instead of the
  platform Material theme, so the window background is correct on the very first frame.
## 12. Round-7: app-element hiding robustness (0.2.3)

User-selected wallet view ids (`hiddenAppElements`, e.g. the bottom-left 「已出站」 chip with id
`tv_card_label`) previously failed to hide because `applyElements()` only looked inside the
adapter's item/holder root once, right after bind.

Current implementation (`FaceHookEngine`):

1. **Target lookup** — resolve `id/<name>` in the wallet package (`elementViewId`, with fallbacks),
   then search in order: the bound root (`findViewById`), the whole window root (`root.rootView`),
   and every ancestor of the bound root (`findHideTarget`). Covers labels inflated outside the
   list-item subtree (fragment/activity content views).
2. **Re-apply after bind** — `applyElements()` applies immediately and again on delayed main-thread
   passes (150 / 400 / 900 ms) so wallet data loads that later reset visibility cannot resurrect
   the element.
3. **Live config changes** — `afterBind` registers every bound root in a `WeakHashMap`
   (`boundRoots`). On `CONFIG_CHANGED` (broadcast or content observer) the refresh callback runs
   `reapplyElementHides()`: it re-applies hides to all attached bound roots and to the foreground
   window's decor view for all detected cards.
4. **Live unhide** — views WE set to `GONE` are tracked (`ownerHiddenViews`); when an id is no
   longer in any configured hide list, `restoreUnhiddenElements()` sets those views back to
   `VISIBLE` (so toggling 隐藏 off takes effect without leaving the page).
5. **Safety** — `SAFE_HIDE_IDS` whitelist only (KEEP is the default; no functional views such as
   payment QR / ride code / location / lock tips are ever hidden); every attempt is logged
   (`hide_<id>`, `hide_miss_<id>`, `hide_reapply`, `element UNHIDE`).

Note for testing: after a fresh `adb install` the panel app is in Android's *stopped state*, so the
wallet's provider calls fail with `IllegalArgumentException: Unknown authority
com.cardpersonality.honor.face`. Launch the panel once after install (then restart the wallet) to
make the config/provider channel reachable.
## 13. Round-9: cold-start quick card + original-face quality (0.2.5)

### 13.1 Double-press-power cold start showed the original face
`SwipeCardActivity` (Honor quick card) binds the card face during a cold wallet start, often before
the panel app's provider is reachable (panel force-stopped, or provider process still starting).
Previously `applyFaceToRoot()` simply returned when `resolve() == null` and the page never re-bound,
so the original face stayed visible.

Fixes (all in `hook/`):

1. **Wallet-local config cache** — every successful config read is persisted as JSON in the wallet
   process cache (`<wallet cacheDir>/cardpersonality_config_cache.json`). When provider IPC fails
   ("Unknown authority", "No content provider", …), `ProviderClient` falls back to this cached
   snapshot (`PROVIDER_CACHE …`), so `resolve()` works during cold start.
2. **Wallet-local face JPEG cache** — `FaceImageLoader` caches the provider JPEG bytes in
   `<wallet cacheDir>/cardpersonality_faces/<sha256(fileKey)>_v<version>.jpg`. If the content URI
   cannot be opened (provider unavailable) the cached JPEG is decoded instead
   (`face loaded from wallet disk cache …`). This lets an already-used custom face render on the
   quick card before the panel app is reachable.
3. **Pending face roots + retries** — `applyFaceToRoot()` records `(root, identity)` in
   `pendingFaceRoots` when config is not ready and schedules retries at 300/800/1500/2500 ms; still
   attached roots are re-applied. `ProviderClient.addConfigListener` also fires `retryPendingFaces()`
   on the main thread as soon as a new config snapshot arrives.
4. **Transient provider backoff** — "Unknown authority"/"Unknown URL" failures use a 1.2 s retry
   (up to 20 attempts) instead of the normal 30 s backoff, so the panel app becoming reachable is
   picked up quickly.
5. Quick-card coverage confirmed: `SwipeCardStackView#setAdapter` (H13) and `z79#d/f` (swipe adapter
   bind/data) hooks are installed; they now hit the pending/retry path instead of giving up.

### 13.2 "Use current face as base" was blurry
- `FaceCapture` now captures the wallet `ImageView` drawable at its **intrinsic full resolution**
  (long side up to 2560 px, previously downscaled to 640), JPEG quality **95** (was 85), and
  `FaceProvider` accepts originals up to **3 MB** (was 512 KB). One capture per card per process,
  only when no custom face is configured.
- `FaceRepository.ensureOriginalFace` keeps CDN downloads at **native resolution** (cap 4096 px,
  JPEG 95) instead of downsampling to ~1024 px, and returns whichever of (hook snapshot / CDN
  download) is sharper.
- `useOriginalAsSource` picks the sharpest available original before writing the source image, and
  the source-image writer uses JPEG quality 95.