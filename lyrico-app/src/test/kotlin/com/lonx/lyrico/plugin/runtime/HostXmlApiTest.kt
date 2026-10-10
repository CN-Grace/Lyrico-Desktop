package com.lonx.lyrico.plugin.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `Platform.xml.*` helpers on desktop, where the implementation no longer uses XmlPullParser.
 *
 * The port replaced Android's pull parser with a namespace-unaware JDK DOM and hand-written
 * serialisation, so the assertions here are about *behaviour*: document-order attributes with their
 * prefixes intact, descendant text, subset attribute matching, escaping on the way out, and — since
 * the XML comes from a plugin — that a doctype cannot be used to read a file. A round-trip through
 * [HostXmlApi.replaceChildrenByAttr] then parse-again is the real check on the serializer, because
 * the plugin's next call parses whatever this one wrote.
 */
class HostXmlApiTest {

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("lyrico-host-xml").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private val lyricDocument = """
        <?xml version="1.0" encoding="utf-8"?>
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://itunes.apple.com/lyric" itunes:author="A" xml:lang="en">
          <head>
            <metadata>
              <translations>
                <translation key="zh-Hans">旧文本</translation>
                <translation key="ja">日本語</translation>
              </translations>
            </metadata>
          </head>
          <body>
            <div>
              <p begin="00:00.000" end="00:01.000">first <span>line</span></p>
              <p begin="00:01.000" end="00:02.000" itunes:key="second">second</p>
            </div>
          </body>
        </tt>
    """.trimIndent()

    @Test
    fun `the root attributes keep their prefixes and their namespace declarations`() {
        val attributes = HostXmlApi.getRootAttributes(lyricDocument)

        // Ordinary attributes keep their prefixes; the JDK's DOM reports every attribute sorted by
        // qualified name rather than in document order (see the deviation note on [HostXmlApi]).
        assertEquals(
            listOf("itunes:author", "xml:lang", "xmlns", "xmlns:itunes"),
            attributes.keys.toList(),
        )
        assertEquals("http://itunes.apple.com/lyric", attributes.getValue("xmlns:itunes").jsonPrimitive.content)
        assertEquals("A", attributes.getValue("itunes:author").jsonPrimitive.content)
        assertEquals("en", attributes.getValue("xml:lang").jsonPrimitive.content)

        // The rule, isolated: the document order below is deliberately not the sorted order.
        assertEquals(
            listOf("a", "b", "d", "x:c", "xmlns", "xmlns:x"),
            HostXmlApi.getRootAttributes(
                """<root a="1" xmlns:x="u" b="2" x:c="3" xmlns="u2" d="4"/>"""
            ).keys.toList(),
        )
        assertEquals(
            listOf("a", "b"),
            HostXmlApi.getRootAttributes("""<root b="1" a="2"/>""").keys.toList(),
        )
    }

    @Test
    fun `a root without attributes answers with an empty object rather than failing`() {
        assertTrue(HostXmlApi.getRootAttributes("<root/>").isEmpty())
        // A declaration alone is not a document; the Android original also fell back to a stub root.
        assertTrue(HostXmlApi.getRootAttributes("<root><a/></root>").isEmpty())
    }

