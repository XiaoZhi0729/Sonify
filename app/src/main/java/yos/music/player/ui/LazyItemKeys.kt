package yos.music.player.ui

/**
 * 生成 LazyColumn/LazyRow 的 item key 列表：
 * 基础键重复时给第 2 次及以后的出现追加 "#序号"（首现保持原样，不破坏既有 item 身份），
 * 空值回退到 "idx_下标"。同一列表内返回值绝不重复。
 *
 * ## 使用约定（两次线上崩溃得出，违反必崩）
 *
 * 1. `itemsIndexed` 的 items 与本函数的入参必须派生自**同一次状态读取**（同一个 val），
 *    禁止一边写 `itemsIndexed(X.value, ...)` 一边 `remember(X.value) { lazyItemKeys(...) }`——
 *    LazyColumn 内容块有独立重组作用域且 key lambda 延迟到测量期执行，两处分别读
 *    X.value 可能落在不同代数据上，keys 空表时直接 IndexOutOfBoundsException。
 * 2. key lambda 内索引 keys 一律用 `keys.getOrElse(index) { "oob_$index" }` 兜底；
 *    "oob_" 前缀不会与本函数产出的任何 key 相撞。
 * 3. itemContent lambda 里也不要读实时 X.value 做索引/长度判断，用同一个 val
 *    （如分割线的 `index < items.size - 1`）。
 */
fun <T> lazyItemKeys(items: List<T>, baseKey: (T) -> String): List<String> {
    val seen = HashMap<String, Int>()
    return List(items.size) { index ->
        val base = baseKey(items[index]).ifEmpty { "idx_$index" }
        val occurrence = seen.merge(base, 1, Int::plus)!! - 1
        if (occurrence == 0) base else "$base#$occurrence"
    }
}
