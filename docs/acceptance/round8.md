# ACCEPTANCE ROUND 8 — v0.2.4 (v31-final, sha 54FD1FC0…)

APK: work\module\poc\card-personality-v31-final.apk, 36,156,074 B, sha256 54FD1FC0D2DF6B3E24CE35492B46FCB52DE7A25C0A8262300F88654AAD3E9304 (versionCode=1, versionName=0.2.4). Panel launched once after install; wallet restarted.

| # | item | verdict | evidence |
|---|---|---|---|
| 1 | 版本 0.2.4 | **PASS** — dumpsys versionCode=1/versionName=0.2.4; Home shows v0.2.4 | round8_final_original.png (Home), dumpsys |
| 2 | KEY 长图自由拖动 (clamp removed) | **PASS** — tall image at 尺寸 200% (w=2.0): 5 vertical UI drags → saved config **cy=2.79**; 4 horizontal drags → **cx=2.11** (old clamp −0.5..1.5 exceeded, values persist after save, no snap-back/resistance). Partial-visible case (cx=0.85, cy=0.851, w=1.0): editor preview crop and wallet detail crop show the same placement (stripes occupy right half of card) — preview/export/wallet consistent. Normal-size drag: text sticker 0.5/0.4 → 0.624/0.587 saved | round8_tall_dragged.png, cfg_r8_saved1.json (cy=2.79), cfg_r8_saved3.json (cx=2.11 evidence in cfg_r8_saved1/saved2 run), round8_vis2_preview_crop.png, round8_vis3_wallet_crop.png, cfg_r8_drag.json |
| 3 | 字体语言标注 | **PASS** — default row Sans Serif（西文，默认）; Latin rows end（西文）(Sans Serif / Medium / Condensed / Monospace / Cursive / Casual); CJK rows end（中文）(Honor Sans CN / Noto Sans CJK / Noto Serif CJK / Droid Sans Chinese); dialog height **1364 px = 48.7%** of screen, LazyColumn scrollable, 导入字体文件/关闭 pinned. Custom-font（自定义）row not present in list during this pass (imported font not listed after clean install) | round8_font_dialog.png, round8_font_dialog_scrolled.png, round8_font_dialog_bottom.png, ui_r8_fontdlg*.xml |
| 4a | rotation snap 90/180 | **PASS** — slider tap≈90°→90°, ≈180°→180° | ui_r8_rot90.xml, ui_r8_rot180.xml |
| 4b | 尺寸 default 50% | carried PASS from v29 (v31 does not touch size defaults; image sticker default verified then) | ui_r7b_imgsize2.xml (v29) |
| 4c | hide tv_card_label | **PASS** — custom composed face + hidden config → chip 「已出站」 GONE | round8_hide_chip.png, round8_hide_chip_crop.png |
| 4d | ZIP export | **PASS** — 图片 tab 保存当前卡面（ZIP）created 岭南通·羊城通_20261115-023536.zip 63,520 B in Download/honor_cards_personality | ls output, round8_zip_export.png |
| 4e | selection latency | **PASS** — CARD_PERSONALITY_PERF: 	ap handled in 0ms, 	ap->nextFrame +18ms | logcat |
| 4f | cold start ×1 | **PASS** — 0 real ANR/FATAL | logcat |
| 5 | cleanup | **PASS** — config/exports/tall_test.png cleared; wallet restarted on original face; stayon false; lspd 10/10 enabled | round8_final_original.png |

Notes:（自定义）annotation not exercised because the imported font was not in the picker list after a clean install; recommend repeating with an imported font if that annotation must be verified (code path exists per module-dev). Zoomed crops used for placement comparison: round8_vis2_preview_crop.png vs round8_vis3_wallet_crop.png.
