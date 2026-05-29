package co.codeyogi.solrmcp

import co.codeyogi.solrmcp.config.Json
import co.codeyogi.solrmcp.config.SolrClientFactory
import co.codeyogi.solrmcp.http.HttpServer
import co.codeyogi.solrmcp.mcp.Services
import co.codeyogi.solrmcp.mcp.SolrMcpServer
import co.codeyogi.solrmcp.transport.Http4kSseServerTransportProvider
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import java.util.concurrent.CountDownLatch

/**
 * Entry point. Selects the transport from the `PROFILES` env var (default `stdio`):
 *  - `stdio` — MCP SDK's stdio transport (System.in/out); for Claude Desktop.
 *  - `http`  — http4k + Jetty serving the SSE transport; for MCP Inspector / remote.
 *
 * This is the manual-wiring equivalent of Spring's auto-configuration.
 */
fun main() {
    val env = System.getenv()
    val solrUrl = env["SOLR_URL"] ?: "http://localhost:8983/solr/"
    val profile = (env["PROFILES"] ?: "stdio").trim().lowercase()

    val services = Services(SolrClientFactory.create(solrUrl))

    when (profile) {
        "http" -> startHttp(services, env)
        else -> startStdio(services)
    }
}

private fun startStdio(services: Services) {
    val transport = StdioServerTransportProvider(Json.mcp)
    val server = SolrMcpServer.build(transport, services)
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    // The stdio transport reads System.in on its own threads; keep main alive.
    CountDownLatch(1).await()
}

private fun startHttp(services: Services, env: Map<String, String>) {
    val transport = Http4kSseServerTransportProvider(Json.mcp)
    val server = SolrMcpServer.build(transport, services)

    val port = (env["PORT"] ?: env["SERVER_PORT"] ?: "8080").toInt()
    val issuer = env["OAUTH2_ISSUER_URI"]
    val origins = (env["MCP_CORS_ALLOWED_ORIGINS"] ?: "http://localhost:6274,http://127.0.0.1:6274")
        .split(",").map(String::trim).filter(String::isNotEmpty)

    val http = HttpServer.start(transport, port, issuer, origins)
    Runtime.getRuntime().addShutdownHook(Thread { http.stop(); server.close() })
    System.err.println("Solr MCP server (HTTP) on :$port — SSE GET /sse, messages POST /message")
}
