package com.example.rnoteviewer

/**
 * R-tree spaziale per query viewport su [CanvasElement].
 * Costruito con STR (Sort-Tile-Recursive) bulk-loading → O(log n + k) query.
 */
class RTree private constructor(private val root: Node?) {

    fun query(minX: Float, minY: Float, maxX: Float, maxY: Float): List<CanvasElement> {
        if (root == null) return emptyList()
        val result = mutableListOf<CanvasElement>()
        query(root, minX, minY, maxX, maxY, result)
        return result
    }

    val size: Int get() = _size
    private var _size: Int = 0

    private fun query(
        node: Node, minX: Float, minY: Float, maxX: Float, maxY: Float,
        result: MutableList<CanvasElement>
    ) {
        if (!node.mbr.intersects(minX, minY, maxX, maxY)) return
        when (node) {
            is LeafNode     -> node.entries.forEach { (mbr, el) ->
                if (mbr.intersects(minX, minY, maxX, maxY)) result.add(el)
            }
            is InternalNode -> node.children.forEach { query(it, minX, minY, maxX, maxY, result) }
        }
    }

    // ── Nodes ─────────────────────────────────
    private data class MBR(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
        fun intersects(qx0: Float, qy0: Float, qx1: Float, qy1: Float) =
            minX <= qx1 && maxX >= qx0 && minY <= qy1 && maxY >= qy0

        companion object {
            fun of(e: CanvasElement) = MBR(e.minX, e.minY, e.maxX, e.maxY)
            fun union(list: List<CanvasElement>): MBR {
                var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE
                var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
                for (e in list) {
                    if (e.minX < x0) x0 = e.minX; if (e.maxX > x1) x1 = e.maxX
                    if (e.minY < y0) y0 = e.minY; if (e.maxY > y1) y1 = e.maxY
                }
                return MBR(x0, y0, x1, y1)
            }
            fun unionNodes(list: List<Node>): MBR {
                var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE
                var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
                for (n in list) {
                    if (n.mbr.minX < x0) x0 = n.mbr.minX; if (n.mbr.maxX > x1) x1 = n.mbr.maxX
                    if (n.mbr.minY < y0) y0 = n.mbr.minY; if (n.mbr.maxY > y1) y1 = n.mbr.maxY
                }
                return MBR(x0, y0, x1, y1)
            }
        }
    }
    private sealed class Node(val mbr: MBR)
    private class LeafNode(mbr: MBR, val entries: List<Pair<MBR, CanvasElement>>) : Node(mbr)
    private class InternalNode(mbr: MBR, val children: List<Node>) : Node(mbr)

    // ── STR bulk-load ─────────────────────────
    companion object {
        fun build(elements: List<CanvasElement>, pageSize: Int = 16): RTree {
            val tree = RTree(if (elements.isEmpty()) null else buildLevel(elements, pageSize))
            tree._size = elements.size
            return tree
        }

        private fun buildLevel(elements: List<CanvasElement>, pageSize: Int): Node {
            if (elements.size <= pageSize) {
                val entries = elements.map { Pair(MBR.of(it), it) }
                return LeafNode(MBR.union(elements), entries)
            }
            val sliceSize = pageSize * Math.ceil(
                Math.sqrt(Math.ceil(elements.size.toDouble() / pageSize))
            ).toInt()
            val sortedByX = elements.sortedBy { (it.minX + it.maxX) / 2f }
            val children = mutableListOf<Node>()
            var i = 0
            while (i < sortedByX.size) {
                val slice = sortedByX.subList(i, minOf(i + sliceSize, sortedByX.size))
                val sortedByY = slice.sortedBy { (it.minY + it.maxY) / 2f }
                var j = 0
                while (j < sortedByY.size) {
                    val page = sortedByY.subList(j, minOf(j + pageSize, sortedByY.size))
                    children.add(buildLevel(page, pageSize))
                    j += pageSize
                }
                i += sliceSize
            }
            return InternalNode(MBR.unionNodes(children), children)
        }
    }
}
