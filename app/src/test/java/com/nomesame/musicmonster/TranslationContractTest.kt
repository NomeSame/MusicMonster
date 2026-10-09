package com.nomesame.musicmonster

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

/** Every added UI resource must have matching translations and format placeholders. */
class TranslationContractTest {
    private val resources = listOf(File("src/main/res"), File("app/src/main/res"))
        .firstOrNull { it.isDirectory } ?: error("Cannot locate app resources")

    private fun read(file: File): Map<String, Map<String, String>> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val result = linkedMapOf<String, Map<String, String>>()
        val nodes = document.documentElement.childNodes
        for (index in 0 until nodes.length) {
            val node = nodes.item(index) as? Element ?: continue
            val key = node.getAttribute("name")
            assertFalse("Duplicate resource $key in $file", result.containsKey(key))
            result[key] = if (node.tagName == "plurals") {
                val items = node.getElementsByTagName("item")
                (0 until items.length).associate {
                    val item = items.item(it) as Element
                    item.getAttribute("quantity") to item.textContent
                }
            } else mapOf("string" to node.textContent)
        }
        return result
    }
    @Test fun everySupportedLanguageContainsAllTextsAndMatchingPlaceholders() {
        val defaults = read(File(resources, "values/strings.xml"))
        val languages = resources.listFiles()!!.filter {
            it.name.matches(Regex("values-(?:[a-z]{2,3}(?:-r[A-Z]{2})?|b\\+.+)"))
        }
        assertTrue("German translations must exist", languages.any { it.name == "values-de" })
        for (language in languages) {
            val translated = read(File(language, "strings.xml"))
            assertEquals("Missing or extra resources in ${language.name}", defaults.keys, translated.keys)
            for ((key, forms) in defaults) {
                val translations = translated.getValue(key)
                assertTrue("Missing default forms for $key in ${language.name}", translations.keys.containsAll(forms.keys))
                for ((form, text) in translations) {
                    val value = forms[form] ?: forms.getValue("other")
                    assertTrue("Empty translation $key/$form", text.isNotBlank())
                    val placeholders = Regex("%[0-9]+\\$[ds]")
                    assertEquals("Format mismatch $key/$form in ${language.name}",
                        placeholders.findAll(value).map { it.value }.sorted().toList(),
                        placeholders.findAll(text).map { it.value }.sorted().toList())
                }
            }
        }
    }
}
