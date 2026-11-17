// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.fonebrew.apple

import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.Foundation.*
import platform.Security.*

/**
 * Keychain-backed [SecretStore]. CODE-ONLY: written without a Mac; compiled for the first time by the
 * macOS CI lane, and Keychain behaviour on a device/simulator is NEEDS-DEVICE-VALIDATION.
 *
 * Policy (porting plan IOS-4 / ruling OQ-22): generic password items, service = [service],
 * kSecAttrAccessibleWhenUnlockedThisDeviceOnly, never kSecAttrSynchronizable (no iCloud Keychain
 * sync, so a key never leaves this device by Apple's service either). Secrets are never logged.
 */
class KeychainSecretStore(private val service: String = "com.asoc.fonebrew.byok") : SecretStore {
    override val guarantee: String =
        "Stored in the iOS Keychain, readable only while the device is unlocked, this device only (not synced)."

    private fun baseQuery(account: String): CFMutableDictionaryRef {
        val q = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)!!
        CFDictionaryAddValue(q, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(q, kSecAttrService, CFBridgingRetain(service))
        CFDictionaryAddValue(q, kSecAttrAccount, CFBridgingRetain(account))
        return q
    }

    override fun put(account: String, secret: String) {
        delete(account)
        val q = baseQuery(account)
        val data = (secret as NSString).dataUsingEncoding(NSUTF8StringEncoding)
        CFDictionaryAddValue(q, kSecValueData, CFBridgingRetain(data))
        CFDictionaryAddValue(q, kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
        val status = SecItemAdd(q, null)
        check(status == errSecSuccess) { "Keychain add failed (OSStatus $status)" }
    }

    override fun get(account: String): String? = memScoped {
        val q = baseQuery(account)
        CFDictionaryAddValue(q, kSecReturnData, kCFBooleanTrue)
        CFDictionaryAddValue(q, kSecMatchLimit, kSecMatchLimitOne)
        val out = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(q, out.ptr)
        if (status != errSecSuccess) return null
        val data = CFBridgingRelease(out.value) as? NSData ?: return null
        NSString.create(data = data, encoding = NSUTF8StringEncoding) as String?
    }

    override fun delete(account: String) {
        SecItemDelete(baseQuery(account))
    }
}
