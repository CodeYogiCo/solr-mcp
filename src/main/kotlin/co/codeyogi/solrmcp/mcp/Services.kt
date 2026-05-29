package co.codeyogi.solrmcp.mcp

import co.codeyogi.solrmcp.collection.CollectionService
import co.codeyogi.solrmcp.indexing.IndexingService
import co.codeyogi.solrmcp.schema.SchemaService
import co.codeyogi.solrmcp.search.SearchService
import org.apache.solr.client.solrj.SolrClient

/** Wires the four services onto one shared [SolrClient] (manual DI, no Spring). */
class Services(val solr: SolrClient) {
    val search = SearchService(solr)
    val indexing = IndexingService(solr)
    val collection = CollectionService(solr)
    val schema = SchemaService(solr)
}
