// SPDX-License-Identifier: Apache-2.0
import Foundation

/// How a tier's availability is drawn. Colour NEVER carries meaning alone: every state has a glyph
/// AND a word, so the UI is legible without colour (CLAUDE.md legibility thesis).
public struct TierBadge: Equatable {
    public let glyph: String
    public let word: String

    /// `availability` is the Kotlin enum's name: "Available" | "Stub" | "Unavailable".
    public static func make(availability: String) -> TierBadge {
        switch availability {
        case "Available": return TierBadge(glyph: "●", word: "Available")
        case "Stub": return TierBadge(glyph: "◐", word: "Not built yet")
        case "Unavailable": return TierBadge(glyph: "○", word: "Not possible on iOS")
        default: return TierBadge(glyph: "?", word: "Unknown")
        }
    }
}
