package com.example.termmgmt.service;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermbaseConfig;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.NamedNodeMap;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class TbxTermbaseHandler {

    /**
     * Termbase files may come from untrusted sources. Turn off external entities and
     * external DTD loading (XXE / SSRF). DOCTYPE declarations stay allowed because real
     * TBX files commonly carry one, but their external DTD is neither fetched nor required.
     */
    private static DocumentBuilderFactory newSecureDocumentBuilderFactory() throws ParserConfigurationException {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        setFeatureIfSupported(dbf, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        setFeatureIfSupported(dbf, "http://xml.org/sax/features/external-general-entities", false);
        setFeatureIfSupported(dbf, "http://xml.org/sax/features/external-parameter-entities", false);
        setFeatureIfSupported(dbf, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);
        return dbf;
    }

    private static void setFeatureIfSupported(DocumentBuilderFactory dbf, String feature, boolean value) {
        try {
            dbf.setFeature(feature, value);
        } catch (ParserConfigurationException | RuntimeException e) {
            // Feature not known to this JAXP implementation; the others still apply.
        }
    }

    private static TransformerFactory newSecureTransformerFactory() {
        TransformerFactory tf = TransformerFactory.newInstance();
        try {
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (Exception e) {
            // Not supported by this implementation.
        }
        try {
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        } catch (IllegalArgumentException e) {
            // JAXP 1.5 attributes not supported by this implementation.
        }
        return tf;
    }

    public static List<TermEntry> loadTerms(TermbaseConfig config) {
        List<TermEntry> terms = new ArrayList<>();
        String filePath = config.getFilePath();

        try {
            DocumentBuilderFactory dbf = newSecureDocumentBuilderFactory();
            dbf.setNamespaceAware(false);
            DocumentBuilder builder = dbf.newDocumentBuilder();
            Document doc = builder.parse(new File(filePath));

            NodeList termEntryNodes = doc.getElementsByTagName("termEntry");

            String detectedSourceLang = null;
            String detectedTargetLang = null;

            for (int i = 0; i < termEntryNodes.getLength(); i++) {
                Node termEntryNode = termEntryNodes.item(i);
                TermEntry entry = new TermEntry();

                int langSetIndex = 0;
                NodeList langSets = termEntryNode.getChildNodes();
                for (int j = 0; j < langSets.getLength(); j++) {
                    Node langSet = langSets.item(j);
                    if (langSet.getNodeType() != Node.ELEMENT_NODE || !langSet.getNodeName().equals("langSet")) {
                        continue;
                    }

                    NamedNodeMap attrs = langSet.getAttributes();
                    if (attrs == null) continue;

                    Node langAttr = attrs.getNamedItem("xml:lang");
                    if (langAttr == null) langAttr = attrs.getNamedItem("lang");
                    if (langAttr == null) continue;

                    String lang = langAttr.getNodeValue();
                    if (lang == null) continue;

                    String termText = getTermText(langSet);
                    if (termText == null) continue;

                    if (langSetIndex == 0) {
                        entry.setSourceTerm(termText);
                        if (detectedSourceLang == null) detectedSourceLang = lang;
                    } else if (langSetIndex == 1) {
                        entry.setTargetTerm(termText);
                        if (detectedTargetLang == null) detectedTargetLang = lang;
                    }
                    langSetIndex++;
                }

                if (entry.getSourceTerm() != null || entry.getTargetTerm() != null) {
                    terms.add(entry);
                }
            }

            if (detectedSourceLang != null) config.setSourceLang(detectedSourceLang);
            if (detectedTargetLang != null) config.setTargetLang(detectedTargetLang);

        } catch (Exception e) {
            throw new RuntimeException("Failed to load TBX: " + filePath, e);
        }

        return terms;
    }

    public static void saveTerms(TermbaseConfig config, List<TermEntry> terms) {
        String filePath = config.getFilePath();

        try {
            DocumentBuilderFactory dbf = newSecureDocumentBuilderFactory();
            dbf.setNamespaceAware(false);
            DocumentBuilder builder = dbf.newDocumentBuilder();
            Document doc = builder.parse(new File(filePath));

            NodeList bodyNodes = doc.getElementsByTagName("body");
            if (bodyNodes.getLength() == 0) {
                throw new RuntimeException("No body node in TBX file");
            }
            Element body = (Element) bodyNodes.item(0);

            NodeList existingEntryNodes = body.getElementsByTagName("termEntry");
            while (existingEntryNodes.getLength() > 0) {
                body.removeChild(existingEntryNodes.item(0));
            }

            String sourceLang = config.getSourceLang() != null ? config.getSourceLang() : "zh-CN";
            String targetLang = config.getTargetLang() != null ? config.getTargetLang() : "en-US";

            for (TermEntry entry : terms) {
                Element termEntry = doc.createElement("termEntry");
                termEntry.setAttribute("id", "tid" + (terms.indexOf(entry) + 1));

                Element langSetSource = doc.createElement("langSet");
                langSetSource.setAttribute("xml:lang", sourceLang);
                Element tigSource = doc.createElement("tig");
                Element termSource = doc.createElement("term");
                termSource.setTextContent(entry.getSourceTerm() != null ? entry.getSourceTerm() : "");
                tigSource.appendChild(termSource);
                langSetSource.appendChild(tigSource);
                termEntry.appendChild(langSetSource);

                Element langSetTarget = doc.createElement("langSet");
                langSetTarget.setAttribute("xml:lang", targetLang);
                Element tigTarget = doc.createElement("tig");
                Element termTarget = doc.createElement("term");
                termTarget.setTextContent(entry.getTargetTerm() != null ? entry.getTargetTerm() : "");
                tigTarget.appendChild(termTarget);
                langSetTarget.appendChild(tigTarget);
                termEntry.appendChild(langSetTarget);

                body.appendChild(termEntry);
            }

            TransformerFactory tf = newSecureTransformerFactory();
            Transformer transformer = tf.newTransformer();
            transformer.setOutputProperty("indent", "yes");
            DOMSource source = new DOMSource(doc);
            StreamResult result = new StreamResult(new File(filePath));
            transformer.transform(source, result);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save TBX: " + filePath, e);
        }
    }

    private static String getTermText(Node langSet) {
        NodeList children = langSet.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                String nodeName = child.getNodeName();
                if ("tig".equals(nodeName)) {
                    String text = getTextFromTig(child);
                    if (text != null) return text;
                } else if ("ntig".equals(nodeName)) {
                    String text = getTextFromNtig(child);
                    if (text != null) return text;
                }
            }
        }
        return null;
    }

    private static String getTextFromTig(Node tig) {
        NodeList children = tig.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && "term".equals(child.getNodeName())) {
                String text = child.getTextContent();
                return (text != null && !text.trim().isEmpty()) ? text.trim() : null;
            }
        }
        return null;
    }

    private static String getTextFromNtig(Node ntig) {
        NodeList children = ntig.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && "termGrp".equals(child.getNodeName())) {
                String text = getTextFromTermGrp(child);
                if (text != null) return text;
            }
        }
        return null;
    }

    private static String getTextFromTermGrp(Node termGrp) {
        NodeList children = termGrp.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && "term".equals(child.getNodeName())) {
                String text = child.getTextContent();
                return (text != null && !text.trim().isEmpty()) ? text.trim() : null;
            }
        }
        return null;
    }
}
