package com.chief.anansi.plugin.local

import android.util.Xml
import java.io.Reader
import org.xmlpull.v1.XmlPullParser

/**
 * The hardened pull-parser setup for EPUB's XML documents (container.xml,
 * OPF, spine XHTML), the same posture the OPDS feeds use: namespace
 * processing on (element names are local names), DTD processing off, and no
 * entity resolver, so a hostile file cannot expand entities against the
 * parser; a reference to an undefined entity fails the parse loudly.
 */
internal object LocalXml {

    fun newParser(input: Reader): XmlPullParser {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        try {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        } catch (e: UnsupportedOperationException) {
            // Another implementation that lacks the feature never processes
            // DOCDECL by default either.
        }
        parser.setInput(input)
        return parser
    }

    fun newParser(input: String): XmlPullParser = newParser(input.reader())
}
