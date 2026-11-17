// SPDX-License-Identifier: Apache-2.0
import SwiftUI
import FonebrewShared   // Kotlin/Native static framework from apple/shared
import FonebrewKit

/// iPad-first shell (porting plan IOS-5). CODE-ONLY: written without Xcode; the first compile is the
/// macOS CI lane. It is deliberately a *proof shell*: it shows that the shared Kotlin domain runs, and
/// states plainly what is not built. It does not pretend to be the Android app.
struct ContentView: View {
    @State private var secretDraft = ""

    private var tiers: [Tier] { PlatformTiers.shared.all }

    var body: some View {
        NavigationSplitView {
            List {
                Section("About this build") {
                    Text("Fonebrew for iOS is an early port. The tiers below say exactly what exists.")
                        .font(.callout)
                    Text("No telemetry. On-device is the default; cloud is opt-in and always marked a watched object. App Store / TestFlight crash reports are collected by Apple, not by this app.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Section("Tiers") {
                    ForEach(tiers, id: \.id) { tier in
                        let badge = TierBadge.make(availability: "\(tier.availability)")
                        VStack(alignment: .leading, spacing: 2) {
                            Text("\(badge.glyph) \(tier.title) — \(badge.word)").font(.headline)
                            Text(tier.reason).font(.caption).foregroundStyle(.secondary)
                        }
                        .accessibilityElement(children: .combine)
                    }
                }
            }
            .navigationTitle(SharedInfo.shared.APP_NAME)
        } detail: {
            List {
                Section("Shared message tree (Kotlin, same sources as Android)") {
                    ForEach(SharedInfo.shared.demoTreeLines(), id: \.self) { Text($0).font(.body.monospaced()) }
                }
                Section("Shared diff") {
                    Text(SharedInfo.shared.diffSummary(old: "a\nb\n", new: "a\nc\n")).font(.body.monospaced())
                }
                Section("API key (stored in Keychain, this device only)") {
                    SecureField("Paste a key", text: $secretDraft)
                    Text("Shown masked: \(SecretMask.mask(secretDraft))").font(.caption)
                    Text(KeychainSecretStore(service: "com.asoc.fonebrew.byok").guarantee).font(.caption).foregroundStyle(.secondary)
                    Text("Saving to the Keychain is not wired in this proof shell.").font(.caption2).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Proof of shared core")
        }
    }
}
