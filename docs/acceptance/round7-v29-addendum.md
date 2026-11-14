# ADDENDUM — v29 focused pass (sha C9BE141B…, v0.2.3)

APK: work\module\poc\card-personality-v29-final.apk, 36,328,067 B, sha256 C9BE141B764D837919D60A99407CAE45CCA738D9B42BF8AAF515876CF4B92B97.
Install workaround applied: launched panel once after `adb install`, then restarted wallet.

## Results

| # | item | verdict | evidence |
|---|---|---|---|
| 1 | 版本 0.2.3 | **PASS** — dumpsys versionCode=1/versionName=0.2.3; Home shows v0.2.3 | round7b_final_original.png (Home), ui_r7b_home (round-7 file) |
| 1 | 组件隐藏 (v29 re-confirm) | **PASS** — hiddenAppElements=["tv_card_label"]: wallet 交通卡 detail chip 「已出站」 GONE; LSPosed log `element HIDE tv_card_label (HwTextView, was=0) card=transit:… root=HwViewPager delay=150` (+ `already GONE` retries); chip present in original-face baseline | round7b_hide_chip_gone.png (chip gone), round7_baseline_wallet.png (chip present), logs/round7b_lspd.log |
| 1 | live unhide via CONFIG_CHANGED | **PARTIAL** — pushed unhide config + broadcast while detail page open; log still showed HIDE (injection reused same store version → hook cache not invalidated); static unhide (restart) restores chip (round-6/7 evidence). Needs panel-UI toggle or version bump to test live-unhide properly | logs/round7b_lspd2.log |
| 2 | 旋转吸附 90/180 | **PASS** — tap at ≈90° position → value **90°** exactly; tap at ≈180° → **180°** exactly (v29 ±11° snap works). Drag-release near 180 ended at 198° (18° off → outside ±11°, no snap) | ui_r7b_rot90.xml, ui_r7b_rot180.xml, round7b_rot_snap.png |
| 2 | snap haptic | **UNVERIFIED** — no fresh com.cardpersonality.honor TICK captured in vibrator history around the snap taps (history last app entries 01:58); needs a drag-crossing test with timestamps | vibrator dumps in RUNLOG |
| 3 | 尺寸 slider | **PASS** — label 尺寸, default **50%**, drag to right end → **200%**, left end → **5%**; saved config s_tall w=**2.0** | ui_r7b_imgsize2.xml (50%), ui_r7b_size_max.xml (200%), ui_r7b_size_min.xml (5%), cfg_r7b_saved.json |
| 4 | 描边仅外轮廓 (visual) | **UNVERIFIED** — config with stroke=true/strokeColor=red/strokeWidth=2 saved (cfg_r7b_saved.json), but injected sticker layers are not composited into the wallet card face (source image only), so no zoomed wallet screenshot; needs a panel-UI stroke test | cfg_r7b_saved.json |
| 5 | 选中延迟 | **PASS** — `CARD_PERSONALITY_PERF`: `tap handled in 0ms hit=s_tall`, `tap->nextFrame +5ms hit=s_tall` | logcat lines in RUNLOG |
| 6 | 长图 200% 拖动 | **PARTIAL** — tall image at w=2.0 dragged; saved cx=0.929, cy=0.976 (moved toward bottom-right from 0.5/0.5). Did not exceed old 0.02/0.98 clamp in this test (0.976 < 0.98) → clamp relaxation inconclusive | cfg_r7b_saved.json, round7b_tall_corner.png |
| 7 | 字体原名 | **UNVERIFIED** — 字体 row could not be kept in view reliably after multiple scrolls; dialog names not captured this pass (dialog compactness 48.7% verified on v24) | — |
| 8 | 快速回归 | **PASS (subset)** — 离开三选项 dialog PASS (不保存并离开); custom face applies to wallet detail; wallet cold start ×1 → 0 ANR/FATAL; hide reapply logs on page open. ZIP import/export not re-run (round-6/7 PASS carried) | ui_r7b_exit.xml, round7b_hide_chip_gone.png |
| 9 | 清理 | **PASS** — config/exports/tall_test.png cleared, wallet restarted original face, `svc power stayon false`, LSPosed 10/10 enabled, 0 ANR/FATAL | round7b_final_original.png |

## Notes / limits of this pass
- Editor scroll-state via adb is fragile: after several swipes the 字体 row / tab bar can drop out of view and dumps occasionally fail; that is why item 7 (font names) and full stroke visual remain unverified.
- Injecting `files/config.json` directly updates the hook's config only for image/hide (image applies); sticker layers appear in the wallet face only through a panel-save flow (round-6 evidence). Version field must be bumped in injected configs to force CONFIG_CHANGED reapply (this pass revealed that).
- v29 rotation snap fix is confirmed working for slider tap at ±90 and ±180.
