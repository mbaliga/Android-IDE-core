# Fonebrew Workdeck client for Kindle

This is the native, stoppable Kindle half of Workdeck protocol v1. The first bounded target is a
jailbroken Kindle Oasis 3 (`KOA3`, 1264×1680) on firmware 5.17.1.0.3. It does **not** jailbreak a
Kindle and contains no Amazon, model-provider, or API credentials.

The client authenticates the phone with a one-time HMAC challenge and authenticates every later
packet. It reconnects with bounded exponential backoff after Wi-Fi/sleep loss; inflates only
bounded dirty regions; renders through the FBInk binary installed by current Kindle homebrew;
selects DU/GL16/GC16 strategies from refresh hints; captures normalized touch; maps Oasis page
buttons to `page_up`/`page_down`; and can be stopped from KUAL or SSH without rebooting.

Build and verify on a Linux host:

```sh
make -C kindle-client test
make -C kindle-client
```

CI cross-compiles a static ARM hard-float binary because Oasis devices on modern firmware use the
KindleHF userspace. CI emits the binary beside a strict compatibility manifest containing its
actual size and SHA-256. On the phone, choose both handles in Fonebrew's Add Kindle flow. Fonebrew
rejects mismatches before the pinned SSH/SFTP install.

Runtime configuration lives at `/mnt/us/extensions/workdeck/bin/workdeck.conf` with only the
phone's private hotspot address, port, Workdeck pairing secret, and optional
`ORIENTATION=landscape`. `--daemon` writes `/tmp/fonebrew-workdeck.pid`; `--stop` terminates only
Workdeck and leaves the native Kindle UI recoverable.
