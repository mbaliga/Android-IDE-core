# Privacy Policy — Fonebrew

> **This is the working copy, not the hosted one.** The URL Play Console points at
> is **https://fonebrew.app/privacy**. This repo is **private**, so the policy
> cannot be a link into it: Play requires a URL a logged-out stranger can read.
> Publish this text at that URL before submitting, and keep the two in sync by hand.
>
> Supersedes the older draft at `docs/play/privacy-policy.md`, which still used the
> retired app name "Aarso" and left the hosting question open.

**Last updated: 29 August 2026**

Fonebrew is a local-first AI computing environment for Android (package
`dev.aarso`), made by A System of Cells. This policy describes exactly what the app
does with data. It is short because the app does very little.

## The short version

Fonebrew has no accounts, no advertising, no analytics and no tracking. There is no
Fonebrew server. **We collect nothing, because there is nowhere for it to go.** The
only data that ever leaves your device is a request you explicitly made: a model
file you chose to download, or a message you chose to send to a cloud provider you
configured with your own key.

## What the app collects

**Nothing.** No telemetry, no analytics, no crash reporting. There are no such
dependencies in the build, and that is a binding rule of the project rather than a
current configuration.

## What stays on your device

- **Your conversations**, in app-private storage. The whole history is a local tree.
- **The models you have downloaded.**
- **Your settings, loops and search index.**
- **Your API keys**, encrypted at rest with the Android Keystore.

All of it is removed when you uninstall the app.

## What is sent off your device, and to whom

Three things, and only when you cause them.

**1. Model downloads.** When you tap download, the file is fetched directly from
the host you chose, for example Hugging Face. That host sees a normal HTTP request
from your device, as it would from a browser. We are not in the middle of it.

**2. Cloud providers, if you add one.** Fonebrew runs models on your device by
default. If you add a provider (Anthropic, Gemini, or any OpenAI-compatible
endpoint) with **your own API key**, then the messages you explicitly send with
that provider selected go to **that provider**, over HTTPS, under **that
provider's** privacy policy.

- This is a real transfer to a third party, which is why the app marks every cloud
  provider in the interface as a **watched object**.
- It never happens by default and there is no hidden fallback to it.
- We never see those messages. There is no proxy of ours in front of the request.
- Your API key is encrypted at rest and is sent only to the provider it belongs to.
  It is never logged.

**3. Git operations you perform.** If you connect a Git host to use the coding
agent, requests go to that host with your credentials, exactly as a Git client
would.

No other data is transmitted anywhere.

## Reports and sharing

Flagging an output prepares an email or a share sheet that **you** send. Nothing is
transmitted automatically.

## Permissions, and why each exists

The Play Store build requests four permissions, and no more.

| Permission | Why |
|---|---|
| `INTERNET` | Model downloads, and any cloud provider or Git host you configured. |
| `ACCESS_NETWORK_STATE` | To avoid starting a download while offline. |
| `FOREGROUND_SERVICE` | So a generation you started keeps running when the screen turns off. |
| `FOREGROUND_SERVICE_DATA_SYNC` | The declared type for that service. |

The sideload build, distributed outside the Play Store, additionally offers screen
capture, an overlay and USB device access. Those are opt-in features of that build,
they are not present in the Play Store build at all, and none of them transmits
anything.

## Children

Fonebrew is not directed at children and collects no personal information from
anyone, including children.

## Changes

If this policy changes, the "Last updated" date above changes with it, and the
revised policy is published at this same URL.

## Contact

Fonebrew is made by **A System of Cells**. Questions about this policy or the app:
fonebrew@asystemofcells.com
