package co.codeyogi.solrmcp

import co.codeyogi.solrmcp.config.SolrClientFactory
import co.codeyogi.solrmcp.mcp.Services
import co.codeyogi.solrmcp.mcp.Tools
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.ComposeContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.File
import java.time.Duration

/**
 * Functional tests: the services and the MCP tool layer run against a real Solr
 * brought up via docker compose (Testcontainers [ComposeContainer]).
 */
@Testcontainers
class SolrMcpFunctionalTest {

    companion object {
        @Container
        @JvmStatic
        val solr: ComposeContainer = ComposeContainer(File("src/test/resources/solr-compose.yaml"))
            .withLocalCompose(true)
            .withExposedService(
                "solr", 8983,
                Wait.forHttp("/solr/admin/collections?action=LIST")
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)),
            )

        private lateinit var services: Services

        @BeforeAll
        @JvmStatic
        fun setUp() {
            val host = solr.getServiceHost("solr", 8983)
            val port = solr.getServicePort("solr", 8983)
            services = Services(SolrClientFactory.create("http://$host:$port/solr/"))
        }
    }

    @Test
    fun `full flow through services - create, add field, index, search, facet, stats, health`() {
        val collection = "films"

        // create
        val created = services.collection.createCollection(collection, null, 1, 1)
        assertTrue(created.success)
        assertTrue(services.collection.listCollections().contains(collection))

        // schema: add an explicit string field for faceting
        services.schema.addFields(
            collection,
            listOf(mapOf("name" to "genre_s", "type" to "string", "stored" to true, "indexed" to true, "docValues" to true)),
        )

        // index JSON
        val json = """
            [{"id":"1","name":"The Matrix","genre_s":"scifi"},
             {"id":"2","name":"Inception","genre_s":"scifi"},
             {"id":"3","name":"Amelie","genre_s":"romance"}]
        """.trimIndent()
        val result = services.indexing.indexJson(collection, json)
        assertTrue(result.contains("indexed 3"), result)

        // search by text
        val matrix = services.search.search(collection, "name:Matrix", null, null, null, 0, 10)
        assertEquals(1L, matrix.numFound)

        // facet over genre_s
        val faceted = services.search.search(collection, "*:*", null, listOf("genre_s"), null, 0, 0)
        assertEquals(2L, faceted.facets["genre_s"]?.get("scifi"))
        assertEquals(1L, faceted.facets["genre_s"]?.get("romance"))

        // stats + health
        assertEquals(3L, services.collection.getCollectionStats(collection).numFound)
        val health = services.collection.checkHealth(collection)
        assertTrue(health.healthy)
        assertEquals(3L, health.numFound)
    }

    @Test
    fun `csv indexing through services`() {
        val collection = "csvcoll"
        services.collection.createCollection(collection, null, 1, 1)

        val csv = "id,title\n10,Alpha\n11,Beta\n"
        val result = services.indexing.indexCsv(collection, csv)
        assertTrue(result.contains("indexed 2"), result)

        val all = services.search.search(collection, "*:*", null, null, null, 0, 10)
        assertEquals(2L, all.numFound)
    }

    @Test
    fun `mcp tool layer end-to-end via SyncToolSpecification handlers`() {
        val collection = "toollayer"
        val tools = Tools.all(services).associateBy { it.tool().name() }

        // create-collection through the tool handler
        val create = tools.getValue("create-collection").callHandler()
            .apply(null, McpSchema.CallToolRequest("create-collection", mapOf("name" to collection)))
        assertFalse(create.isError, "create-collection returned error: ${textOf(create)}")

        // index-json-documents through the tool handler
        val index = tools.getValue("index-json-documents").callHandler().apply(
            null,
            McpSchema.CallToolRequest(
                "index-json-documents",
                mapOf("collection" to collection, "json" to """[{"id":"1","name":"Solr"}]"""),
            ),
        )
        assertFalse(index.isError, "index returned error: ${textOf(index)}")

        // list-collections through the tool handler
        val list = tools.getValue("list-collections").callHandler()
            .apply(null, McpSchema.CallToolRequest("list-collections", emptyMap()))
        assertFalse(list.isError)
        assertTrue(textOf(list).contains(collection))
    }

    private fun textOf(result: McpSchema.CallToolResult): String =
        result.content().filterIsInstance<McpSchema.TextContent>().joinToString { it.text() }
}
