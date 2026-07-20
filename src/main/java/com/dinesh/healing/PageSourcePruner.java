package com.dinesh.healing;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Shrinks a raw Appium getPageSource() XML dump (often 100-300 KB on a busy
 * banking screen) into a compact document containing only what an LLM needs
 * to identify elements. Typical reduction: 5-10x.
 *
 * Transformations:
 *   - keep only locator-relevant attributes (resource-id, content-desc, text,
 *     class, name, label, value, type, clickable, enabled)
 *   - drop subtrees that are not displayed/visible
 *   - collapse pure layout containers (no useful attributes, single child)
 *
 * Parsing is defensive: any XML failure returns a truncated raw string rather
 * than blowing up the healing path.
 */
public final class PageSourcePruner {

    /** Attributes worth keeping for element identification. */
    private static final Set<String> KEEP_ATTRS = Set.of(
            "resource-id", "content-desc", "text", "class", "package",
            "name", "label", "value", "type",
            "clickable", "enabled", "checkable", "focused");

    /** Android layout classes that add no identification value on their own. */
    private static final Set<String> COLLAPSIBLE_CLASSES = Set.of(
            "android.widget.FrameLayout", "android.widget.LinearLayout",
            "android.widget.RelativeLayout", "android.view.ViewGroup",
            "androidx.recyclerview.widget.RecyclerView",
            "XCUIElementTypeOther", "XCUIElementTypeGroup");

    private static final int FALLBACK_TRUNCATE_CHARS = 40_000;

    private PageSourcePruner() {
    }

    public static String prune(String rawPageSource) {
        if (rawPageSource == null || rawPageSource.isBlank()) {
            return "";
        }
        try {
            Document doc = parse(rawPageSource);
            Element root = doc.getDocumentElement();
            pruneElement(doc, root);
            return serialize(doc);
        } catch (Exception e) {
            // Never let pruning break healing - degrade to truncation.
            return rawPageSource.length() > FALLBACK_TRUNCATE_CHARS
                    ? rawPageSource.substring(0, FALLBACK_TRUNCATE_CHARS)
                    : rawPageSource;
        }
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Hardening: page source is app-controlled input.
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    private static void pruneElement(Document doc, Element element) {
        // 1. Remove invisible subtrees.
        List<Element> children = childElements(element);
        for (Element child : children) {
            if (isInvisible(child)) {
                element.removeChild(child);
            }
        }

        // 2. Strip unwanted attributes (also drops noisy defaults).
        NamedNodeMap attrs = element.getAttributes();
        List<String> toRemove = new ArrayList<>();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node attr = attrs.item(i);
            String name = attr.getNodeName();
            String value = attr.getNodeValue();
            boolean keep = KEEP_ATTRS.contains(name)
                    && value != null && !value.isBlank()
                    && !"false".equals(value);
            if (!keep) {
                toRemove.add(name);
            }
        }
        toRemove.forEach(element::removeAttribute);

        // 3. Recurse, then collapse trivial layout wrappers:
        //    a container with no identifying attributes and exactly one child
        //    is replaced by that child.
        for (Element child : childElements(element)) {
            pruneElement(doc, child);
        }
        List<Element> after = childElements(element);
        for (Element child : after) {
            if (isCollapsible(child)) {
                List<Element> grandChildren = childElements(child);
                if (grandChildren.size() == 1) {
                    element.replaceChild(grandChildren.get(0), child);
                } else if (grandChildren.isEmpty()) {
                    element.removeChild(child);
                }
            }
        }
    }

    private static boolean isInvisible(Element el) {
        String displayed = el.getAttribute("displayed");
        String visible = el.getAttribute("visible");
        return "false".equals(displayed) || "false".equals(visible);
    }

    private static boolean isCollapsible(Element el) {
        String className = el.getAttribute("class");
        String tag = el.getTagName();
        boolean layoutClass = COLLAPSIBLE_CLASSES.contains(className)
                || COLLAPSIBLE_CLASSES.contains(tag);
        if (!layoutClass) {
            return false;
        }
        for (String attr : List.of("resource-id", "content-desc", "text", "name", "label")) {
            String v = el.getAttribute(attr);
            if (v != null && !v.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static List<Element> childElements(Element parent) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element el) {
                result.add(el);
            }
        }
        return result;
    }

    private static String serialize(Document doc) throws Exception {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        transformer.setOutputProperty(OutputKeys.INDENT, "no");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));
        return writer.toString();
    }
}
