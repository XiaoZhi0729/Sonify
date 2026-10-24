package yos.music.player.code.utils.player

/** Bidirectional, non-repeating shuffle rounds for manual navigation. */
internal class ShuffleNavigationHistory(
    private val shuffle: (List<String>) -> List<String> = { it.shuffled() },
) {
    private var queueIds: List<String> = emptyList()
    private val path = mutableListOf<String>()
    private var cursor = -1

    fun next(queue: List<String>, currentId: String): String? = move(queue, currentId, forward = true)

    fun previous(queue: List<String>, currentId: String): String? = move(queue, currentId, forward = false)

    fun clear() {
        queueIds = emptyList()
        path.clear()
        cursor = -1
    }

    /** Aligns spontaneous transitions with the history, preserving it when the transition was expected. */
    fun observe(queue: List<String>, currentId: String?) {
        val ids = normalized(queue)
        if (ids.size < 2 || currentId == null || currentId !in ids) {
            clear()
            return
        }
        align(ids, currentId)
    }

    /**
     * 让游标贴合当前曲：同一首不动；正好走到已记录路径的相邻节点就跟随
     * （自然播放/外部命令/手动 seek 的转场都走这里）；否则以当前曲重建。
     */
    private fun align(ids: List<String>, currentId: String) {
        if (ids.toSet() != queueIds.toSet()) {
            reset(ids, currentId)
            return
        }
        if (path.getOrNull(cursor) == currentId) return
        if (path.getOrNull(cursor + 1) == currentId) {
            cursor++
            return
        }
        if (path.getOrNull(cursor - 1) == currentId) {
            cursor--
            return
        }
        reset(ids, currentId)
    }

    private fun move(queue: List<String>, currentId: String, forward: Boolean): String? {
        val ids = normalized(queue)
        if (ids.size < 2 || currentId !in ids) {
            clear()
            return null
        }
        align(ids, currentId)

        if (forward) {
            if (cursor == path.lastIndex) appendRound(ids)
            cursor++
        } else {
            if (cursor == 0) prependRound(ids)
            cursor--
        }
        return path.getOrNull(cursor)
    }

    private fun reset(ids: List<String>, currentId: String) {
        queueIds = ids
        val rest = ids.filterNot { it == currentId }
        path.clear()
        path.add(currentId)
        path.addAll(shuffle(rest).filter { it != currentId && it in ids }.distinct())
        path.addAll(rest.filterNot { it in path })
        cursor = 0
    }

    private fun appendRound(ids: List<String>) {
        val previousLast = path.last()
        val round = shuffledRound(ids, forbiddenFirst = previousLast)
        path.addAll(round)
    }

    private fun prependRound(ids: List<String>) {
        val nextFirst = path.first()
        val round = shuffledRound(ids, forbiddenLast = nextFirst)
        path.addAll(0, round)
        cursor += round.size
    }

    private fun shuffledRound(
        ids: List<String>,
        forbiddenFirst: String? = null,
        forbiddenLast: String? = null,
    ): List<String> {
        val round = shuffle(ids).filter { it in ids }.distinct().toMutableList()
        round.addAll(ids.filterNot { it in round })
        if (round.size > 1 && forbiddenFirst != null && round.first() == forbiddenFirst) {
            val swap = (1 until round.size).firstOrNull { round[it] != forbiddenLast } ?: 1
            round[0] = round[swap].also { round[swap] = round[0] }
        }
        if (round.size > 1 && forbiddenLast != null && round.last() == forbiddenLast) {
            val swap = (0 until round.lastIndex).firstOrNull { round[it] != forbiddenFirst } ?: 0
            round[round.lastIndex] = round[swap].also { round[swap] = round.last() }
        }
        return round
    }

    private fun normalized(queue: List<String>): List<String> = queue.distinct()
}
