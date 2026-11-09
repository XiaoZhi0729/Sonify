package yos.music.player.ui.pages.settings.performance

/**
 * 「界面列表顺序 → 网格预设数组下标」的双向映射。
 *
 * 预设数组是上游 Pear Wall 导出的原始顺序，**不是**按形变强度排的，所以界面要按
 * 平均顶点位移从小到大重排（数值见 `PearMeshPresets` 的顶点数据）：
 * 竖屏最静的是 idx2、最动的是 idx3；横屏最静的是 idx2、最动的是 idx1。
 *
 * 这里的关键陷阱：**存的是数组下标、显示要的是界面位置，两个方向必须都走这张表**。
 * 只做写入方向、读取时直接 `labels[saved]`，会让下拉菜单"点 A 显示 B"——
 * 因为 `labels` 是界面顺序，而 `saved` 是数组下标，两者不同序。
 * 表里恰好 `ORDER[i] == i` 的那一项（两个方向都是「四角拉伸」）反而表现正常，
 * 这种"只有一项是对的"的现象就是这条错误的典型指纹。
 */
internal object MeshPresetOrder {

    /** 界面第 0..3 项（由静到动）对应的竖屏预设数组下标。 */
    val PORTRAIT = listOf(2, 0, 1, 3)

    /** 界面第 0..4 项（由静到动）对应的横屏预设数组下标。 */
    val LANDSCAPE = listOf(2, 4, 0, 3, 1)

    /** 界面第 [uiIndex] 项 → 要存进设置的预设数组下标。 */
    fun presetForUiIndex(order: List<Int>, uiIndex: Int): Int =
        order[uiIndex.coerceIn(0, order.lastIndex)]

    /**
     * 预设数组下标 [preset] → 它在界面上排第几项。
     *
     * 正常路径下 [preset] 一定在表里；只有历史脏数据（越界或不在表内）才回落到
     * 夹紧后的原值 —— 宁可显示一个近似项，也不要 `indexOf` 返回 -1 去索引崩溃。
     */
    fun uiIndexForPreset(order: List<Int>, preset: Int): Int =
        order.indexOf(preset).takeIf { it >= 0 }
            ?: preset.coerceIn(0, order.lastIndex)
}
