package ua.olex.music.lyrics

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * TTML as music services write it (Apple Music's syllable-timed lyrics and files made from them):
 * each `<p begin end ttm:agent>` is a line; its `<span begin end>` children are words or
 * syllables, and white space between two spans is a space between words (no white space joins
 * syllables of one word). A span with `ttm:role="x-bg"` holds backing vocals, timed the same way;
 * `x-translation` holds the line's translation. A `<p>` without timed spans is a line-timed line.
 */
object TtmlParser {
    fun parse(xml: String): Lyrics {
        if (xml.contains("<!DOCTYPE", ignoreCase = true) || xml.contains("<!ENTITY", ignoreCase = true)) return Lyrics.EMPTY
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // No external entities or DTDs: lyrics come from the network and from other people's files.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { isExpandEntityReferences = false }
        }
        val document = runCatching { factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }.parse(InputSource(StringReader(xml.trimStart('﻿')))) }.getOrNull()
            ?: return Lyrics.EMPTY
        val paragraphs = document.getElementsByTagName("p")
        val lines = ArrayList<Line>()
        var anyWords = false
        for (i in 0 until paragraphs.length) {
            val p = paragraphs.item(i) as? Element ?: continue
            val agent = p.getAttribute("ttm:agent").ifEmpty { null }
            val main = collect(p)
            val start = time(p.getAttribute("begin")) ?: main.words.firstOrNull()?.startMs ?: continue
            val end = time(p.getAttribute("end")) ?: main.words.lastOrNull()?.endMs ?: (start + 5_000)
            val text = if (main.words.isNotEmpty()) main.words.joinToString("") { it.text }.trim() else main.plain.trim().replace(Regex("\\s+"), " ")
            if (text.isNotEmpty()) {
                anyWords = anyWords || main.words.isNotEmpty()
                lines += Line(start, end, text, main.words, agent = agent, translation = main.translation)
            }
            for (bg in main.background) {
                val words = bg.words
                val bgText = (if (words.isNotEmpty()) words.joinToString("") { it.text } else bg.plain.toString()).trim().trim('(', ')')
                if (bgText.isEmpty()) continue
                lines += Line(words.firstOrNull()?.startMs ?: start, words.lastOrNull()?.endMs ?: end, bgText, words, background = true, agent = agent)
            }
        }
        if (lines.isEmpty()) return Lyrics.EMPTY
        // Main lines in time order; a background line stays right after its own line.
        return Lyrics(lines, if (anyWords) Sync.WORD else Sync.LINE)
    }

    private class Part {
        val words = ArrayList<Word>()
        val plain = StringBuilder()
        val background = ArrayList<Part>()
        var translation: String? = null
    }

    private fun collect(parent: Element): Part {
        val part = Part()
        var node: Node? = parent.firstChild
        while (node != null) {
            when {
                node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE -> {
                    val text = node.nodeValue.orEmpty()
                    part.plain.append(text)
                    // White space after a word: the next span starts a new word.
                    if (text.isNotEmpty() && text[0].isWhitespace()) spaceAfterLast(part)
                }
                node is Element && node.tagName == "span" -> {
                    val role = node.getAttribute("ttm:role")
                    val begin = time(node.getAttribute("begin"))
                    val end = time(node.getAttribute("end"))
                    when {
                        role == "x-bg" -> part.background += collect(node)
                        role == "x-translation" -> part.translation = node.textContent.trim().ifEmpty { null }
                        role == "x-roman" -> Unit
                        begin != null && end != null -> {
                            val text = node.textContent
                            part.plain.append(text)
                            if (text.isNotBlank()) part.words += Word(begin, end, text.trim() + if (text.last().isWhitespace()) " " else "")
                        }
                        else -> { // an untimed wrapper: its timed spans count as this line's
                            val inner = collect(node)
                            part.words += inner.words; part.plain.append(inner.plain); part.background += inner.background
                        }
                    }
                }
                node is Element && node.tagName == "br" -> { part.plain.append(' '); spaceAfterLast(part) }
            }
            node = node.nextSibling
        }
        return part
    }

    private fun spaceAfterLast(part: Part) {
        val last = part.words.lastOrNull() ?: return
        if (!last.text.endsWith(' ')) part.words[part.words.lastIndex] = last.copy(text = last.text + " ")
    }

    /** "1:02.345", "00:01:02.345", "62.345", "62.3s", "1500ms". */
    internal fun time(value: String): Long? {
        val v = value.trim()
        if (v.isEmpty()) return null
        if (v.endsWith("ms")) return v.dropLast(2).toDoubleOrNull()?.toLong()
        val parts = v.removeSuffix("s").split(':')
        var seconds = 0.0
        for (p in parts) seconds = seconds * 60 + (p.toDoubleOrNull() ?: return null)
        return Math.round(seconds * 1000)
    }
}
