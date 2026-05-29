package co.codeyogi.solrmcp.transport

import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.json.TypeRef
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpServerSession
import io.modelcontextprotocol.spec.McpServerTransport
import io.modelcontextprotocol.spec.McpServerTransportProvider
import io.modelcontextprotocol.spec.ProtocolVersions
import org.http4k.core.Method.POST
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.routing.RoutingHttpHandler
import org.http4k.routing.RoutingSseHandler
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.routing.sse
import org.http4k.sse.Sse
import org.http4k.sse.SseMessage
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.http4k.routing.sse.bind as sseBind

/**
 * An http4k-native implementation of the MCP HTTP+SSE server transport
 * (protocol revision 2024-11-05). This is a faithful port of the SDK's
 * `HttpServletSseServerTransportProvider`, swapping the Servlet API for
 * http4k's [Sse] / routing primitives so the MCP Java SDK runs entirely on
 * http4k with no servlet container.
 *
 * Two endpoints:
 *  - `GET  {sseEndpoint}`     — opens the SSE stream, creates a session, and
 *                               emits an `endpoint` event telling the client where to POST.
 *  - `POST {messageEndpoint}` — receives a JSON-RPC message for an existing
 *                               session (routed by the `sessionId` query param).
 */
class Http4kSseServerTransportProvider(
    private val jsonMapper: McpJsonMapper,
    private val messageEndpoint: String = "/message",
    private val sseEndpoint: String = "/sse",
) : McpServerTransportProvider {

    private val sessions = ConcurrentHashMap<String, McpServerSession>()

    @Volatile
    private var sessionFactory: McpServerSession.Factory? = null

    @Volatile
    private var closing = false

    override fun setSessionFactory(factory: McpServerSession.Factory) {
        this.sessionFactory = factory
    }

    override fun protocolVersions(): List<String> = listOf(ProtocolVersions.MCP_2024_11_05)

    /** Broadcasts a notification to every connected client. */
    override fun notifyClients(method: String, params: Any?): Mono<Void> {
        if (sessions.isEmpty()) return Mono.empty()
        return Flux.fromIterable(sessions.values)
            .flatMap { session -> session.sendNotification(method, params).onErrorComplete() }
            .then()
    }

    override fun closeGracefully(): Mono<Void> {
        closing = true
        return Flux.fromIterable(sessions.values)
            .flatMap { it.closeGracefully() }
            .then()
            .doOnSuccess { sessions.clear() }
    }

    // ----- http4k handlers consumed by the HTTP layer -----

    /** SSE route: clients connect here to receive server-to-client messages. */
    fun sseRoutes(): RoutingSseHandler = sseEndpoint sseBind sse { conn -> onConnect(conn) }

    /** HTTP route: clients POST JSON-RPC messages here. */
    fun messageRoutes(): RoutingHttpHandler = routes(
        (messageEndpoint bind POST).to(::handleMessage),
    )

    private fun onConnect(sse: Sse) {
        val factory = sessionFactory
        if (factory == null || closing) {
            sse.close()
            return
        }
        val sessionId = UUID.randomUUID().toString()
        val session = factory.create(SessionTransport(sessionId, sse))
        sessions[sessionId] = session
        sse.onClose { sessions.remove(sessionId) }
        // Tell the client where to send messages for this session.
        sse.send(SseMessage.Event(event = "endpoint", data = "$messageEndpoint?sessionId=$sessionId"))
    }

    private fun handleMessage(request: Request): Response {
        if (closing) return jsonError(Status.SERVICE_UNAVAILABLE, "Server is shutting down")
        val sessionId = request.query("sessionId")
            ?: return jsonError(Status.BAD_REQUEST, "Session ID missing in message endpoint")
        val session = sessions[sessionId]
            ?: return jsonError(Status.NOT_FOUND, "Session not found: $sessionId")

        return try {
            val message = McpSchema.deserializeJsonRpcMessage(jsonMapper, request.bodyString())
            // Block until the session has processed the message (mirrors the servlet transport).
            session.handle(message).block()
            Response(Status.ACCEPTED)
        } catch (e: Exception) {
            jsonError(Status.INTERNAL_SERVER_ERROR, e.message ?: "Error processing message")
        }
    }

    private fun jsonError(status: Status, message: String): Response =
        Response(status)
            .header("Content-Type", "application/json")
            .body(jsonMapper.writeValueAsString(mapOf("error" to message)))

    /** Per-session transport: writes server-to-client frames as SSE `message` events. */
    private inner class SessionTransport(
        private val sessionId: String,
        private val sse: Sse,
    ) : McpServerTransport {

        override fun sendMessage(message: McpSchema.JSONRPCMessage): Mono<Void> =
            Mono.fromRunnable {
                synchronized(sse) {
                    sse.send(SseMessage.Event(event = "message", data = jsonMapper.writeValueAsString(message)))
                }
            }

        override fun <T : Any?> unmarshalFrom(data: Any, typeRef: TypeRef<T>): T =
            jsonMapper.convertValue(data, typeRef)

        override fun closeGracefully(): Mono<Void> = Mono.fromRunnable {
            sessions.remove(sessionId)
            sse.close()
        }

        override fun close() {
            sessions.remove(sessionId)
            sse.close()
        }
    }
}
