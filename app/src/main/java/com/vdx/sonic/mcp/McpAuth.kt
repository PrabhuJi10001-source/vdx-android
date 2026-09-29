package com.vdx.sonic.mcp

/**
 * McpAuth — bearer-token check for a future [McpTransport] client.
 * Not used while the in-app HTTP server is removed. Keep so any later
 * transport can require Authorization without inventing a new gate.
 */
class McpAuth(private val token: String) {

    /**
     * Returns true when the request is authorized.
     *
     * @param authorizationHeader the raw `Authorization` header value, or null
     *   if the client sent none.
     */
    fun isAuthorized(authorizationHeader: String?): Boolean {
        if (token.isBlank()) return true // auth disabled
        if (authorizationHeader == null) return false
        val trimmed = authorizationHeader.trim()
        if (!trimmed.startsWith("Bearer ", ignoreCase = true)) return false
        val presented = trimmed.substringAfter(' ').trim()
        return presented == token
    }
}
