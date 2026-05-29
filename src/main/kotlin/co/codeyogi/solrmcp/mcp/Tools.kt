package co.codeyogi.solrmcp.mcp

import co.codeyogi.solrmcp.config.Json
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification
import io.modelcontextprotocol.spec.McpSchema

/**
 * Builds the MCP tool catalog. Unlike Spring AI's `@McpTool` annotation scanning,
 * the MCP Java SDK requires each tool to be registered explicitly: a JSON input
 * schema plus a handler that reads the call arguments, invokes a service, and
 * returns a [McpSchema.CallToolResult].
 */
object Tools {

    fun all(s: Services): List<SyncToolSpecification> = listOf(
        // ---------------- Search ----------------
        tool(
            name = "search",
            description = """
                Search a Solr collection with optional filters, facets, sorting and pagination.
                Solr uses dynamic-field suffixes: _s string, _t text, _i int, _l long, _f float,
                _d double, _dt date, _b boolean.
            """.trimIndent(),
            schema = """
                {"type":"object","properties":{
                  "collection":{"type":"string","description":"Solr collection to query"},
                  "query":{"type":"string","description":"Solr q parameter; defaults to *:*"},
                  "filterQueries":{"type":"array","items":{"type":"string"},"description":"Solr fq parameters"},
                  "facetFields":{"type":"array","items":{"type":"string"}},
                  "sortClauses":{"type":"array","items":{"type":"object",
                    "properties":{"item":{"type":"string"},"order":{"type":"string","enum":["asc","desc"]}}}},
                  "start":{"type":"integer"},
                  "rows":{"type":"integer"}
                },"required":["collection"]}
            """.trimIndent(),
            readOnly = true,
        ) { a ->
            s.search.search(
                collection = a.str("collection"),
                query = a.strOpt("query"),
                filterQueries = a.listStr("filterQueries"),
                facetFields = a.listStr("facetFields"),
                sortClauses = a.listOfMap("sortClauses"),
                start = a.intOpt("start"),
                rows = a.intOpt("rows"),
            )
        },

        // ---------------- Indexing ----------------
        tool(
            name = "index-json-documents",
            description = "Index documents from a JSON string (array of objects) into a Solr collection.",
            schema = payloadSchema("json"),
            idempotent = true,
        ) { a -> s.indexing.indexJson(a.str("collection"), a.str("json")) },

        tool(
            name = "index-csv-documents",
            description = "Index documents from a CSV string (first row is the header) into a Solr collection.",
            schema = payloadSchema("csv"),
            idempotent = true,
        ) { a -> s.indexing.indexCsv(a.str("collection"), a.str("csv")) },

        tool(
            name = "index-xml-documents",
            description = "Index documents from an XML string into a Solr collection.",
            schema = payloadSchema("xml"),
            idempotent = true,
        ) { a -> s.indexing.indexXml(a.str("collection"), a.str("xml")) },

        // ---------------- Collections ----------------
        tool(
            name = "list-collections",
            description = "List all Solr collections in the cluster.",
            schema = """{"type":"object","properties":{}}""",
            readOnly = true,
        ) { s.collection.listCollections() },

        tool(
            name = "get-collection-stats",
            description = "Get index statistics and document counts for a Solr collection.",
            schema = collectionSchema(),
            readOnly = true,
        ) { a -> s.collection.getCollectionStats(a.str("collection")) },

        tool(
            name = "check-health",
            description = "Check the health (ping + doc count) of a Solr collection.",
            schema = collectionSchema(),
            readOnly = true,
        ) { a -> s.collection.checkHealth(a.str("collection")) },

        tool(
            name = "create-collection",
            description = "Create a new Solr collection. configSet defaults to _default, " +
                "numShards and replicationFactor default to 1.",
            schema = """
                {"type":"object","properties":{
                  "name":{"type":"string"},
                  "configSet":{"type":"string"},
                  "numShards":{"type":"integer"},
                  "replicationFactor":{"type":"integer"}
                },"required":["name"]}
            """.trimIndent(),
        ) { a ->
            s.collection.createCollection(
                name = a.str("name"),
                configSet = a.strOpt("configSet"),
                numShards = a.intOpt("numShards"),
                replicationFactor = a.intOpt("replicationFactor"),
            )
        },

        // ---------------- Schema ----------------
        tool(
            name = "get-schema",
            description = "Retrieve the schema (fields, field types, dynamic/copy fields) of a Solr collection.",
            schema = collectionSchema(),
            readOnly = true,
        ) { a -> s.schema.getSchema(a.str("collection")) },

        tool(
            name = "add-fields",
            description = "Add one or more fields to a Solr collection schema (additive only). " +
                "Each field follows the Solr add-field shape: required 'name' and 'type', " +
                "plus optional 'stored','indexed','docValues','multiValued', etc.",
            schema = """
                {"type":"object","properties":{
                  "collection":{"type":"string"},
                  "fields":{"type":"array","items":{"type":"object"}}
                },"required":["collection","fields"]}
            """.trimIndent(),
        ) { a -> s.schema.addFields(a.str("collection"), a.listOfMap("fields") ?: emptyList()) },

        tool(
            name = "add-field-types",
            description = "Add one or more field types to a Solr collection schema. " +
                "Each follows the Solr add-field-type shape: required 'name' and 'class', " +
                "optional 'analyzer'/'indexAnalyzer'/'queryAnalyzer' and class-specific attributes.",
            schema = """
                {"type":"object","properties":{
                  "collection":{"type":"string"},
                  "fieldTypes":{"type":"array","items":{"type":"object"}}
                },"required":["collection","fieldTypes"]}
            """.trimIndent(),
        ) { a -> s.schema.addFieldTypes(a.str("collection"), a.listOfMap("fieldTypes") ?: emptyList()) },
    )

    // --- tool builder ---

    private fun tool(
        name: String,
        description: String,
        schema: String,
        readOnly: Boolean = false,
        idempotent: Boolean = false,
        handler: (Map<String, Any?>) -> Any?,
    ): SyncToolSpecification {
        val tool = McpSchema.Tool.builder()
            .name(name)
            .description(description)
            .inputSchema(Json.mcp, schema)
            .annotations(McpSchema.ToolAnnotations(null, readOnly, false, idempotent, false, false))
            .build()

        return SyncToolSpecification(tool) { _, request ->
            try {
                val result = handler(request.arguments() ?: emptyMap())
                McpSchema.CallToolResult.builder()
                    .addTextContent(Json.toJson(result))
                    .isError(false)
                    .build()
            } catch (e: Exception) {
                McpSchema.CallToolResult.builder()
                    .addTextContent("Error: ${e.message}")
                    .isError(true)
                    .build()
            }
        }
    }

    private fun payloadSchema(field: String): String = """
        {"type":"object","properties":{
          "collection":{"type":"string"},
          "$field":{"type":"string","description":"$field document payload"}
        },"required":["collection","$field"]}
    """.trimIndent()

    private fun collectionSchema(): String =
        """{"type":"object","properties":{"collection":{"type":"string"}},"required":["collection"]}"""
}

// --- argument extraction helpers ---

private fun Map<String, Any?>.str(key: String): String =
    this[key] as? String ?: throw IllegalArgumentException("missing required argument '$key'")

private fun Map<String, Any?>.strOpt(key: String): String? = this[key] as? String

private fun Map<String, Any?>.intOpt(key: String): Int? = (this[key] as? Number)?.toInt()

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.listStr(key: String): List<String>? = this[key] as? List<String>

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.listOfMap(key: String): List<Map<String, Any?>>? =
    this[key] as? List<Map<String, Any?>>
