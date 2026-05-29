package co.codeyogi.solrmcp.search

import org.apache.solr.client.solrj.SolrClient
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.common.params.FacetParams

/** Structured search result, serialized to JSON for the MCP client. */
data class SearchResponse(
    val numFound: Long,
    val start: Long,
    val maxScore: Float?,
    val documents: List<Map<String, Any?>>,
    val facets: Map<String, Map<String, Long>>,
)

/**
 * Full-text search with optional filtering, faceting, sorting and pagination.
 * Port of the reference `SearchService`.
 */
class SearchService(private val solr: SolrClient) {

    fun search(
        collection: String,
        query: String?,
        filterQueries: List<String>?,
        facetFields: List<String>?,
        sortClauses: List<Map<String, Any?>>?,
        start: Int?,
        rows: Int?,
    ): SearchResponse {
        val q = SolrQuery(if (query.isNullOrBlank()) "*:*" else query)

        if (!filterQueries.isNullOrEmpty()) {
            q.setFilterQueries(*filterQueries.toTypedArray())
        }

        if (!facetFields.isNullOrEmpty()) {
            q.setFacet(true)
            q.addFacetField(*facetFields.toTypedArray())
            q.facetMinCount = 1
            q.setFacetSort(FacetParams.FACET_SORT_COUNT)
        }

        sortClauses?.forEach { clause ->
            val field = clause["item"] as? String ?: return@forEach
            val order = SolrQuery.ORDER.valueOf((clause["order"] as? String ?: "asc").lowercase())
            q.addSort(field, order)
        }

        start?.let { q.start = it }
        rows?.let { q.rows = it }

        val response = solr.query(collection, q)
        val results = response.results

        val docs = results.map { doc ->
            doc.fieldNames.associateWith { name -> doc.getFieldValue(name) }
        }

        val facets = response.facetFields.orEmpty().associate { facetField ->
            facetField.name to facetField.values.orEmpty().associate { it.name to it.count }
        }

        return SearchResponse(results.numFound, results.start, results.maxScore, docs, facets)
    }
}
