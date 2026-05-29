package co.codeyogi.solrmcp.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper

/**
 * Shared JSON facilities.
 *
 * One Jackson [ObjectMapper] is reused for (a) serializing tool results back to the
 * MCP client and (b) backing the MCP SDK's [McpJsonMapper], which the protocol layer
 * uses to (de)serialize JSON-RPC frames.
 */
object Json {

    val mapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    /** The MCP SDK's mapper abstraction, backed by the same Jackson mapper. */
    val mcp: McpJsonMapper = JacksonMcpJsonMapper(mapper)

    fun toJson(value: Any?): String = mapper.writeValueAsString(value)
}
