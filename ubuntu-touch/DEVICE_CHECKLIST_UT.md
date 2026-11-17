# Ubuntu Touch device checklist (all rows NEEDS-DEVICE-VALIDATION)

No Ubuntu Touch device, emulator or Lomiri exists in the authoring environment. Nothing here has been run.
Device decision is open (OQ-1 / OQ-33 / OQ-37: the owner is waiting for a OnePlus 6 on a newer image).

| # | Check | Pass means |
| --- | --- | --- |
| 1 | Install the CI click (`clickable install`, or sideload from the draft release) on 24.04-1.x; repeat on 24.04-2.x when available | installs; if not, the 20.04-framework assumption is wrong, record the exact error |
| 2 | Launch from the app grid | icon shows, window opens, page renders, no blank WebView |
| 3 | Enter two texts | hunks, "+N added, -N removed" line and unified diff appear and are readable (glyph + underline/strike, not colour alone) |
| 4 | Type a draft, press the power button / switch away for 30 s, return | draft is intact (Lomiri froze the app) |
| 5 | Force-quit then relaunch | "Restored your last draft." appears with the same text |
| 6 | "Clear draft" then relaunch | empty |
| 7 | Rotate; on-screen keyboard open | fields stay visible and usable |
| 8 | Airplane mode vs. normal: the app behaves identically; `journalctl`/network monitor shows no connection from the app | no egress |
| 9 | `click-review` / OpenStore review of the click | no errors (warnings recorded) |
| 10 | Dark and light system theme | text legible in both |

Not implemented, so not on this list: Content Hub import/export (UT-4), keys, models, any Fonebrew feature
beyond the diff view.
