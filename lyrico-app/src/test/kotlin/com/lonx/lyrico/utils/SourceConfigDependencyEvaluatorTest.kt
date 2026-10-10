package com.lonx.lyrico.utils

import com.lonx.lyrico.data.model.plugin.PluginConfigDependency
import com.lonx.lyrico.data.model.plugin.PluginManifest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `isSatisfied` decides whether a plugin's config-dependent field is shown, and a plugin author
 * expresses that condition as nested `match` / `and` / `or` / `not` JSON in the manifest. A wrong
 * answer here is invisible until a user cannot reach a field they need, so the whole truth table is
 * pinned, including the two empty-collection edges that fall out of `all`/`any` semantics.
 */
class SourceConfigDependencyEvaluatorTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `a plugin with no dependency always satisfies it`() {
        val absent: PluginConfigDependency? = null

        assertEquals(true, absent.isSatisfied(emptyMap()))
        assertEquals(true, absent.isSatisfied(mapOf("region" to "cn")))
    }

    @Test
    fun `match compares the raw string the user saved`() {
        val match = PluginConfigDependency.Match(key = "region", value = "cn")

        assertEquals(true, match.isSatisfied(mapOf("region" to "cn")))
        assertEquals(false, match.isSatisfied(mapOf("region" to "global")))
        assertEquals(false, match.isSatisfied(emptyMap()), "an unset key does not match a value")
        assertEquals(false, match.isSatisfied(mapOf("Region" to "cn")), "keys are case-sensitive")
        assertEquals(
            false,
            match.isSatisfied(mapOf("region" to " cn ")),
            "values are compared verbatim, not trimmed",
        )
    }

    @Test
    fun `and requires every condition and or requires any one of them`() {
        val values = mapOf("region" to "cn", "quality" to "lossless")
        val and = PluginConfigDependency.And(
            listOf(
                PluginConfigDependency.Match("region", "cn"),
                PluginConfigDependency.Match("quality", "lossless"),
            ),
        )
        val or = PluginConfigDependency.Or(
            listOf(
                PluginConfigDependency.Match("region", "jp"),
                PluginConfigDependency.Match("quality", "lossless"),
            ),
        )

        assertEquals(true, and.isSatisfied(values))
        assertEquals(false, and.isSatisfied(mapOf("region" to "cn")))
        assertEquals(true, or.isSatisfied(values), "one satisfied branch is enough")
        assertEquals(false, or.isSatisfied(mapOf("region" to "cn")))
    }

    @Test
    fun `empty and and empty or follow all and any semantics`() {
        assertEquals(
            true,
            PluginConfigDependency.And(emptyList()).isSatisfied(emptyMap()),
            "all of nothing holds",
        )
        assertEquals(
            false,
            PluginConfigDependency.Or(emptyList()).isSatisfied(emptyMap()),
            "any of nothing does not",
        )
    }

    @Test
    fun `not inverts its inner condition`() {
        val not = PluginConfigDependency.Not(PluginConfigDependency.Match("region", "cn"))

        assertEquals(false, not.isSatisfied(mapOf("region" to "cn")))
        assertEquals(true, not.isSatisfied(mapOf("region" to "jp")))
        assertEquals(true, not.isSatisfied(emptyMap()), "an unset key is not equal to the value")
    }

    @Test
    fun `nested conditions evaluate through the whole tree`() {
        // (region == cn and (quality == lossless or quality == high)) and not preview
        val dependency = PluginConfigDependency.And(
            listOf(
                PluginConfigDependency.Match("region", "cn"),
                PluginConfigDependency.Or(
                    listOf(
                        PluginConfigDependency.Match("quality", "lossless"),
                        PluginConfigDependency.Match("quality", "high"),
                    ),
                ),
                PluginConfigDependency.Not(PluginConfigDependency.Match("preview", "true")),
            ),
        )

        assertEquals(true, dependency.isSatisfied(mapOf("region" to "cn", "quality" to "high")))
        assertEquals(true, dependency.isSatisfied(mapOf("region" to "cn", "quality" to "lossless")))
        assertEquals(
            false,
            dependency.isSatisfied(mapOf("region" to "cn", "quality" to "high", "preview" to "true")),
        )
        assertEquals(false, dependency.isSatisfied(mapOf("region" to "jp", "quality" to "high")))
        assertEquals(false, dependency.isSatisfied(mapOf("region" to "cn", "quality" to "low")))
        assertEquals(false, dependency.isSatisfied(emptyMap()))
    }

    @Test
    fun `the JSON a manifest carries deserializes into the condition it describes`() {
        // The documented manifest shape: each operator wraps its payload in a named property.
        val raw = """
            {
              "and": {
                "conditions": [
                  { "match": { "key": "region", "value": "cn" } },
                  { "not": { "condition": { "match": { "key": "pureMusic", "value": "true" } } } },
                  { "or": {
                    "conditions": [
                      { "match": { "key": "quality", "value": "lossless" } },
                      { "match": { "key": "quality", "value": "high" } }
                    ]
                  } }
                ]
              }
            }
        """.trimIndent()

        val dependency = json.decodeFromString(PluginConfigDependency.serializer(), raw)

        assertEquals(true, dependency.isSatisfied(mapOf("region" to "cn", "quality" to "high")))
        assertEquals(
            false,
            dependency.isSatisfied(mapOf("region" to "cn", "quality" to "high", "pureMusic" to "true")),
        )
        assertEquals(false, dependency.isSatisfied(mapOf("region" to "jp", "quality" to "high")))
    }

    @Test
    fun `a config field's dependency decodes out of a full manifest`() {
        val manifestJson = """
            {
              "id": "net.example.source",
              "name": "Example",
              "versionCode": 1,
              "versionName": "1.0.0",
              "apiVersion": 5,
              "minHostApiVersion": 4,
              "configFields": [
                {
                  "key": "token_url",
                  "title": "Token endpoint",
                  "type": "text",
                  "dependency": {
                    "and": {
                      "conditions": [
                        { "match": { "key": "auth_type", "value": "oauth" } },
                        { "match": { "key": "custom_server", "value": "true" } }
                      ]
                    }
                  }
                },
                {
                  "key": "custom_host",
                  "title": "Custom host",
                  "type": "text",
                  "dependency": {
                    "not": { "condition": { "match": { "key": "server_mode", "value": "auto" } } }
                  }
                }
              ]
            }
        """.trimIndent()

        val manifest = json.decodeFromString(PluginManifest.serializer(), manifestJson)
        val tokenUrl = manifest.configFields.single { it.key == "token_url" }
        val customHost = manifest.configFields.single { it.key == "custom_host" }

        assertEquals(
            true,
            tokenUrl.dependency.isSatisfied(mapOf("auth_type" to "oauth", "custom_server" to "true")),
        )
        assertEquals(false, tokenUrl.dependency.isSatisfied(mapOf("auth_type" to "oauth")))
        assertEquals(true, customHost.dependency.isSatisfied(mapOf("server_mode" to "manual")))
        assertEquals(false, customHost.dependency.isSatisfied(mapOf("server_mode" to "auto")))
    }

    @Test
    fun `an unknown condition kind is rejected instead of silently passing`() {
        val raw = """{ "xor": { "conditions": [ { "match": { "key": "a", "value": "b" } } ] } }"""

        val failure = assertFailsWith<Exception> {
            json.decodeFromString(PluginConfigDependency.serializer(), raw)
        }

        assertEquals(true, failure.message.orEmpty().contains("Unknown config dependency discriminator"))
    }
}
