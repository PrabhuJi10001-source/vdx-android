package com.vdx.sonic.mcp

/**
 * Optional plug-in for driving [McpToolRegistry] from an external agent later.
 *
 * VDX is a phone app — it does not run an HTTP/MCP server on the device.
 * Default is [Disabled]. Swap in a client (stdio, HTTP client, vendor SDK)
 * without putting a ServerSocket on the phone.
 */
interface McpTransport {
    fun start(registry: McpToolRegistry) {}
    fun stop() {}

    object Disabled : McpTransport
}
