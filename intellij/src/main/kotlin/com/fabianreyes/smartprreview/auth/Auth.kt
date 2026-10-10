package com.fabianreyes.smartprreview.auth

/** Sign-in state and flow of the repository's provider. */
interface Auth {
    /** Blocking (may read PasswordSafe or the GitHub plugin's accounts): never call on the EDT. */
    fun isSignedIn(): Boolean

    /** Shows the sign-in UI (EDT). force: replace the current credentials, e.g. after a 401. */
    fun signIn(force: Boolean)

    /** Authorization header value for REST calls. Throws HttpError(401) when signed out. Blocking. */
    fun authorization(): String
}
