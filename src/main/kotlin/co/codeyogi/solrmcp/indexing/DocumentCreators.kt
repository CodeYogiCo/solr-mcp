package co.codeyogi.solrmcp.indexing

import co.codeyogi.solrmcp.config.Json
import com.fasterxml.jackson.databind.JsonNode
import org.apache.commons.csv.CSVFormat
import org.apache.solr.common.SolrInputDocument
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Raised when an input document cannot be parsed into Solr documents. */
class DocumentProcessingException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Sanitizes arbitrary field names into Solr-compatible identifiers. */
object FieldNameSanitizer {
    private val invalid = Regex("[\\W]")
    private val edgeUnderscores = Regex("(^_+)|(_+$)")
    private val multiUnderscores = Regex("_{2,}")

    fun sanitize(name: String): String {
        var s = invalid.replace(name.lowercase(), "_")
        s = edgeUnderscores.replace(s, "")
        s = multiUnderscores.replace(s, "_")
        if (s.isEmpty()) return "field"
        if (s.first().isDigit()) s = "field_$s"
        return s
    }
}

private const val MAX_INPUT_BYTES = 10 * 1024 * 1024

/** Orchestrates format-specific parsing (strategy pattern, like the reference). */
class IndexingDocumentCreator {

    fun fromJson(json: String): List<SolrInputDocument> {
        requireSize(json)
        val root = try {
            Json.mapper.readTree(json)
        } catch (e: Exception) {
            throw DocumentProcessingException("Failed to parse JSON document", e)
        }
        val nodes = if (root.isArray) root.toList() else listOf(root)
        return nodes.map { node ->
            SolrInputDocument().also { addFlat(it, node, "") }
        }
    }

    fun fromCsv(csv: String): List<SolrInputDocument> {
        requireSize(csv)
        val format = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setTrim(true)
            .build()
        return try {
            format.parse(StringReader(csv)).use { parser ->
                val headers = parser.headerNames
                parser.map { record ->
                    SolrInputDocument().also { doc ->
                        for (header in headers) {
                            val value = record.get(header)
                            if (!value.isNullOrBlank()) {
                                doc.addField(FieldNameSanitizer.sanitize(header), value)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            throw DocumentProcessingException("Failed to parse CSV document", e)
        }
    }

    fun fromXml(xml: String): List<SolrInputDocument> {
        requireSize(xml)
        val doc = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            factory.newDocumentBuilder().parse(xml.byteInputStream())
        } catch (e: Exception) {
            throw DocumentProcessingException("Failed to parse XML document", e)
        }

        val root = doc.documentElement
        val childElements = root.childNodes.toElementList()
        val docElements = childElements.filter { it.tagName.lowercase() in setOf("doc", "item", "record") }
        val sources = docElements.ifEmpty { listOf(root) }
        return sources.map { element ->
            SolrInputDocument().also { addXmlFlat(it, element, "") }
        }
    }

    private fun requireSize(input: String) {
        if (input.toByteArray(Charsets.UTF_8).size > MAX_INPUT_BYTES) {
            throw DocumentProcessingException("Input too large: exceeds maximum size of $MAX_INPUT_BYTES bytes")
        }
    }

    // --- JSON flattening ---

    private fun addFlat(doc: SolrInputDocument, node: JsonNode, prefix: String) {
        node.fields().forEach { (key, value) ->
            processValue(doc, value, FieldNameSanitizer.sanitize(prefix + key))
        }
    }

    private fun processValue(doc: SolrInputDocument, value: JsonNode, fieldName: String) {
        when {
            value.isNull -> Unit
            value.isArray -> {
                val values = value.filterNot { it.isObject }.map { convert(it) }
                if (values.isNotEmpty()) doc.addField(fieldName, values)
            }
            value.isObject -> addFlat(doc, value, "${fieldName}_")
            else -> doc.addField(fieldName, convert(value))
        }
    }

    private fun convert(value: JsonNode): Any = when {
        value.isBoolean -> value.asBoolean()
        value.isLong -> value.asLong()
        value.isDouble -> value.asDouble()
        value.isInt -> value.asInt()
        else -> value.asText()
    }

    // --- XML flattening ---

    private fun addXmlFlat(doc: SolrInputDocument, element: Element, prefix: String) {
        val attributes = element.attributes
        for (i in 0 until attributes.length) {
            val attr = attributes.item(i)
            doc.addField(FieldNameSanitizer.sanitize("${prefix}${attr.nodeName}_attr"), attr.nodeValue)
        }
        for (child in element.childNodes.toElementList()) {
            val childElements = child.childNodes.toElementList()
            if (childElements.isEmpty()) {
                val text = child.textContent?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    doc.addField(FieldNameSanitizer.sanitize(prefix + child.tagName), text)
                }
            } else {
                addXmlFlat(doc, child, "${prefix}${child.tagName}_")
            }
        }
    }
}

private fun org.w3c.dom.NodeList.toElementList(): List<Element> =
    (0 until length).mapNotNull { item(it).takeIf { n -> n.nodeType == Node.ELEMENT_NODE } as? Element }
