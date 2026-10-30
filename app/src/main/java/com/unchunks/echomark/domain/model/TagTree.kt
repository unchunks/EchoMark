package com.unchunks.echomark.domain.model

/** 階層の中のタグ1つ。[depth] は最上位が 0。 */
data class TagNode(val tag: TagWithCount, val depth: Int)

/** タグの階層(親子)の扱い。親をたどる処理はどれも、輪になったデータ(壊れたバックアップなど)でも止まる。 */
object TagTree {

    /** 階層の順(親の直後にその子、兄弟は名前順)に深さつきで並べる。親が一覧に無いタグは最上位として扱う。 */
    fun order(tags: List<TagWithCount>): List<TagNode> {
        val ids = tags.mapTo(HashSet()) { it.id }
        val byParent = tags.groupBy { it.parentId?.takeIf { parent -> parent in ids } }
        val byName = compareBy<TagWithCount, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        val result = ArrayList<TagNode>(tags.size)
        val visited = HashSet<Long>()
        fun visit(tag: TagWithCount, depth: Int) {
            if (!visited.add(tag.id)) return
            result += TagNode(tag, depth)
            byParent[tag.id].orEmpty().sortedWith(byName).forEach { visit(it, depth + 1) }
        }
        byParent[null].orEmpty().sortedWith(byName).forEach { visit(it, 0) }
        // 輪になっていて最上位から届かなかったものも、落とさずに最上位として出す
        tags.filter { it.id !in visited }.sortedWith(byName).forEach { visit(it, 0) }
        return result
    }

    /** [tagId] の子孫のタグの ID(自分は含まない)。 */
    fun descendantIds(tags: List<TagWithCount>, tagId: Long): Set<Long> {
        val children = tags.groupBy { it.parentId }
        val result = LinkedHashSet<Long>()
        val queue = ArrayDeque(listOf(tagId))
        while (queue.isNotEmpty()) {
            children[queue.removeFirst()].orEmpty().forEach { child ->
                if (child.id != tagId && result.add(child.id)) queue += child.id
            }
        }
        return result
    }

    /** [tag] を親へたどった名前("プログラミング › Android"の左側)。無ければ空。 */
    fun ancestorNames(tags: List<TagWithCount>, tag: TagWithCount): List<String> {
        val byId = tags.associateBy { it.id }
        val seen = hashSetOf(tag.id)
        val names = ArrayDeque<String>()
        var current = tag.parentId?.let { byId[it] }
        while (current != null && seen.add(current.id)) {
            names.addFirst(current.name)
            current = current.parentId?.let { byId[it] }
        }
        return names.toList()
    }
}
