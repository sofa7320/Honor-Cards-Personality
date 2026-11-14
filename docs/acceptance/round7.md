# ACCEPTANCE ROUND 7 — v0.2.3 (v28-final, sha FB528C51…)

Device: Honor Magic 7 Pro (PTP-AN10), Android 17, KernelSU root; LSPosed 2.2.1 (API 102); wallet 11.0.3.305.
APK under test: app\build\outputs\apk\debug\app-debug.apk = v28 green build,
size 36,156,070 B, sha256 FB528C513302435B4A4634277ACC36F93C4B9EF643A804D03C71C842F5744DEF.
dumpsys package com.cardpersonality.honor → versionCode=1, versionName=0.2.3.

## Matrix

| # | item | verdict | evidence |
|---|---|---|---|
| 1 | 版本 0.2.3 | **PASS** — dumpsys versionCode=1 / versionName=0.2.3; Home shows v0.2.3 | round7_home_v023.png, ui_r7_home.xml |
| 2a | 旋转滑杆范围 ±360 | **PASS** — dragged to ends: 360° and -360° | ui_r7_rot_right.xml, ui_r7_rot_left.xml, round7_rot_left.png |
| 2b | 旋转吸附 90/180/270 | **FAIL/UNREACHABLE via slider** — tap at x=770 (≈90° region) yields 96°, no snap; ±2° tolerance over an 1042 px track ≈ 1.4 px is not reachable by adb tap/drag; two-finger gesture snap not automatable (no multi-touch injection) | ui_r7_rot_90b.xml (value 96°) |
| 2c | 旋转/防误触手势震动 | **UNVERIFIED** — no fresh TICK from uid=10366 captured during the rotation drag attempts (vibrator history last app entries were older, 00:56); multi-touch not injectable via adb | vibrator dumps (see RUNLOG) |
| 3 | 尺寸滑杆 (label 尺寸, 默认 50%, 5–200%, pinch clamp) | **UNVERIFIED** — editor session lost foreground during automation before reaching the image-sticker style sheet (see limitation) | — |
| 4 | 滑杆磁吸 + 关键点震动一次 / 两端各一次 | **UNVERIFIED** — no fresh app TICK entries captured during drag attempts; see limitation | — |
| 5 | 重命名后保存 | **UNVERIFIED** — editor session instability (panel activity lost foreground) | — |
| 6 | 长图 200% 拖动到画布外 | **UNVERIFIED** — test config + tall_test.png prepared on device; not exercised in editor | cfg_r7_long.json, files/images/tall_test.png |
| 7 | 新增元素置顶 (index0) | **UNVERIFIED** — add-text flow interrupted by editor-session loss | — |
| 8 | 字体原名显示 | **UNVERIFIED** | — |
| 9 | 描边仅外轮廓 | **PARTIAL / NOT CONFIRMED** — injected config with red stroke width 2 applied its source image to the wallet card, but injected sticker layers do not render into the wallet face (observed also for cfg_r7_two TOP/BOTTOM), so the stroke visual could not be captured from an injected config; needs a panel-editor save pass | round7_stroke_wallet.png, round7_stroke_zoom.png |
| 10 | **KEY 组件隐藏 tv_card_label** | **PASS** — hidden config → wallet 交通卡 detail chip 「已出站」 GONE; unhide → chip back; hook logs show hide applied | round7_hide_chip_after3.png, round7_baseline_wallet.png (chip before), round7_unhide_chip_back.png (chip back); logs/round7_lspd_hide.log: `element hide target NOT found` (list-root attempts) then `element HIDE tv_card_label (HwTextView, was=0) ... root=HwViewPager delay=150`, `element already GONE` |
| 11 | 选中延迟 CARD_PERSONALITY_PERF | **UNVERIFIED** — editor session not stable enough to collect | — |
| 12 | 回归 | **PARTIAL** — custom face applies to wallet card (sunset image on detail) ✓; chip hide/unhide ✓; wallet cold start ×2: 0 real ANR/FATAL (only the grep command echo matched the pattern); ZIP import/export, font dialog 48.7 %, hit-test, haptics were not re-run in this session | round7_unhide_chip_back.png, round7_final_original.png |
| 13 | 清理 | **PASS** — config.json + exports cleared, tall_test.png removed, wallet restarted on ORIGINAL face, `svc power stayon false`, LSPosed 10/10 enabled, 0 real ANR/FATAL | round7_final_original.png / round7_final_original2.png |

## Limitations found during this pass (important)
- **Editor session loss**: twice during automated slider interactions the editor activity stopped being foreground and the wallet screen resumed (panel process alive, no panel FATAL in crash buffer). Consequence: UI items 3–8, 11 could not be exercised reliably by adb-only automation in this session.
- **uiautomator instability**: repeated/concurrent `uiautomator dump` caused `IllegalStateException: UiAutomationService ... already registered` in the uiautomator CLI process (NOT the panel). Mitigated by pkill+retry in scripts/r7_helpers.ps1.
- **Injected configs render source image only**: configs written directly to files/config.json (with stickers) show the custom source image on the wallet card but not the sticker layers; panel-save path (round-6 evidence) did render stickers. This limits visual verification of sticker-only features via injection.

## Recommendation
Do a **human/manual or device-farm pass** for items 3–8 and 11 (they need real multi-touch + stable foreground), and a panel-save based shot for item 9. Round-7 KEY item (component hide) and version/rotation-range are verified.
## ADDENDUM — v30 preview-ghost fix (final round-7 check, sha 7F2540D8…)

APK: work\module\poc\card-personality-v30-final.apk, 36,328,067 B, sha256 7F2540D8E1A3AD154C912AF1BBBAFAB6559D605CDB425717A01CEE63DE82247D (0.2.3). Panel launched once after install, wallet restarted.

| check | verdict | evidence |
|---|---|---|
| 1) selected stroke text preview (ghost?) | **PASS** — ONE clean glyph set (white fill + red outer-only outline) with dashed selection box; no offset/doubled outline while selected | round7_v30_stroke_selected.png, round7_v30_stroke_selected_zoom.png |
| 1b) deselect render | **PASS** — clean single render | round7_v30_stroke_deselected.png |
| 2) save -> wallet regression | **PASS** — wallet detail shows white filled “ABC” with red outer-only outline (rotated 180° from rotation test); config stroke=true, strokeColor=-1489356, strokeWidth=2 | round7_v30_wallet_stroke.png, round7_v30_wallet_stroke_zoom.png, cfg_v30_saved.json |
| 3a) rotation snap | **PASS** — slider tap ≈90° → 90°, ≈180° → 180° | ui_v30_rot90.xml, ui_v30_rot180.xml |
| 3b) 尺寸 default 50% | carried from v29 PASS (v30 change is preview-only; label/default unchanged) | ui_r7b_imgsize2.xml (v29) |
| 3c) hide tv_card_label | **PASS** — custom face + hidden chip config → wallet detail chip 「已出站」 GONE | round7_v30_hide_chip.png |
| 3d) cold start | **PASS** — 0 real ANR/FATAL | logcat |
| 4) cleanup | **PASS** — config/exports cleared (UI 不保存并离开), wallet original face, stayon false, lspd 10/10 enabled | round7_v30_final_original.png |

Notes: rotation was left at 180° during the stroke render test, so wallet text appears upside-down by design of that test step (not a bug). 「余额异常，请联系客服」 still shown by wallet in the balance card (unrelated wallet state). v30 acceptable as round-7 final delivery from verifier side.
