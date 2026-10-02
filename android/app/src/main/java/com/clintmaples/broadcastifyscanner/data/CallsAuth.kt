package com.clintmaples.broadcastifyscanner.data

import java.io.IOException

/**
 * One listener session shared by every Calls card. Cookie stays in memory
 * and is refreshed when a poll reports an expired session.
 */
class CallsAuth(
    private val username: String,
    private val password: String,
    private val client: CallsClient,
) {
    private val lock = Any()
    @Volatile private var cookie: String? = null

    fun cookie(): String {
        synchronized(lock) {
            cookie?.let { return it }
            if (username.isBlank() || password.isBlank()) {
                throw IOException("listener login not configured")
            }
            val fresh = client.login(username, password)
            cookie = fresh
            return fresh
        }
    }

    fun invalidate() {
        synchronized(lock) { cookie = null }
    }
}
