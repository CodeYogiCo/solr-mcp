package co.codeyogi.solrmcp.config

import org.apache.solr.client.solrj.SolrClient
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient
import java.util.concurrent.TimeUnit

/**
 * Builds the single shared [SolrClient].
 *
 * Mirrors the connection choices proven in the reference Spring implementation:
 * HTTP/1.1 forced (the JDK client's HTTP/2 transport intermittently drops reused
 * connections against Solr/Jetty), 10s connect / 60s idle timeouts.
 */
object SolrClientFactory {

    fun create(rawUrl: String): SolrClient =
        HttpJdkSolrClient.Builder(normalize(rawUrl))
            .withConnectionTimeout(10L, TimeUnit.SECONDS)
            .withIdleTimeout(60L, TimeUnit.SECONDS)
            .useHttp1_1(true)
            .build()

    /** Normalizes a Solr base URL so it ends with `/solr/`. */
    fun normalize(input: String): String {
        var url = if (input.endsWith("/")) input else "$input/"
        if (!url.endsWith("/solr/") && !url.contains("/solr/")) {
            url += "solr/"
        }
        return url
    }
}
