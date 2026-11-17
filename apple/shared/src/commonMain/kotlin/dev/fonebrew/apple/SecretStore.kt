// SPDX-License-Identifier: Apache-2.0
package dev.fonebrew.apple

/**
 * Where a BYOK key lives (CLAUDE.md rule 5 equivalent on iOS; owner ruling OQ-22).
 * iOS actual: Keychain, kSecAttrAccessibleWhenUnlockedThisDeviceOnly, never synchronizable.
 * Keys are never logged and never leave the device except to the provider they belong to.
 */
interface SecretStore {
    /** Human-readable guarantee shown in the UI (the OQ-22 "weaker guarantee shown" rule). */
    val guarantee: String
    fun put(account: String, secret: String)
    fun get(account: String): String?
    fun delete(account: String)
}

/** Test double and fallback for hosts with no keystore. It SAYS it is not durable. */
class InMemorySecretStore : SecretStore {
    private val items = HashMap<String, String>()
    override val guarantee: String = "In memory only: lost when the app closes. Not a keystore."
    override fun put(account: String, secret: String) { items[account] = secret }
    override fun get(account: String): String? = items[account]
    override fun delete(account: String) { items.remove(account) }
}
