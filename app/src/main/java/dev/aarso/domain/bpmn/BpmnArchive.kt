package dev.aarso.domain.bpmn

import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Serialises a [BpmnGraph] to/from standard BPMN 2.0 XML (the loop interchange
 * format — docs/design/workflow-builder.md §1). Dependency-light on purpose:
 * hand-built XML on write, the JDK/Android `javax.xml` DOM on read (no new
 * library — same minimal-surface stance as `TreeArchive`/`Converters`).
 *
 * A `.bpmn` opens in any BPMN tool; Aarso's own data rides in a single
 * `<aarso:meta …/>` extension element per node, so the file stays valid-standard.
 */
object BpmnArchive {

    private const val BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL"
    private const val BPMNDI = "http://www.omg.org/spec/BPMN/20100524/DI"
    private const val DC = "http://www.omg.org/spec/DD/20100524/DC"
    private const val DI = "http://www.omg.org/spec/DD/20100524/DI"
    private const val XSI = "http://www.w3.org/2001/XMLSchema-instance"
    private const val AARSO = "https://aarso.dev/bpmn"

    fun write(g: BpmnGraph): String {
        val s = StringBuilder()
        s.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        s.append("<bpmn:definitions ")
            .append("xmlns:bpmn=\"").append(BPMN).append("\" ")
            .append("xmlns:bpmndi=\"").append(BPMNDI).append("\" ")
            .append("xmlns:dc=\"").append(DC).append("\" ")
            .append("xmlns:di=\"").append(DI).append("\" ")
            .append("xmlns:xsi=\"").append(XSI).append("\" ")
            .append("xmlns:aarso=\"").append(AARSO).append("\" ")
            .append("id=\"defs_").append(esc(g.id)).append("\" ")
            .append("targetNamespace=\"https://aarso.dev/loops\">").append('\n')
        s.append("  <bpmn:process id=\"").append(esc(g.id)).append("\" name=\"").append(esc(g.name))
            .append("\" isExecutable=\"false\">").append('\n')

        for (n in g.nodes) {
            s.append("    <bpmn:").append(n.kind.element).append(" id=\"").append(esc(n.id)).append('"')
            if (n.name.isNotEmpty()) s.append(" name=\"").append(esc(n.name)).append('"')
            s.append('>').append('\n')
            if (n.ext.isNotEmpty()) {
                s.append("      <bpmn:extensionElements><aarso:meta")
                for ((k, v) in n.ext) s.append(' ').append(k).append("=\"").append(esc(v)).append('"')
                s.append("/></bpmn:extensionElements>").append('\n')
            }
            for (e in g.incoming(n.id)) s.append("      <bpmn:incoming>").append(esc(e.id)).append("</bpmn:incoming>").append('\n')
            for (e in g.outgoing(n.id)) s.append("      <bpmn:outgoing>").append(esc(e.id)).append("</bpmn:outgoing>").append('\n')
            s.append("    </bpmn:").append(n.kind.element).append('>').append('\n')
        }

        for (e in g.edges) {
            s.append("    <bpmn:sequenceFlow id=\"").append(esc(e.id))
                .append("\" sourceRef=\"").append(esc(e.sourceId))
                .append("\" targetRef=\"").append(esc(e.targetId)).append('"')
            if (e.name != null) s.append(" name=\"").append(esc(e.name)).append('"')
            if (e.condition != null) {
                s.append('>').append('\n')
                s.append("      <bpmn:conditionExpression xsi:type=\"bpmn:tFormalExpression\">")
                    .append(esc(e.condition)).append("</bpmn:conditionExpression>").append('\n')
                s.append("    </bpmn:sequenceFlow>").append('\n')
            } else {
                s.append("/>").append('\n')
            }
        }
        s.append("  </bpmn:process>").append('\n')

        // BPMN-DI: carry each shape's bounds so layout transports.
        s.append("  <bpmndi:BPMNDiagram id=\"diagram_").append(esc(g.id)).append("\">").append('\n')
        s.append("    <bpmndi:BPMNPlane id=\"plane_").append(esc(g.id))
            .append("\" bpmnElement=\"").append(esc(g.id)).append("\">").append('\n')
        for (n in g.nodes) {
            s.append("      <bpmndi:BPMNShape id=\"shape_").append(esc(n.id))
                .append("\" bpmnElement=\"").append(esc(n.id)).append("\">").append('\n')
            s.append("        <dc:Bounds x=\"").append(n.bounds.x).append("\" y=\"").append(n.bounds.y)
                .append("\" width=\"").append(n.bounds.width).append("\" height=\"").append(n.bounds.height)
                .append("\"/>").append('\n')
            s.append("      </bpmndi:BPMNShape>").append('\n')
        }
        for (e in g.edges) {
            s.append("      <bpmndi:BPMNEdge id=\"edge_").append(esc(e.id))
                .append("\" bpmnElement=\"").append(esc(e.id)).append("\"/>").append('\n')
        }
        s.append("    </bpmndi:BPMNPlane>").append('\n')
        s.append("  </bpmndi:BPMNDiagram>").append('\n')
        s.append("</bpmn:definitions>").append('\n')
        return s.toString()
    }

