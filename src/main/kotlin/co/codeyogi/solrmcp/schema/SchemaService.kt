package co.codeyogi.solrmcp.schema

import co.codeyogi.solrmcp.config.Json
import org.apache.solr.client.solrj.SolrClient
import org.apache.solr.client.solrj.request.schema.AnalyzerDefinition
import org.apache.solr.client.solrj.request.schema.FieldTypeDefinition
import org.apache.solr.client.solrj.request.schema.SchemaRequest

data class SchemaUpdateResult(val collection: String, val names: List<String>)

/**
 * Schema introspection and additive modification.
 * Port of the reference `SchemaService`. Additions are transactional in Solr:
 * if any command in a batch fails, none are applied.
 */
class SchemaService(private val solr: SolrClient) {

    /** Returns the full schema representation (fields, field types, dynamic/copy fields). */
    fun getSchema(collection: String): Any =
        SchemaRequest().process(solr, collection).schemaRepresentation

    fun addFields(collection: String, fields: List<Map<String, Any?>>): SchemaUpdateResult {
        require(fields.isNotEmpty()) { "fields must not be empty" }
        val names = fields.map { it["name"] as String }
        val updates = fields.map { SchemaRequest.AddField(it) }
        SchemaRequest.MultiUpdate(updates).process(solr, collection)
        return SchemaUpdateResult(collection, names)
    }

    fun addFieldTypes(collection: String, fieldTypes: List<Map<String, Any?>>): SchemaUpdateResult {
        require(fieldTypes.isNotEmpty()) { "fieldTypes must not be empty" }
        val names = fieldTypes.map { it["name"] as String }
        val updates = fieldTypes.map { SchemaRequest.AddFieldType(toFieldTypeDefinition(it)) }
        SchemaRequest.MultiUpdate(updates).process(solr, collection)
        return SchemaUpdateResult(collection, names)
    }

    /**
     * Builds a [FieldTypeDefinition] from the flat Solr add-field-type JSON shape.
     * Scalar attributes (name, class, …) go into the attributes map; analyzer blocks
     * are pulled into typed sub-objects.
     */
    private fun toFieldTypeDefinition(input: Map<String, Any?>): FieldTypeDefinition {
        val attributes = LinkedHashMap(input)
        val analyzer = attributes.remove("analyzer")
        val indexAnalyzer = attributes.remove("indexAnalyzer")
        val queryAnalyzer = attributes.remove("queryAnalyzer")

        return FieldTypeDefinition().apply {
            setAttributes(attributes)
            (analyzer as? Map<*, *>)?.let { setAnalyzer(toAnalyzer(it)) }
            (indexAnalyzer as? Map<*, *>)?.let { setIndexAnalyzer(toAnalyzer(it)) }
            (queryAnalyzer as? Map<*, *>)?.let { setQueryAnalyzer(toAnalyzer(it)) }
        }
    }

    private fun toAnalyzer(map: Map<*, *>): AnalyzerDefinition =
        Json.mapper.convertValue(map, AnalyzerDefinition::class.java)
}
