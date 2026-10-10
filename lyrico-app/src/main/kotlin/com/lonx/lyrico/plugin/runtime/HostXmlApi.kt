package com.lonx.lyrico.plugin.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * XML helpers exposed to plugins as `Platform.xml.*`.
 *
 * Ported from Android, where parsing went through `org.xmlpull.v1.XmlPullParser` (kxml2) and
 * serialisation through `XmlSerializer`. The desktop JDK has neither, and this project already uses
 * JDK XML for the TTML lyric document, so the pull parser is replaced by a namespace-unaware DOM
 * (`DocumentBuilderFactory`). The tree model, the query/mutation semantics and the JSON shapes are
 * unchanged; only [parse] and [serialize] differ.
 *
 * Two deliberate deviations, both confined to how a document is written back out:
 *
 * 1. Serialisation is done by [writeNode] instead of `XmlSerializer`. No XML declaration is emitted
 *    (the Android code never called `startDocument()` either), empty elements are written as
 *    `<tag />`, and `&`, `<`, `>`, `\r` (and in attribute values also `"`, `\n`, `\t`) are escaped
 *    as entity or character references. Plugins only re-parse what they get back, so whitespace and
 *    declaration details are not load-bearing.
 * 2. A `DOCTYPE` declaration is retained but never resolved: external general/parameter entities and
 *    external DTD loading are switched off, so a document carrying a doctype cannot reach the
 *    filesystem or the network through this API.
 * 3. Attribute order changes. Xerces (the JDK's DOM parser) hands back attributes ordered by
 *    qualified name, where Android's XmlPullParser reported them in document order. Both the query
 *    results and the serialised document are consumed by re-parsing, so order is not load-bearing —
 *    but it is not byte-identical to Android either, and [HostXmlApiTest] pins the order that is
 *    actually produced.
 */
object HostXmlApi {

    fun getRootAttributes(xml: String): JsonObject {
        val root = parse(xml)
        return JsonObject(root.attributes.mapValues { JsonPrimitive(it.value) })
    }

    fun findElements(
        xml: String,
        query: JsonObject,
        json: Json
    ): JsonArray {
        val root = parse(xml)
        val tag = query.string("tag")
        val attrs = query.obj("attrs") ?: JsonObject(emptyMap())

        val results = mutableListOf<JsonObject>()

        root.walk { node ->
            if (node.type != XmlNodeType.Element) return@walk
            if (tag.isNotBlank() && node.name != tag) return@walk
            if (!attrsMatch(node, attrs)) return@walk

            results += node.toJsonObject()
        }

        return JsonArray(results)
    }

    fun replaceChildrenByAttr(
        xml: String,
        options: JsonObject
    ): String {
        val root = parse(xml)

        val targetTag = options.string("targetTag")
        val keyAttr = options.string("keyAttr")
        val replacementsObj = options.obj("replacements") ?: JsonObject(emptyMap())
        val rootAttributes = options.obj("rootAttributes") ?: JsonObject(emptyMap())

        if (targetTag.isBlank() || keyAttr.isBlank()) {
            return xml
        }

        rootAttributes.forEach { (name, value) ->
            root.attributes[name] = value.jsonPrimitive.contentOrNull.orEmpty()
        }

        root.walk { node ->
            if (node.type != XmlNodeType.Element) return@walk
            if (node.name != targetTag) return@walk

            val key = node.attributes[keyAttr].orEmpty()
            if (key.isBlank()) return@walk

            val replacement = replacementsObj[key]?.jsonObject ?: return@walk
            val mode = replacement.string("mode").ifBlank { "text" }
            val value = replacement.string("value")

            node.children.clear()

            if (mode == "xml") {
                node.children += parseFragment(value)
            } else {
                node.children += XmlNode.text(value)
            }
        }

        return serialize(root)
    }

    fun removeElements(
        xml: String,
        query: JsonObject
    ): String {
        val root = parse(xml)

        val tag = query.string("tag")
        val attrs = query.obj("attrs") ?: JsonObject(emptyMap())

        fun removeIn(node: XmlNode) {
            val iterator = node.children.iterator()
            while (iterator.hasNext()) {
                val child = iterator.next()

                if (
                    child.type == XmlNodeType.Element &&
                    (tag.isBlank() || child.name == tag) &&
                    attrsMatch(child, attrs)
                ) {
                    iterator.remove()
                } else {
                    removeIn(child)
                }
            }
        }

        removeIn(root)
        collapseEmptyTranslations(root)

        return serialize(root)
    }

    private fun parse(xml: String): XmlNode {
        val document = parseDocument(xml)
        val root = document.documentElement ?: return XmlNode.element("root")
        return toXmlNode(root)
    }

    private fun parseDocument(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        // Secure processing plus no external entity/DTD resolution: plugins supply this XML.
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        factory.isExpandEntityReferences = false
        // Android's XmlPullParser has namespace processing off by default, so qualified names keep
        // their prefix and attribute names are compared verbatim — mirror that.
        factory.isNamespaceAware = false
        return factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }

    private fun toXmlNode(element: Element): XmlNode {
        val node = XmlNode.element(element.nodeName)

        val attributes = element.attributes
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            node.attributes[attribute.nodeName] = attribute.nodeValue.orEmpty()
        }

        var child: Node? = element.firstChild
        while (child != null) {
            when (child.nodeType) {
                Node.ELEMENT_NODE -> node.children += toXmlNode(child as Element)

                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> {
                    val text = child.nodeValue.orEmpty()
                    if (text.isNotEmpty()) {
                        node.children += XmlNode.text(text)
                    }
                }
            }
            child = child.nextSibling
        }

        return node
    }

    private fun parseFragment(fragment: String): List<XmlNode> {
        val wrapped = "<root>$fragment</root>"
        return parse(wrapped).children
    }

    private fun serialize(node: XmlNode): String = buildString { writeNode(node, this) }

    private fun writeNode(node: XmlNode, out: StringBuilder) {
        when (node.type) {
            XmlNodeType.Text -> out.appendEscapedText(node.text.orEmpty())

            XmlNodeType.Element -> {
                val name = node.name.orEmpty()
                out.append('<').append(name)

                node.attributes.forEach { (attributeName, value) ->
                    out.append(' ').append(attributeName).append("=\"")
                    out.appendEscapedAttributeValue(value)
                    out.append('"')
                }

                if (node.children.isEmpty()) {
                    out.append(" />")
                } else {
                    out.append('>')
                    node.children.forEach { child -> writeNode(child, out) }
                    out.append("</").append(name).append('>')
                }
            }
        }
    }

    private fun StringBuilder.appendEscapedText(value: String) {
        for (character in value) {
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '\r' -> append("&#13;")
                else -> append(character)
            }
        }
    }

    private fun StringBuilder.appendEscapedAttributeValue(value: String) {
        for (character in value) {
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\n' -> append("&#10;")
                '\r' -> append("&#13;")
                '\t' -> append("&#9;")
                else -> append(character)
            }
        }
    }

    private fun attrsMatch(node: XmlNode, attrs: JsonObject): Boolean {
        return attrs.all { (name, value) ->
            val expected = value.jsonPrimitive.contentOrNull.orEmpty()
            node.attributes[name] == expected
        }
    }

    private fun collapseEmptyTranslations(node: XmlNode) {
        node.walk { current ->
            if (current.type != XmlNodeType.Element) return@walk
            if (current.name != "translations") return@walk
            if (current.children.none { it.type == XmlNodeType.Element }) {
                current.children.clear()
            }
        }
    }

    private fun XmlNode.walk(block: (XmlNode) -> Unit) {
        block(this)
        children.forEach { it.walk(block) }
    }

    private fun XmlNode.toJsonObject(): JsonObject {
        return buildJsonObject {
            put("tag", JsonPrimitive(name.orEmpty()))
            put(
                "attrs",
                JsonObject(attributes.mapValues { JsonPrimitive(it.value) })
            )
            put("text", JsonPrimitive(textContent()))
            put("innerXml", JsonPrimitive(children.joinToString("") { serialize(it) }))
            put(
                "children",
                JsonArray(
                    children
                        .filter { it.type == XmlNodeType.Element }
                        .map { it.toJsonObject() }
                )
            )
        }
    }

    private fun XmlNode.textContent(): String {
        return when (type) {
            XmlNodeType.Text -> text.orEmpty()
            XmlNodeType.Element -> children.joinToString("") { it.textContent() }
        }
    }

    private fun JsonObject.string(key: String): String {
        return this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    private fun JsonObject.obj(key: String): JsonObject? {
        return this[key] as? JsonObject
    }
}

private enum class XmlNodeType {
    Element,
    Text
}

private data class XmlNode(
    val type: XmlNodeType,
    val name: String? = null,
    val attributes: MutableMap<String, String> = linkedMapOf(),
    val children: MutableList<XmlNode> = mutableListOf(),
    val text: String? = null
) {
    companion object {
        fun element(name: String): XmlNode {
            return XmlNode(
                type = XmlNodeType.Element,
                name = name
            )
        }

        fun text(value: String): XmlNode {
            return XmlNode(
                type = XmlNodeType.Text,
                text = value
            )
        }
    }
}