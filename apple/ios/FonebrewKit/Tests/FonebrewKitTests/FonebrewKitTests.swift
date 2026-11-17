// SPDX-License-Identifier: Apache-2.0
import XCTest
@testable import FonebrewKit

final class FonebrewKitTests: XCTestCase {
    func testEveryStateHasGlyphAndWord() {
        for s in ["Available", "Stub", "Unavailable", "garbage"] {
            let b = TierBadge.make(availability: s)
            XCTAssertFalse(b.glyph.isEmpty); XCTAssertFalse(b.word.isEmpty)
        }
    }
    func testStatesAreDistinguishableWithoutColour() {
        let glyphs = Set(["Available", "Stub", "Unavailable"].map { TierBadge.make(availability: $0).glyph })
        XCTAssertEqual(glyphs.count, 3)
    }
    func testMaskNeverRevealsMoreThanLastFour() {
        let m = SecretMask.mask("sk-ant-api03-ABCDEFGH1234")
        XCTAssertTrue(m.hasSuffix("1234"))
        XCTAssertFalse(m.contains("ABCDEFGH"))
        XCTAssertEqual(SecretMask.mask("short"), "•••••")
        XCTAssertEqual(SecretMask.mask(""), "")
    }
}