    fun read(xml: String): BpmnGraph {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
        val process = doc.getElementsByTagNameNS(BPMN, "process").item(0) as Element

        // node bounds from BPMN-DI, keyed by element ref
        val boundsById = HashMap<String, Bounds>()
        val shapes = doc.getElementsByTagNameNS(BPMNDI, "BPMNShape")
        for (i in 0 until shapes.length) {
            val shp = shapes.item(i) as Element
            val b = childNS(shp, DC, "Bounds") ?: continue
            boundsById[shp.getAttribute("bpmnElement")] = Bounds(
                b.getAttribute("x").toDoubleOrNull() ?: 0.0,
                b.getAttribute("y").toDoubleOrNull() ?: 0.0,
                b.getAttribute("width").toDoubleOrNull() ?: 130.0,
                b.getAttribute("height").toDoubleOrNull() ?: 64.0,
            )
        }

        val nodes = ArrayList<BpmnNode>()
        val edges = ArrayList<BpmnEdge>()
        val kids = process.childNodes
        for (i in 0 until kids.length) {
            val c = kids.item(i)
            if (c !is Element || c.namespaceURI != BPMN) continue
            if (c.localName == "sequenceFlow") {
                val cond = childNS(c, BPMN, "conditionExpression")?.textContent?.trim()?.ifEmpty { null }
                edges.add(
                    BpmnEdge(
                        id = c.getAttribute("id"),
                        sourceId = c.getAttribute("sourceRef"),
                        targetId = c.getAttribute("targetRef"),
                        name = c.getAttribute("name").ifEmpty { null },
                        condition = cond,
                    ),
                )
            } else {
                val kind = BpmnNodeKind.fromElement(c.localName) ?: continue
                val ext = LinkedHashMap<String, String>()
                childNS(c, BPMN, "extensionElements")?.let { ee ->
                    childNS(ee, AARSO, "meta")?.let { meta ->
                        val attrs = meta.attributes
                        for (j in 0 until attrs.length) {
                            val a = attrs.item(j)
                            if (a.nodeName.startsWith("xmlns")) continue
                            ext[a.localName ?: a.nodeName] = a.nodeValue
                        }
                    }
                }
                nodes.add(
                    BpmnNode(
                        id = c.getAttribute("id"),
                        kind = kind,
                        name = c.getAttribute("name"),
                        bounds = boundsById[c.getAttribute("id")] ?: Bounds(0.0, 0.0),
                        ext = ext,
                    ),
                )
            }
        }
        return BpmnGraph(process.getAttribute("id"), process.getAttribute("name"), nodes, edges)
    }

    private fun childNS(parent: Element, ns: String, local: String): Element? {
        val kids = parent.childNodes
        for (i in 0 until kids.length) {
            val k = kids.item(i)
            if (k is Element && k.namespaceURI == ns && k.localName == local) return k
        }
        return null
    }

    private fun esc(s: String): String = buildString(s.length) {
        for (ch in s) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(ch)
        }
    }
}
