package co.codeyogi.solrmcp.indexing

import org.apache.solr.client.solrj.SolrClient
import org.apache.solr.common.SolrInputDocument

/**
 * Indexes JSON / CSV / XML payloads into a Solr collection.
 *
 * Batches documents (1000 per batch); on a batch failure it retries each document
 * individually so one bad document doesn't sink the whole batch, then commits once.
 * Port of the reference `IndexingService`.
 */
class IndexingService(
    private val solr: SolrClient,
    private val creator: IndexingDocumentCreator = IndexingDocumentCreator(),
) {
    private val batchSize = 1000

    fun indexJson(collection: String, json: String): String = index(collection, creator.fromJson(json))
    fun indexCsv(collection: String, csv: String): String = index(collection, creator.fromCsv(csv))
    fun indexXml(collection: String, xml: String): String = index(collection, creator.fromXml(xml))

    private fun index(collection: String, documents: List<SolrInputDocument>): String {
        val success = indexDocuments(collection, documents)
        return "Successfully indexed $success of ${documents.size} documents into collection '$collection'"
    }

    fun indexDocuments(collection: String, documents: List<SolrInputDocument>): Int {
        var success = 0
        for (batch in documents.chunked(batchSize)) {
            try {
                solr.add(collection, batch)
                success += batch.size
            } catch (_: Exception) {
                for (doc in batch) {
                    try {
                        solr.add(collection, doc)
                        success++
                    } catch (_: Exception) {
                        // skip the problematic document, keep going
                    }
                }
            }
        }
        solr.commit(collection)
        return success
    }
}
