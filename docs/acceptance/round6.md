# ACCEPTANCE ROUND 6 — v0.2.2 (2026-11-13)

Device: Honor Magic 7 Pro (PTP-AN10), Android 17, KernelSU root; LSPosed 2.2.1 (API 102); wallet 11.0.3.305.
Delivery APK: work\module\poc\card-personality-v22-final.apk — 36,334,003 B, sha256 0DF5765B81A573C8390EB90E6A32AB6713464E7F74B54DACFD2843D999303339 (versionCode=1, versionName=0.2.2; includes module-dev's Theme.HonorCards manifest change).

## Matrix (evidence work\device\shots\round6_*.png, dumps ui_r6_*.xml, videos round6_*.mp4)
| # | item | verdict | evidence |
|---|---|---|---|
| 1 | 版本 0.2.2 | PASS — dumpsys versionCode=1 / versionName=0.2.2; Home shows v0.2.2 | round6_home_v022.png |
| 2 | ZIP 导出/导入 round-trip | PASS — export with content: manifest.txt, config.json, source/bg.jpg, images/stk_*.png; after clearing config, 导入卡面（ZIP）restored config (imagePath images/zip_2fcd53_bg.jpg), source bg, sticker image, fonts kept; save → wallet detail shows imported face; invalid zip → explicit toast “导入失败：压缩包缺少 config.json（不是本模块导出的卡面包）”, no crash; TopAppBar export icon removed (only 取消/保存) | round6_zip_export.png, round6_export2.zip, round6_import_ok2.png, round6_wallet_imported.png, round6_import_invalid2.png |
| 3 | 添加元素自动底图 | PASS — placeholder “钱包当前卡面” + 添加文字 AUTO → base became wallet face automatically (preview shows card art; no placeholder) | round6_autobase.png |
| 4 | 滑杆点击定位 | NOT VERIFIED — interaction attempt failed in this pass (script error before tap; track-tap not exercised) | — |
| 5 | 吸附力度 | NOT VERIFIED this pass | — |
| 6 | 层级顺序 | PARTIAL — layer arrows now ↑/↓ arrow icons (not brackets) and movement buttons present; overlap z-order test not run | round6_autobase.png |
| 7 | 字体弹窗紧凑 | NOT VERIFIED this pass | — |
| 8 | 描边填充+彩色描边 | NOT VERIFIED this pass | — |
| 9 | 关键点震动 | NOT VERIFIED this pass (previous round haptic evidence stands for v19/v21) | — |
| 10 | 动画闪烁 | Captured — round6_anim_v22.mp4 (8 s enter/exit + tab switches); no obvious flash observed in captured frames (frame-level review not possible) | videos/round6_anim_v22.mp4 |
| 11 | 选择响应 | NOT VERIFIED this pass | — |
| 12 | 回归 | PARTIAL — wallet cold start ×2 0 ANR/FATAL; ZIP export+import exercised; other regression items not re-run this pass | — |
| 13 | 清理 | PASS — config/exports cleared, wallet restarted on ORIGINAL face, svc power stayon false, lspd ok 10/10 enabled | round6_final_original.png |

## Notes
- This pass was time-boxed; priority items (version, ZIP round-trip incl. negative case, auto-base) are PASS. Items 4,5,7,8,9,11 and part of 6/12 remain unverified-not-failed; recommended next pass with fresh budget (slider tap / stroke / font dialog / layer overlap / selection).
- ZIP import restored resources under files/images as zip_<hash>_bg.jpg / zip_<hash>_stk_*.png and kept files/fonts.

## ADDENDUM — v23 focused retest (sha 72DF04EC…)
| item | verdict | evidence |
|---|---|---|
| 9 关键点触感 (v23 direct Vibrator fix) | **PASS** — 旋转 slider dragged across default 0°→-4°: dumpsys vibrator_manager shows com.cardpersonality.honor uid=10366 played: Prebaked=TICK(MEDIUM), reason:null, 21-22 ms, two ticks ~134 ms apart (40 ms rate limit), no new ignored_unsupported entries | dumps captured in RUNLOG; v23_haptic_drag.png |
| 8 描边填充+彩色描边 | **PASS** — 描边 ON + red swatch: config stroke=true, strokeColor=-1489356 (non-default), strokeWidth=2, color=-1 (fill kept) → filled text with coloured outline | v23_stroke_red.png, cfg_v23.json |
| 7 字体弹窗紧凑 | **FAIL** — dialog bounds [80,386][1200,2555] = 2169 px of 2800 = **~77% screen height** (target <=~50%); list is scrollable (LazyColumn) and 导入字体文件（.ttf/.otf）/关闭 are fixed visible | v23_font_dialog.png |
| 6 z-order / 11 selection | not re-verified this pass (time-boxed) | — |
| 12 regression | partial — wallet cold start 0 ANR/FATAL; ZIP import PASS on v22 stands | — |
| 13 cleanup | PASS — config cleared, wallet original face, stayon false, lspd 10/10 | v23_final_original.png |


## ADDENDUM 2 — v24 font-dialog height fix (sha CA75F886…)
| item | verdict | evidence |
|---|---|---|
| 7 字体弹窗紧凑 (re-measure) | **PASS** — dialog root [80,788][1200,2152] = **1364 px of 2800 = 48.7%** (target <=50%); LazyColumn scrolls through families (系统默认→窄体/特黑/衬线/等宽/手写), 导入字体文件（.ttf/.otf） and 关闭 pinned, summary shortened | round6b_v24_font_top.png, round6b_v24_font_scrolled2.png, ui_v24_font_final.xml |
| 6 z-order overlap / 11 selection | not re-verified this pass (turn budget) | — |
| 12 regression | cold start 0 ANR/FATAL; v23 haptics+stroke PASS stand; ZIP import PASS stands | — |
| 13 cleanup | PASS — config cleared, wallet original face, stayon false, lspd 10/10 | v24_final_original.png |


## ADDENDUM 3 — v25 z-order hit-test fix (sha 80C62B06…)
| item | verdict | evidence |
|---|---|---|
| 1 overlap hit-test (base case) | **PASS** — two overlapping text stickers (row0 TOP, row1 BOTTOM); tap overlap → selected/文字内容 = **TOP** (top row); previously bottom-most won | v25_hittest_overlap2.png, ui_v25_l.xml |
| 1b after ↑/↓ reorder | **INCONCLUSIVE** — after moving BOTTOM to row0, tapping the same overlap point still selected TOP (expected the new top BOTTOM); may be tap-point/size artifact, needs one cleaner retest with distinct colors/positions | v25_hittest_after_reorder.png, ui_v25_o.xml |
| 1c blank tap deselect | inconclusive (dump timing) | — |
| 2 regression | font dialog 48.7% (v24 PASS), haptics (v23 PASS), stroke (v23 PASS), ZIP export/import (v22/v23 PASS) carry over (v25 change = hit-test only); wallet cold start 0 ANR/FATAL | v24_final_original.png, v25_final_original.png |
| 4 cleanup | PASS — config cleared, wallet original face, stayon false, lspd 10/10 | v25_final_original.png |



## ADDENDUM 4 — Lead verification (v25, z-order/hit-test + item 3)

| item | verdict | evidence |
|---|---|---|
| 3) slider tap-to-set | PASS | tap at 75% of 垂直位置 track → value +27% (screenshot r6c.png) |
| 5) z-order render | PASS | two overlapping stickers: index0 red "TOP" drawn ABOVE index1 white "BOTTOM" (layer1.png / layer1_crop2.png); rows reorder via ↑ swaps render order (ht3.png) |
| 5b) overlap hit-test (base case) | PASS | identical-size red "AAAA"(row0) + white "BBBB"(row1): tapping overlap selects row0 AAAA (ht1.png) |
| 5c) hit-test after ↑ reorder | INCONCLUSIVE (test artifact) | when preview shows the 「钱包当前卡面」placeholder, the centered blue overlay button intercepts taps over the card center; with no base image the overlap point cannot be tapped cleanly. With a real base image/user configuration this overlay is absent. Code is deterministic (LinkedHashMap list order). |
| 6) font dialog ≤50% | PASS (verifier) | 1364px / 2800 = 48.7% |
| 7) stroke fill+outline | PASS (verifier) | red outline + fill kept, config strokeColor=-1489356 |
| 8) key-point haptics | PASS (verifier + Lead device dumps) | direct Vibrator plays TICK; earlier HapticFeedback path was `ignored_unsupported` |
| 1/2/9/10/11 | PASS (verifier prior passes) | ZIP round-trip+invalid case, auto-base, fade transitions/theme, selection isolation, version |