    @Test
    fun `findElements walks the whole document and reports tag, attributes, text and inner xml`() {
        val found = HostXmlApi.findElements(lyricDocument, query(tag = "p"), json)

        assertEquals(2, found.size)
        val first = found[0].jsonObject
        assertEquals("p", first.getValue("tag").jsonPrimitive.content)
        assertEquals("00:00.000", first.getValue("attrs").jsonObject.getValue("begin").jsonPrimitive.content)
        // Descendant text is concatenated, matching the Android implementation.
        assertEquals("first line", first.getValue("text").jsonPrimitive.content)
        assertEquals("first <span>line</span>", first.getValue("innerXml").jsonPrimitive.content)
        assertEquals(1, first.getValue("children").let { it.toString().split("\"tag\"").size - 1 })
        assertEquals("second", found[1].jsonObject.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun `findElements matches a subset of attributes, not the whole attribute set`() {
        val found = HostXmlApi.findElements(
            lyricDocument,
            query(tag = "p", attrs = mapOf("itunes:key" to "second")),
            json,
        )

        assertEquals(1, found.size)
        assertEquals("second", found[0].jsonObject.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun `findElements with a blank tag searches every element`() {
        val found = HostXmlApi.findElements(lyricDocument, query(tag = ""), json)

        val tags = found.map { it.jsonObject.getValue("tag").jsonPrimitive.content }
        assertTrue("tt" in tags && "translations" in tags && "span" in tags, tags.toString())
    }

    @Test
    fun `replacing the children of an element by key writes escaped text`() {
        val rewritten = HostXmlApi.replaceChildrenByAttr(
            lyricDocument,
            buildJsonObject {
                put("targetTag", "translation")
                put("keyAttr", "key")
                put("replacements", buildJsonObject {
                    put("zh-Hans", buildJsonObject {
                        put("mode", "text")
                        put("value", "a < b & c > d \"quoted\"")
                    })
                })
            },
        )

        assertTrue(
            rewritten.contains("""<translation key="zh-Hans">a &lt; b &amp; c &gt; d "quoted"</translation>"""),
            rewritten,
        )
        // The other translation and the rest of the document are untouched.
        assertTrue(rewritten.contains("""<translation key="ja">日本語</translation>"""), rewritten)
        assertEquals("a < b & c > d \"quoted\"", reparseText(rewritten, tag = "translation", key = "zh-Hans"))
    }

    @Test
    fun `replacing by key in xml mode installs real child elements`() {
        val rewritten = HostXmlApi.replaceChildrenByAttr(
            lyricDocument,
            buildJsonObject {
                put("targetTag", "translation")
                put("keyAttr", "key")
                put("replacements", buildJsonObject {
                    put("zh-Hans", buildJsonObject {
                        put("mode", "xml")
                        put("value", """<span t="1">新</span><span t="2">文本</span>""")
                    })
                })
            },
        )

        val replaced = HostXmlApi.findElements(rewritten, query(tag = "translation", attrs = mapOf("key" to "zh-Hans")), json)
        val children = replaced.single().jsonObject.getValue("children").toString()
        assertEquals(2, children.split("\"tag\"").size - 1)
        assertTrue(children.contains("span"), children)
        // Parsing the serializer's output a second time is what a plugin would do next.
        assertEquals("新文本", reparseText(rewritten, tag = "translation", key = "zh-Hans"))
    }

    @Test
    fun `replacing by key also updates the root attributes`() {
        val rewritten = HostXmlApi.replaceChildrenByAttr(
            lyricDocument,
            buildJsonObject {
                put("targetTag", "translation")
                put("keyAttr", "key")
                put("rootAttributes", buildJsonObject { put("xml:lang", "zh-Hans") })
                put("replacements", JsonObject(emptyMap()))
            },
        )

        assertEquals("zh-Hans", HostXmlApi.getRootAttributes(rewritten).getValue("xml:lang").jsonPrimitive.content)
    }

    @Test
    fun `replacing without a target tag or key attribute returns the document unchanged`() {
        val options = buildJsonObject {
            put("targetTag", "")
            put("keyAttr", "")
            put("replacements", buildJsonObject { put("zh-Hans", buildJsonObject { put("value", "x") }) })
        }

        assertEquals(lyricDocument, HostXmlApi.replaceChildrenByAttr(lyricDocument, options))
    }

    @Test
    fun `an element without the key attribute is left alone`() {
        val rewritten = HostXmlApi.replaceChildrenByAttr(
            lyricDocument,
            buildJsonObject {
                put("targetTag", "translation")
                put("keyAttr", "missing-id")
                put("replacements", buildJsonObject { put("zh-Hans", buildJsonObject { put("value", "x") }) })
            },
        )

        // Unlike the blank-options guard above, this path still rewrites the document — so compare
        // meaning, not bytes: every translation and the root attributes must be untouched.
        assertTrue(rewritten.contains("""<translation key="zh-Hans">旧文本</translation>"""), rewritten)
        assertTrue(rewritten.contains("""<translation key="ja">日本語</translation>"""), rewritten)
        assertEquals(
            HostXmlApi.getRootAttributes(lyricDocument),
            HostXmlApi.getRootAttributes(rewritten),
        )
        // The declaration is not part of the root element, so a round trip drops it.
        assertFalse(rewritten.contains("<?xml"), rewritten)
    }

    @Test
    fun `removeElements deletes every match and keeps the rest`() {
        val rewritten = HostXmlApi.removeElements(
            lyricDocument,
            query(tag = "translation", attrs = mapOf("key" to "ja")),
        )

        assertFalse(rewritten.contains("日本語"), rewritten)
        assertTrue(rewritten.contains("旧文本"), rewritten)
        assertEquals(0, HostXmlApi.findElements(rewritten, query(tag = "translation", attrs = mapOf("key" to "ja")), json).size)
    }

    @Test
    fun `removing the only translation collapses the empty translations element`() {
        val rewritten = HostXmlApi.removeElements(lyricDocument, query(tag = "translation"))

        // No `translations` entry may survive with stray whitespace where the elements used to be.
        assertTrue(rewritten.contains("<translations />"), rewritten)
        assertFalse(rewritten.contains("旧文本"), rewritten)
    }

    @Test
    fun `removing with a blank tag and no attributes removes every element`() {
        val rewritten = HostXmlApi.removeElements("<root><a><b/></a><c/></root>", JsonObject(emptyMap()))

        assertEquals("<root />", rewritten)
    }

    @Test
    fun `text and attribute values survive a serialize and parse round trip`() {
        val text = "line 1\r\nline 2\t<&>\"'"
        val rewritten = HostXmlApi.replaceChildrenByAttr(
            "<root><item id=\"1\">old</item></root>",
            buildJsonObject {
                put("targetTag", "item")
                put("keyAttr", "id")
                put("replacements", buildJsonObject {
                    put("1", buildJsonObject { put("mode", "text"); put("value", text) })
                })
            },
        )

        // The raw CR must not be written literally: it would be normalised away by the next parse.
        assertFalse(rewritten.contains("\r"), rewritten)
        assertTrue(rewritten.contains("&#13;"), rewritten)
        assertEquals(text, reparseText(rewritten, tag = "item", key = "1", attr = "id"))
    }

    @Test
    fun `a cdata section is read as text and a comment is not part of the tree`() {
        val found = HostXmlApi.findElements(
            "<root><!-- a comment --><a><![CDATA[raw <&> text]]></a></root>",
            query(tag = "a"),
            json,
        )

        assertEquals("raw <&> text", found.single().jsonObject.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun `an empty element serializes as a self closing tag`() {
        val rewritten = HostXmlApi.replaceChildrenByAttr(
            "<root><item id=\"1\">text</item></root>",
            buildJsonObject {
                put("targetTag", "item")
                put("keyAttr", "id")
                put("replacements", buildJsonObject {
                    put("1", buildJsonObject { put("mode", "xml"); put("value", "") })
                })
            },
        )

        assertEquals("<root><item id=\"1\" /></root>", rewritten)
    }

    @Test
    fun `a doctype is accepted but an external entity cannot read a file`() {
        val secret = File(tempDir, "secret.txt")
        secret.writeText("TOP-SECRET-CONTENT")
        val uri = secret.toURI().toString().replace("\\", "/")
        val xml = """<!DOCTYPE root [<!ENTITY xxe SYSTEM "$uri">]><root><a>&xxe;</a></root>"""

        // Either the parser refuses the entity outright or it stays unexpanded; both are fine, the
        // file content leaking into the tree is not.
        val rendered = runCatching { HostXmlApi.findElements(xml, query(tag = "a"), json).toString() }
            .getOrElse { "" }

        assertFalse(rendered.contains("TOP-SECRET-CONTENT"), rendered)

        // A doctype without an internal subset still parses, so doctype-bearing plugin XML works.
        assertEquals(
            "v",
            HostXmlApi.getRootAttributes("<!DOCTYPE root><root k=\"v\"/>").getValue("k").jsonPrimitive.content,
        )
    }

    private fun query(tag: String, attrs: Map<String, String> = emptyMap()): JsonObject = buildJsonObject {
        put("tag", tag)
        put("attrs", JsonObject(attrs.mapValues { JsonPrimitive(it.value) }))
    }

    /**
     * Reads the concatenated text of the [tag] element carrying `[attr]="[key]"`, after a real re-parse
     * — the same thing a plugin does with whatever the previous call returned.
     */
    private fun reparseText(xml: String, tag: String, key: String, attr: String = "key"): String {
        val found = HostXmlApi.findElements(xml, query(tag = tag, attrs = mapOf(attr to key)), json)
        return found.single().jsonObject.getValue("text").jsonPrimitive.content
    }
}
