package com.chief.anansi.plugin.opds

import android.util.Xml
import com.chief.anansi.plugin.error.SourceError
import com.chief.anansi.plugin.error.SourcePluginException
import java.io.Reader
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

/** The namespaces an OPDS 1.x catalog mixes in one document. */
object OpdsXml {
    const val ATOM_NS = "http://www.w3.org/2005/Atom"
    const val OPDS_NS = "http://opds-spec.org/2010/catalog"
    const val XML_NS = "http://www.w3.org/XML/1998/namespace"

    /**
     * A hardened parser for untrusted catalog XML: namespace
     * processing on, DTD processing off, and no entity resolver, so a hostile
     * feed cannot define or expand entities against the parser; a reference
     * to an undefined entity fails the parse loudly.
     */
    fun newParser(input: Reader): XmlPullParser {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        try {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        } catch (e: UnsupportedOperationException) {
            // The platform parser supports the feature; another implementation
            // that does not expose it never processes DOCDECL by default either.
        }
        parser.setInput(input)
        return parser
    }
}

internal fun parseFailure(reason: String): SourcePluginException =
    SourcePluginException(SourceError.Parse(reason), null)

/**
 * Wraps a low-level XML failure into the loud adapter-boundary parse error,
 * keeping the parser's own position message as the reason.
 */
internal fun xmlFailure(location: String, e: XmlPullParserException): SourcePluginException =
    parseFailure("$location is not well-formed XML: ${e.message}")
