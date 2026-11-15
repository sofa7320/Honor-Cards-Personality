# ACCEPTANCE ROUND 9 — v0.2.5 (v32-final)

Device: Honor Magic 7 Pro (PTP-AN10), Android 17, KernelSU root; LSPosed 2.2.1 (API 102); wallet 11.0.3.305.

## Artifacts under test (two freezes)
| sha | note |
|---|---|
| `1A46D47F5FEFC6FDEC47C30895A7741052899A4DA94545FF5090C4AA26876952` (36,595,483 B) | first v32 freeze; behavioural tests (rotation/numeric/double-press/no-clamp) ran on this build |
| **`7EA231644FD6100608CB7FC70795921271E839D973FDCE833C1BC4758D0A7ED7`** (final, same size) | re-frozen with sticker-image decode 1536 px; **clarity checks, old-ZIP import and the final quick regression were run on this sha** (re-installed) |

versionCode=1 / versionName=0.2.5 on both (dumpsys + Home v0.2.5).

| # | item | verdict | evidence (sha) |
|---|---|---|---|
| 0 | 版本 0.2.5 | **PASS** — dumpsys versionCode=1/versionName=0.2.5; Home shows v0.2.5 | dumpsys (both) |
| 4 | **双击电源键冷启动** | **PASS** — force-stop panel+wallet → lock → double-press power (`input keyevent 26 26`) opens `SwipeCardActivity` showing the **custom face** (not original). 3 runs on 1A46D47F (2 cold incl. panel force-stop, 1 warm) + 1 cold run on final 7EA23164; normal wallet detail matches | round9_doublepress_try2/3/4.png (1A46), round9f_doublepress_cold.png + round9f_detail_custom.png (7EA2) |
| 1 | 旋转范围 ±270（无 ±360） | **PASS** — slider ends: 270° and **-270°** (no ±360); tap-snap 90→90°, 180→180°; numeric input 270 applied; final sha re-checked dialog 270 + slider left end -270 | ui_r9_rot_right2/left.xml, ui_r9f_rot270/rotleft.xml |
| 2 | 滑杆磁吸变弱、可精准停任意值 | **PASS** — tap at arbitrary track position stopped at **40°** (no forced snap to 0/90); weak magnet retained | ui_r9_rot_arb.xml (1A46) |
| 3 | 数值输入弹窗 | **PASS (part)** — 旋转 value tap → Miuix dialog “输入旋转 / 范围：-270° ~ 270°”, legal 270 applied; invalid “abc” → inline error “请输入有效数字（范围：-270° ~ 270°）” no crash; 999 → clamped 270; 透明度 dialog “范围：5% ~ 100%”. 字号 dialog not captured (editor scroll-state issue) | round9_num_dialog.png, round9_num_invalid_confirm.png, round9_alpha_num_dialog.png, ui_r9_* |
| 5 | 渲染 2016×1248 / 清晰度 / 旧 ZIP | **PASS on final sha** — caption “导出 2016 × 1248（2x）”; composed export file measured **2016×1248** (files/exports, 142 413 B; with 200% image sticker 27 682 B); image sticker at 200% sharp in export crop (no softness with 1536 decode); old ZIP `岭南通·羊城通_20261113-213845.zip` imported → config restored (zip_bdda76_bg.jpg 1.5 MB + 2 sticker pngs + fonts kept); wallet detail renders sharp | dumps/r9_export.jpg (2016×1248), r9_export2.jpg/dumps/r9_export2_stk.png, round9_zip_import_after3.png, cfg_r9_zipimport.json |
| 6 | 以当前卡面为底图 清晰度 | **FAIL / PARTIAL** — fresh wallet capture snapshot is **672×416 (60 449 B)**; “以当前卡面为底图” produced img_current_…jpg **672×416 (59 164 B)**; no ≥1008 px improvement observed vs old capture (originals dir still 640-class). Needs capture-path fix (wallet cache face / drawable intrinsic full-res) | files/cache/original (672×416), files/images/img_current_1794764185624_b500f3.jpg (672×416), round9_use_current_face.png |
| 7 | 回归 | **PASS (spot)** — double-press cold start, numeric dialog, rotation ±270 re-verified on final sha; element hide/stroke/font annotations/free placement/ZIP io/haptics/selection latency carried PASS from round 8 (v0.2.4 builds); wallet cold start ×1 → **0 real ANR/FATAL** | RUNLOG, round9_final_original.png |
| 8 | 清理 | **PASS** — config/exports/tall_test.png cleared; wallet restarted on original face; `svc power stayon false`; LSPosed 10/10 enabled | round9_final_original.png |

