package co.codeyogi.solrmcp.collection

import org.apache.solr.client.solrj.SolrClient
import org.apache.solr.client.solrj.request.CollectionAdminRequest
import org.apache.solr.client.solrj.request.LukeRequest
import org.apache.solr.client.solrj.SolrQuery
import java.util.Date

data class IndexStats(val numDocs: Int?, val maxDoc: Int?, val numTerms: Int?)

data class SolrMetrics(
    val collection: String,
    val numFound: Long,
    val index: IndexStats,
    val timestamp: Date = Date(),
)

data class SolrHealthStatus(
    val collection: String,
    val healthy: Boolean,
    val errorMessage: String?,
    val pingTimeMillis: Long?,
    val numFound: Long?,
    val timestamp: Date = Date(),
)

data class CollectionCreationResult(
    val name: String,
    val success: Boolean,
    val message: String,
    val timestamp: Date = Date(),
)

/**
 * Collection management: list, stats, health, create.
 * Port of the reference `CollectionService` (stats trimmed to index + query metrics;
 * the reference's cache/handler mbean metrics are omitted as they were removed in Solr 10).
 */
class CollectionService(private val solr: SolrClient) {

    /** Strips a shard/replica suffix (e.g. `films_shard1_replica_n1` -> `films`). */
    private fun baseName(collection: String): String {
        val idx = collection.indexOf("_shard")
        return if (idx > 0) collection.substring(0, idx) else collection
    }

    fun listCollections(): List<String> {
        val response = CollectionAdminRequest.List().process(solr)
        @Suppress("UNCHECKED_CAST")
        return (response.response["collections"] as? List<String>) ?: emptyList()
    }

    fun getCollectionStats(collection: String): SolrMetrics {
        val name = baseName(collection)
        require(listCollections().contains(name)) { "Collection not found: $name" }

        val luke = LukeRequest().apply { numTerms = 0 }.process(solr, name)
        val index = IndexStats(
            numDocs = luke.numDocs,
            maxDoc = (luke.indexInfo?.get("maxDoc") as? Number)?.toInt(),
            numTerms = (luke.indexInfo?.get("numTerms") as? Number)?.toInt(),
        )
        val numFound = solr.query(name, SolrQuery("*:*").setRows(0)).results.numFound
        return SolrMetrics(name, numFound, index)
    }

    fun checkHealth(collection: String): SolrHealthStatus {
        val name = baseName(collection)
        return try {
            val ping = solr.ping(name)
            val numFound = solr.query(name, SolrQuery("*:*").setRows(0)).results.numFound
            SolrHealthStatus(name, true, null, ping.elapsedTime, numFound)
        } catch (e: Exception) {
            SolrHealthStatus(name, false, e.message, null, null)
        }
    }

    fun createCollection(
        name: String,
        configSet: String?,
        numShards: Int?,
        replicationFactor: Int?,
    ): CollectionCreationResult {
        require(name.isNotBlank()) { "Collection name must not be blank" }
        CollectionAdminRequest
            .createCollection(name, configSet ?: "_default", numShards ?: 1, replicationFactor ?: 1)
            .process(solr)
        return CollectionCreationResult(name, true, "Collection created successfully")
    }
}
