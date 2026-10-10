package com.fabianreyes.smartprreview.auth

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/** Secret storage keyed by name: the equivalent of VS Code's SecretStorage. */
interface Secrets {
    fun get(key: String): String?

    /** null removes the secret. */
    fun set(key: String, value: String?)
}

/** Secrets in the IDE's PasswordSafe (keychain, KeePass or memory, whatever the user configured). */
object PasswordSafeSecrets : Secrets {
    private fun attributes(key: String) = CredentialAttributes(generateServiceName("Smart PR Review", key))

    override fun get(key: String): String? = PasswordSafe.instance.getPassword(attributes(key))?.takeIf { it.isNotEmpty() }

    override fun set(key: String, value: String?) = PasswordSafe.instance.set(attributes(key), value?.let { Credentials(key, it) })
}
