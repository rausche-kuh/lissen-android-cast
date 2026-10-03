package org.grakovne.lissen.cast.upnp

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

internal fun parseXml(xml: String): Element {
  val factory =
    DocumentBuilderFactory.newInstance().apply {
      isNamespaceAware = true
      runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    }

  return factory
    .newDocumentBuilder()
    .parse(InputSource(StringReader(xml)))
    .documentElement
}

internal val Node.name: String
  get() = localName ?: nodeName.substringAfter(':')

internal fun Element.children(name: String): List<Element> =
  (0 until childNodes.length)
    .map { childNodes.item(it) }
    .filterIsInstance<Element>()
    .filter { it.name == name }

internal fun Element.child(name: String): Element? = children(name).firstOrNull()

internal fun Element.childText(name: String): String? = child(name)?.textContent?.trim()

/** Every element named [name] in the subtree, in document order. */
internal fun Element.descendants(name: String): List<Element> {
  val nodes = getElementsByTagNameNS("*", name)
  return (0 until nodes.length).map { nodes.item(it) as Element }
}

internal fun escapeXml(text: String): String =
  buildString(text.length) {
    text.forEach { char ->
      when (char) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        '\'' -> append("&apos;")
        else -> append(char)
      }
    }
  }