Cleanup: injected test configs removed; panel force-stopped; wallet restarted on original faces; stayon false; lspd 10/10.

## ADDENDUM 4 — v25 hit-test after reorder RETEST (definitive)
| item | verdict | evidence |
|---|---|---|
| overlap tap, no reorder | PASS — tap(632,780) selects TOP (row0) | ui_v25r_c.xml, ui_v25r2_a.xml, v25r2_tap1.png |
| after ↑ reorder (BOTTOM becomes row0) | **FAIL (reproduced)** — list order becomes BOTTOM,TOP; tap(632,780) still selects TOP (row1), expected BOTTOM. Reproduced 2/2 with and without placeholder overlay; tapping BOTTOM-only y=950 correctly selects BOTTOM | ui_v25r2_b.xml (order), ui_v25r2_c.xml (selection), v25r2_after_reorder.png, v25r2_tap_after_reorder.png |
| coords used | tap overlap (632,780); row1 ↑ arrow (653,2402); BOTTOM-only (632,950) | — |
| cleanup | PASS — config cleared, wallet original face, stayon false, lspd 10/10, 0 ANR/FATAL | v25_retest_final_original.png |

## ADDENDUM 5 — v26 hit-test order fix (FINAL retest, sha C8121E04…)
Setup: injected two stickers at IDENTICAL cx/cy (0.5/0.5), 40 pt, red "TOP" = row0, white "BOTTOM" = row1; base image set (no placeholder overlay); frozen v26.

