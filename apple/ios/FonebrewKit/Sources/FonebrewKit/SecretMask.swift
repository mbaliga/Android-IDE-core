// SPDX-License-Identifier: Apache-2.0
import Foundation

/// Display-only masking for a BYOK key. The full key is never rendered or logged (rule 5).
public enum SecretMask {
    public static func mask(_ secret: String) -> String {
        let n = secret.count
        if n == 0 { return "" }
        if n <= 8 { return String(repeating: "•", count: n) }
        return String(repeating: "•", count: 8) + String(secret.suffix(4))
    }
}
