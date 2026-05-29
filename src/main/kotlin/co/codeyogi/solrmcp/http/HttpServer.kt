package co.codeyogi.solrmcp.http

import co.codeyogi.solrmcp.transport.Http4kSseServerTransportProvider
import org.http4k.core.HttpHandler
import org.http4k.core.Method.GET
import org.http4k.core.PolyHandler
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.server.Http4kServer
import org.http4k.server.Jetty
import org.http4k.server.asServer

/**
 * Hosts the MCP SSE transport on http4k + Jetty.
 *
 * A [PolyHandler] serves the HTTP routes (health + the JSON-RPC message endpoint,
 * wrapped in CORS + JWT filters) alongside the long-lived SSE stream on the same port.
 */
object HttpServer {

    fun start(
        transport: Http4kSseServerTransportProvider,
        port: Int,
        issuerUri: String?,
        allowedOrigins: List<String>,
    ): Http4kServer {
        val health = routes(("/health" bind GET).to { Response(Status.OK).body("OK") })

        val httpHandler: HttpHandler = Security.cors(allowedOrigins)
            .then(Security.jwtAuth(issuerUri))
            .then(routes(health, transport.messageRoutes()))

        val poly = PolyHandler(http = httpHandler, sse = transport.sseRoutes())
        return poly.asServer(Jetty(port)).start()
    }
}