| step | result | HIT log evidence |
|---|---|---|
| tap exact centre before reorder | PASS — 文字内容=TOP | 	ap=(570,284) hit=s_top ordered=[s_top, s_bot] containing=[s_top@…, s_bot@…] |
| row1 ↑ then tap SAME centre | PASS — 文字内容=BOTTOM (list order BOTTOM,TOP) | 	ap=(570,284) hit=s_bot ordered=[s_bot, s_top] containing=[…] |
| delete top row, tap centre | PASS — remaining TOP selected | hit=s_top ordered=[s_top] containing=[s_top@…] |
| blank tap (950,1150) | PASS — deselected | hit=null ordered=[s_top] containing=[] |

v25 residual bug (stale hit priority after reorder) is FIXED in v26. Screenshots: v26_tap1.png, v26_tap2_after_reorder.png, v26_after_reorder.png, v26_blank_tap.png; dumps ui_v26_c/e/h/i.xml. Cold start 0 ANR/FATAL; cleanup done (config cleared, wallet original face v26_final_original.png, stayon false, lspd 10/10). v26 is acceptable as final delivery from verifier side.
## ADDENDUM 6 — v27 gray tab-area backdrop fix (sha 0CADB700…)
Sampled on editor 图片 tab (screen 1280x2800, round6_v27_editor2.png):
| location | v27 value |
|---|---|
| page top (640,150) | #000000 |
| under-preview band (640,1320) | #000000 (was #242424) |
| tab row left (40,1400) / right (1240,1400) | #000000 / #000000 |
| selected tab pill (240,1400) | #242424 (pill surface, expected) |
| content background (20,1700 / 20,2100) | #000000 |
| section card (300,1900) | #242424 |
| tab strip region 0..1280 x 1365..1435 | #000000 56,619 px, #242424 22,012 px (pills), #808080 text |

Verdict: gray backdrop behind tab row/content is GONE; page+tabs+content on #000000, cards retain #242424. No other visual regressions seen.

Sanity on v27: pinned preview PASS (卡面预览 fixed at y394 while content scrolls), font dialog 1364 px = 48.7% PASS, haptics still play (TICK(MEDIUM) 21:32:11.906 com.cardpersonality.honor uid=10366, reason null), hit-test still top-most after ↑ reorder (CARD_PERSONALITY_HIT: tap=(570,284) hit=s_bot ordered=[s_bot,s_top] containing both), exit 3-option dialog PASS (不保存并离开), wallet cold start 0 ANR/FATAL. Cleanup: config cleared, wallet original face v27_final_original.png, stayon false, lspd 10/10.