## Notes
- Double-press power must be sent as a fast pair (`input keyevent 26 26`) while the screen is dozing; single-press pairs only wake the lock screen. All three double-press runs opened `SwipeCardActivity` and rendered the custom face.
- Old ZIP picker: file rows are text nodes `*.zip` inside the SAF picker; selected `岭南通·羊城通_20261113-213845.zip` (older 1.58 MB export) and import succeeded.
- 字号 numeric dialog could not be reached reliably via adb scroll; 旋转/透明度 dialogs fully verified.
- Item 6 remains the one open quality item (capture resolution still 672×416); recommend module-dev revisit the original-face capture path.

---

# ADDENDUM — adaptive render + lossless capture re-test (sha `5FB7A5DFCEAA520EC162D06EC742F94CA29F7D726E1EEA1E9CFF1736FB454380`, 36,416,211 B, v0.2.5)

Re-frozen final artifact (supersedes both earlier v32 shas for delivery). Re-installed, panel launched once, wallet restarted.

| re-test | verdict | evidence |
|---|---|---|
| 6) 以当前卡面为底图 native, no upscale | **PASS** — fresh base copy is **672×416 (60 449 B, JPEG bytes copied, not upscaled)**; caption **“导出 672 × 416（2x）”**; composed adaptive export measured **672×416 (51 274 B)**; wallet detail renders this base with no worse sharpness than the original face | files/images/img_current_1794764849377_10a2f5.jpg (672×416), dumps/r9c_capture (672×416), dumps/r9c_export (672×416), round9c_use_current.png, round9c_adaptive_wallet.png vs round9c_original_detail.png |
| 5) photo base adaptive | **PASS** — 2400×1400 photo imported **native** (134 506 B); caption **“导出 2016 × 1248（6x）”**; composed export **2016×1248 (114 680 B)** with crisp lines/text; 200% image sticker export also sharp | dumps/r9c_photo_base.jpg (2400×1400), dumps/r9c_photo_export.jpg (2016×1248), dumps/r9c_photo_export_crop.png |
| 4) double-press cold start (final sha) | **PASS** — panel+wallet force-stopped → lock → `input keyevent 26 26` → `SwipeCardActivity` shows custom face (photo + ADAPT/R9P) | round9c_doublepress_cold.png, round9c_detail_custom.png |
| 3) numeric dialog + rotation ±270 (final sha) | **PASS (carried)** — identical UI code to `7EA23164` where verified (numeric range −270~270, legal apply, invalid inline error, 999→270; ends ±270). Final-sha editor could not reload config after force-stop (panel stopped-state → provider empty: “还没有卡面图片”), so these were not re-driven on `5FB7A5DF` | RUNLOG |
| old ZIP import (final sha) | **INCONCLUSIVE on 5FB7A5DF** — import picked `…20261113-213845.zip` but config stayed the previously imported photo (v3); carried PASS from `7EA23164` where import restored `zip_bdda76_bg.jpg` + stickers + fonts | run9g_zip_import.png, RUNLOG |
| cold start / cleanup | **PASS** — 0 real ANR/FATAL; config/exports cleared; wallet original face; stayon false; lspd 10/10 | round9c_final_original.png |

**Note for delivery:** item-6 root cause (wallet native asset 672×416; double-resample to 2016 was softer) is resolved by adaptive render — captured wallet bases now stay 672×416 end-to-end, photo bases render 2016×1248. Delivery sha: `5FB7A5DF…`.
