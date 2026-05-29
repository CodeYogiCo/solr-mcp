package co.codeyogi.solrmcp.mcp

import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpSyncServer
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpServerTransportProvider

/**
 * Assembles the MCP server (protocol core) from a transport provider and the
 * Solr-backed tool catalog. The same server definition serves both transports —
 * only the [McpServerTransportProvider] differs (STDIO vs the http4k SSE bridge).
 */
object SolrMcpServer {

    fun build(transport: McpServerTransportProvider, services: Services): McpSyncServer =
        McpServer.sync(transport)
            .serverInfo("solr-mcp", "0.1.0")
            .instructions("Tools to search, index, and manage Apache Solr collections via MCP.")
            .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
            .tools(Tools.all(services))
            .build()
}
