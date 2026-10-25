package yos.music.player.ui.pages.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 恒定 4 容器闭环轨道 + 延后一拍换图的契约测试。
 *
 * 断言把规范里的硬性要求变成可执行契约：
 *  - **恒定 4 个容器**：环位置恒落在 [0,4)，永不增删；
 *  - **闭环**：向右滑一圈后容器回到原位、内容前进 4（队列前进 4 首）；
 *  - **延后一拍换图**：只有 Slot1→Slot2（右→下）那一段 crossfadeAlpha > 0，
 *    其余三段恒为 0（绝不在一划走主焦点时就变图）；
 *  - **Z 轴**：100/80/60/40 严格递减，且层级序 = 尺寸序；
 *  - **无上界**：scrollIndex 可以无限增大（不会"滑到第十首就滑不动"）。
 *
 * 坐标全部以**舞台宽度 W 归一化**，断言与屏幕尺寸无关。
 */
class GallerySlotGeometryTest {

    private val slots = 0 until GallerySlotGeometry.SLOT_COUNT

    // ---------- 槽位表 ----------

    @Test
    fun slotTableMatchesDesign() {
        // z 是**纵深序**（离主焦点越近越高），不是槽位号序：
        // 主焦点 100 > 左中 80 > 正下 60 > 右侧 40。
        // 右侧之所以最低：它刚离开主焦点，是环上离焦点最远的一张；
        // 而左中那张正朝焦点前进，必须压过右侧（见 zIndexRisesWhileApproachingFocus）。
        val expected = mapOf(
            0 to floatArrayOf(0.0976f, -0.1830f, 1.00f, 100f),
            1 to floatArrayOf(0.2684f, 0.1952f, 0.64f, 40f),
            2 to floatArrayOf(-0.0610f, 0.3416f, 0.64f, 60f),
            3 to floatArrayOf(-0.2928f, 0.0488f, 0.64f, 80f)
        )
        for ((slot, e) in expected) {
            val s = GallerySlotGeometry.slot(slot)
            assertEquals("slot=$slot offsetX", e[0], s.offsetX, 1e-4f)
            assertEquals("slot=$slot offsetY", e[1], s.offsetY, 1e-4f)
            assertEquals("slot=$slot scale", e[2], s.scale, 1e-6f)
            assertEquals("slot=$slot z", e[3], s.z, 1e-3f)
        }
    }

    @Test
    fun zOrderFollowsFocusDistanceNotSlotNumber() {
        // 层级 = 纵深序，按「离主焦点的环上距离」排，不是按槽位号排：
        // 主焦点 100 > 正朝焦点走来的 Slot3 80 > 正下方 Slot2 60 > 刚离开焦点的 Slot1 40。
        // Slot1 之所以垫底：它是环上离焦点最远的一张，且正被后面三张依次超过。
        assertEquals(100f, GallerySlotGeometry.slot(0).z, 0f)
        assertEquals(80f, GallerySlotGeometry.slot(3).z, 0f)
        assertEquals(60f, GallerySlotGeometry.slot(2).z, 0f)
        assertEquals(40f, GallerySlotGeometry.slot(1).z, 0f)
        assertTrue("主焦点必须最高", GallerySlotGeometry.slot(0).z > GallerySlotGeometry.slot(3).z)
        assertTrue("走向焦点的 Slot3 必须高于正下方", GallerySlotGeometry.slot(3).z > GallerySlotGeometry.slot(2).z)
        assertTrue("正下方必须高于刚离开焦点的 Slot1", GallerySlotGeometry.slot(2).z > GallerySlotGeometry.slot(1).z)
    }

    @Test
    fun zIndexRisesWhileApproachingFocusInsteadOfSnappingAtTheEnd() {
        // 回归守卫（实机 bug）：卡片从 Slot3 滑向 Slot0 的整段里，层级必须**在动画过程中**
        // 就逐步上升，而不是等弹簧落位结束、环位置回绕到 0 的那一帧才从最低跳到最高。
        // container 0 在 scroll 3.0→4.0 期间正是走 Slot3→Slot0 这一段。
        val container = 0
        // 起点（刚好落位在 Slot3）与终点（落位在 Slot0）的层级
        val zStart = GallerySlotGeometry.zIndexForRank(
            GallerySlotGeometry.zRank(container, 3f)
        )
        val zEnd = GallerySlotGeometry.zIndexForRank(
            GallerySlotGeometry.zRank(container, 4f)
        )
        assertTrue("起点($zStart)必须低于终点($zEnd)", zStart < zEnd)

        // 行程中途就已经翻上来：不再是"最后一帧才跳"
        val zMid = GallerySlotGeometry.zIndexForRank(
            GallerySlotGeometry.zRank(container, 3.5f)
        )
        assertTrue("中点($zMid)必须已高于起点($zStart)", zMid > zStart)
        // 且中点已是全场最高（与正在离开焦点的卡完成交换）
        val topAtMid = (0 until 4).minOf { GallerySlotGeometry.zRank(it, 3.5f) }
        assertEquals("3.5 行程时 container 0 应已是最高层", 0, topAtMid)
    }

    @Test
    fun incomingCardOvertakesDepartingCardBeforeAnimationEnds() {
        // 实机 bug 的精确形态：动画中途（行程 1/2）来袭卡片就必须已经压过
        // 正在离开焦点的卡片，而不是等动画结束才翻上来。
        val incoming = 0   // 3.5 时环位置 3.5，正走向 Slot0
        val departing = 1  // 3.5 时环位置 0.5，正离开 Slot0
        val scroll = 3.5f
        val rankIn = GallerySlotGeometry.zRank(incoming, scroll)
        val rankOut = GallerySlotGeometry.zRank(departing, scroll)
        assertTrue("中途来袭卡片 rank($rankIn)应高于离开卡片 rank($rankOut)", rankIn < rankOut)
    }

    @Test
    fun focusCardKeepsTopZThroughoutEveryTransition() {
        // 任意时刻，离主焦点最近的卡片（环位置离 0 最近）层级必须最高。
        var scroll = 0f
        while (scroll < 8f) {
            val top = (0 until 4).minByOrNull { GallerySlotGeometry.zRank(it, scroll) }
            val nearest = (0 until 4).minByOrNull {
                val p = GallerySlotGeometry.ringPosition(it, scroll)
                minOf(p, 4f - p)
            }
            assertEquals(
                "scroll=$scroll 最高层级应属于离焦点最近的容器",
                nearest,
                top
            )
            scroll += 0.05f
        }
    }

    @Test
    fun zRankIsAPermutationOfZeroToThree() {
        // 4 张卡任何时刻都必须恰好占满 4 个不同的层级，不允许并列（并列会导致闪烁）
        var scroll = 0f
        while (scroll < 8f) {
            val ranks = (0 until 4).map { GallerySlotGeometry.zRank(it, scroll) }.sorted()
            assertEquals("scroll=$scroll 排名必须是 0,1,2,3 的排列", listOf(0, 1, 2, 3), ranks)
            scroll += 0.05f
        }
    }

    @Test
    fun zOrderEqualsSizeOrder() {
        // 大卡永远压小卡
        for (a in slots) {
            for (b in slots) {
                val sa = GallerySlotGeometry.slot(a)
                val sb = GallerySlotGeometry.slot(b)
                if (sa.scale > sb.scale) assertTrue("Slot$a 更大，z 必须更高", sa.z > sb.z)
                if (sa.scale < sb.scale) assertTrue("Slot$a 更小，z 必须更低", sa.z < sb.z)
            }
        }
    }

    @Test
    fun zIndexForSlotIsAlwaysPositive() {
        // 回归守卫：zIndex 为负会让卡片被 zIndex=0 的不透明背景层盖住（"图片全没了"）
        for (slot in -20..20) {
            assertTrue(
                "slot=$slot 的 zIndex 必须为正",
                GallerySlotGeometry.zIndexForSlot(slot) > 0f
            )
        }
    }

    // ---------- 恒定 4 容器 / 闭环 ----------

    @Test
    fun ringPositionAlwaysStaysInRange() {
        // 恒定 4 容器：环位置永远落在 [0,4)，不存在"第 5 个槽位"
        for (container in slots) {
            var scroll = -8f
            while (scroll <= 40f) {
                val p = GallerySlotGeometry.ringPosition(container, scroll)
                assertTrue(
                    "container=$container scroll=$scroll 环位置越界：$p",
                    p >= 0f && p < GallerySlotGeometry.SLOT_COUNT
                )
                scroll += 0.13f
            }
        }
    }

    @Test
    fun oneFullTurnBringsContainerBackToSameSlot() {
        // 滑一圈（+4）：同一容器回到同一槽位
        for (container in slots) {
            for (scroll in listOf(0f, 1f, 2.5f, 7f)) {
                val a = GallerySlotGeometry.ringPosition(container, scroll)
                val b = GallerySlotGeometry.ringPosition(container, scroll + 4f)
                assertEquals(
                    "container=$container scroll=$scroll 滑一圈应回到同一槽位",
                    a,
                    b,
                    1e-4f
                )
            }
        }
    }

    @Test
    fun containersCoverAllFourSlotsExactlyOnce() {
        // 任意进度下，4 个容器恰好各占一个槽位（不重不漏）
        for (scroll in listOf(0f, 0.3f, 1f, 2.7f, 3.9f, 12.4f)) {
            val occupied = (0 until GallerySlotGeometry.SLOT_COUNT)
                .map { GallerySlotGeometry.slotAt(it, scroll) }
                .sorted()
            assertEquals("scroll=$scroll 四容器应恰好覆盖 4 个槽位", listOf(0, 1, 2, 3), occupied)
        }
    }

    @Test
    fun rightSwipeMovesCardsClockwise() {
        // 向右滑（scroll 增大）→ 容器环位置增大（Slot0→1→2→3→0）
        for (container in slots) {
            val before = GallerySlotGeometry.ringPosition(container, 0.1f)
            val after = GallerySlotGeometry.ringPosition(container, 0.6f)
            val advanced = if (after >= before) after - before else after + 4f - before
            assertEquals("container=$container 应顺时针前进 0.5 个槽位", 0.5f, advanced, 1e-4f)
        }
    }

    // ---------- 延后一拍换图（本轮核心） ----------

    @Test
    fun crossfadeOnlyHappensOnSlot1ToSlot2Segment() {
        // 硬性要求：只有 Slot1→Slot2（右侧→正下方）这一段 crossfadeAlpha > 0，
        // 其余三段恒为 0 —— 卡片刚离开主焦点时封面纹丝不动。
        for (container in slots) {
            var scroll = 0f
            while (scroll <= 8f) {
                val p = GallerySlotGeometry.ringPosition(container, scroll)
                val slot = p.toInt()
                val alpha = GallerySlotGeometry.crossfadeAlpha(container, scroll)
                if (slot == 1) {
                    // 这一段应当在换图：alpha 随段内进度单调上升
                    val expected = p - slot
                    assertEquals(
                        "container=$container scroll=$scroll Slot1 段换图进度应为段内进度",
                        expected,
                        alpha,
                        1e-4f
                    )
                } else {
                    assertEquals(
                        "container=$container scroll=$scroll Slot$slot 段不得换图",
                        0f,
                        alpha,
                        0f
                    )
                }
                scroll += 0.05f
            }
        }
    }

    @Test
    fun mainFocusCardNeverChangesArtwork() {
        // 主焦点（Slot0）上的封面必须完全稳定：alpha 恒 0，且 songPair 两端相同。
        for (container in slots) {
            var scroll = 0f
            while (scroll <= 8f) {
                if (GallerySlotGeometry.slotAt(container, scroll) == 0) {
                    val pair = GallerySlotGeometry.songPair(container, scroll, size = 50)
                    assertEquals(
                        "container=$container scroll=$scroll 主焦点不得换图",
                        pair.first,
                        pair.second
                    )
                }
                scroll += 0.05f
            }
        }
    }

    @Test
    fun contentAdvancesOnlyWhenEnteringBottomSlot() {
        // 逐段检查：容器从 Slot j 走到 Slot j+1 时，歌曲下标只有 j==1 才会变。
        val size = 50
        for (container in slots) {
            for (seg in 0..6) {
                // 段内起点的槽位
                val pStart = GallerySlotGeometry.ringPosition(container, seg.toFloat())
                val slot = pStart.toInt()
                // 走完这一段后的容器
                val pEnd = GallerySlotGeometry.ringPosition(container, seg + 1f)
                val slotEnd = pEnd.toInt()
                val before = GallerySlotGeometry.songPair(container, seg.toFloat(), size).first
                val after = GallerySlotGeometry.songPair(container, seg + 1f, size).first
                val changed = before != after
                // 容器每段前进一个槽位；只有从 Slot1 进入 Slot2 时才允许换内容
                if (slot == 1) {
                    if (seg == 0) {
                        // 冷启动特例：seg=0 时 Slot1 的"上一首"是 -1，被 displaySongIndex
                        // 归一成第 4 首（下标 3）；而它滑到 Slot2 时本来也该显示第 4 首。
                        // 两者相同 → 这一段**不换图**，冷启动因此完全没有跳变。
                        assertTrue(
                            "冷启动 seg=0 的 Slot1→Slot2 不应换图（当前：$before→$after）",
                            !changed
                        )
                    } else {
                        assertTrue(
                            "container=$container seg=$seg 从 Slot1→Slot$slotEnd 必须换图",
                            changed
                        )
                        assertEquals(
                            "换图应前进 4 首（闭环复用槽位的必然结果）",
                            (before + 4) % size,
                            after
                        )
                    }
                } else {
                    assertTrue(
                        "container=$container seg=$seg Slot$slot→$slotEnd 不得换图（当前：$before→$after）",
                        !changed
                    )
                }
            }
        }
    }

    @Test
    fun slotZeroShowsCurrentSegment() {
        // 主焦点显示当前段对应的歌
        val size = 50
        for (seg in 0..8) {
            // 找到此刻处于 Slot0 的容器
            val container = (0 until 4).first {
                GallerySlotGeometry.slotAt(it, seg.toFloat()) == 0
            }
            val pair = GallerySlotGeometry.songPair(container, seg.toFloat(), size)
            assertEquals("seg=$seg 主焦点应显示当前段歌曲", seg % size, pair.first)
        }
    }

    // ---------- 无上界 ----------

    @Test
    fun scrollIndexHasNoUpperBound() {
        // 回归守卫：曾经把 scrollIndex 夹到 songs.size-1，导致"滑到第十首就滑不动"。
        // 现在只保留下界 0，任意大的 scroll 都必须能正常映射。
        val size = 10
        for (scroll in listOf(0f, 9f, 10f, 25f, 100f, 1000f)) {
            for (container in slots) {
                val pair = GallerySlotGeometry.songPair(container, scroll, size)
                assertTrue("scroll=$scroll 歌曲下标越界：${pair.first}", pair.first in 0 until size)
                assertTrue("scroll=$scroll 歌曲下标越界：${pair.second}", pair.second in 0 until size)
            }
        }
        // 第 10 首之后仍可继续前进（内容继续变化）
        val a = GallerySlotGeometry.songPair(0, 9f, size)
        val b = GallerySlotGeometry.songPair(0, 10f, size)
        assertTrue("越过队列长度后内容仍应继续推进", a.first != b.first || a.second != b.second)
    }

    @Test
    fun wrapSongHandlesEmptyList() {
        // 队列首拉期间 songs 为空，环形取模绝不能抛异常
        for (index in -5..5) {
            assertEquals(0, GallerySlotGeometry.wrapSong(index, 0))
        }
    }

    @Test
    fun cardContentStableWhileQueueGrowsOnColdStart() {
        // 回归守卫（实机 bug：冷启动封面连闪 5→10→15→20 次）：
        // PersonalFmObject.load() 每拉到一批 5 首就增量发布 songs，size 依次 5/10/15/20。
        // 卡片取歌若按 size 环形取模，负下标（slot1 的 seg-1）会变成 size-1，
        // 于是每次补货都换一张封面 —— 用户看到的就是「右下角封面随数字一起闪」。
        // 契约：冷启动期间**已经可见的卡片内容不得因队列变长而改变**。
        val ids = (0 until 40).map { "hash-$it" }
        fun contentAt(size: Int): List<Int> =
            (0 until GallerySlotGeometry.SLOT_COUNT).map { container ->
                GallerySlotGeometry.songPair(container, 0f, size).first
            }
        val at5 = contentAt(5)
        for (size in listOf(10, 15, 20, 40)) {
            assertEquals("size=$size 时首屏四张卡的内容必须与 size=5 时一致", at5, contentAt(size))
        }
        // 且不得出现负下标或越界
        for (size in 1..20) {
            for (container in slots) {
                val pair = GallerySlotGeometry.songPair(container, 0f, size)
                assertTrue("size=$size container=$container old=${pair.first}", pair.first in 0 until size)
                assertTrue("size=$size container=$container new=${pair.second}", pair.second in 0 until size)
            }
        }
    }

    @Test
    fun negativeSongIndexDoesNotWrapToQueueTail() {
        // 精确根因：scroll=0 时槽位表是 [0, -1, 2, 1]，那个 -1 绝不能映射到 size-1
        // （映射到队尾 = 冷启动凭空出现"最后一首"的封面，且随 size 变化而闪）。
        for (size in listOf(5, 10, 15, 20)) {
            assertTrue(
                "负下标 -1 不得回绕到队尾（size=$size）",
                GallerySlotGeometry.displaySongIndex(-1, size) != size - 1
            )
            // 也不得夹到 0（那会与主焦点封面重复，实机用户明确反馈过）
            assertTrue(
                "负下标 -1 不得与主焦点(0)重复（size=$size）",
                GallerySlotGeometry.displaySongIndex(-1, size) != 0
            )
        }
        // 队列只有 1 首时也不得越界
        assertEquals(0, GallerySlotGeometry.displaySongIndex(-1, 1))
        assertEquals(0, GallerySlotGeometry.displaySongIndex(-1, 0))
        // 正向越界仍环回：电台式画廊要能无限前进
        assertEquals(0, GallerySlotGeometry.displaySongIndex(20, 20))
        assertEquals(3, GallerySlotGeometry.displaySongIndex(23, 20))
    }

    @Test
    fun coldStartRightCardShowsFourthSongNotARepeatOfFocus() {
        // 用户实机反馈的精确期望：冷启动进入画廊时，右侧那张（Slot1）必须是
        // **第 4 首**的封面，而不是与主焦点重复、也不是队尾第 20 首。
        val size = 20
        val pair = GallerySlotGeometry.songPair(
            containerIndex = 1,   // 冷启动时 container1 的环位置是 1 → Slot1
            scroll = 0f,
            size = size
        )
        assertEquals("冷启动右侧卡应为第 4 首（下标 3）", 3, pair.first)

        // 且四张卡在冷启动时互不重复（任何两张同图都是肉眼可见的 bug）
        val contents = (0 until GallerySlotGeometry.SLOT_COUNT).map {
            GallerySlotGeometry.songPair(it, 0f, size).first
        }
        assertEquals(
            "冷启动四张卡的内容必须互不相同",
            contents.size,
            contents.toSet().size
        )
        assertEquals("冷启动槽位内容应为 [1,4,3,2] 首（下标 [0,3,2,1]）", listOf(0, 3, 2, 1), contents)
    }

    @Test
    fun noCrossfadeWhenContentDoesNotChange() {
        // 回归守卫（实机 bug：最后一张卡"逐渐消失 → 落位后又突然出现"，阴影还在）。
        //
        // 渐变是「上层旧图 alpha 1→0 + 底层新图 alpha 0→1」的双层结构，而底层只在
        // 新旧 URL 不同时才绘制。若这一段前后内容其实是同一首，上层照样淡出、底层
        // 又没画，卡片就变成透明的空框；段末 crossfade 归 0，alpha 弹回 1 → 突然出现。
        //
        // 契约：**内容不变时换图进度必须恒为 0**，任何 container/scroll 都成立。
        val size = 20
        var scroll = 0f
        while (scroll <= 8f) {
            for (container in slots) {
                val pair = GallerySlotGeometry.songPair(container, scroll, size)
                if (pair.first == pair.second) {
                    assertEquals(
                        "container=$container scroll=$scroll 内容相同（${pair.first}）时不得渐变",
                        0f,
                        GallerySlotGeometry.crossfadeAlpha(container, scroll, size),
                        0f
                    )
                }
            }
            scroll += 0.05f
        }
    }

    @Test
    fun coldStartFirstSwipeDoesNotBlankAnyCard() {
        // 精确复现用户描述的场景：数字为 1（seg=0）时向右滑，container1 从 Slot1
        // 滑向 Slot2。它在这一段前后都是第 4 首，因此整段 alpha 必须保持 1（不淡出）。
        val size = 20
        val container = 1
        var scroll = 0f
        while (scroll <= 1f) {
            val pair = GallerySlotGeometry.songPair(container, scroll, size)
            val crossfade = GallerySlotGeometry.crossfadeAlpha(container, scroll, size)
            val topAlpha = 1f - crossfade   // 上层（实际可见的那层）的不透明度
            assertTrue(
                "scroll=$scroll 冷启动首段上层封面不得淡出（alpha=$topAlpha，内容 ${pair.first}→${pair.second}）",
                topAlpha > 0.999f
            )
            scroll += 0.02f
        }
    }

    @Test
    fun wrapSongNeverGoesNegative() {
        for (size in listOf(1, 2, 3, 7, 20)) {
            for (index in -40..40) {
                val v = GallerySlotGeometry.wrapSong(index, size)
                assertTrue("size=$size index=$index -> $v 越界", v in 0 until size)
            }
        }
    }

    // ---------- 「转着圈滑」的拖动投影 ----------

    private val stageW = 1280f
    private val triggerPx = stageW * 0.25f

    @Test
    fun horizontalDragAtRingRightStillMovesRing() {
        // 回归守卫：任何位置横滑都必须有反应（旧"纯圆盘"模型在右侧会完全失效）。
        // 圆环右侧 ≈ (0.27W, 0.20W)，相对舞台中心即 (0.27-0.5, 0.20-0.5)W。
        val fingerX = (0.27f - 0.5f) * stageW
        val fingerY = (0.20f - 0.5f) * stageW
        val delta = GallerySlotGeometry.dragToRingDelta(
            dx = 80f, dy = 0f,
            fingerX = fingerX, fingerY = fingerY,
            stageWidthPx = stageW, triggerPx = triggerPx
        )
        assertTrue("圆环右侧横滑必须有响应，实际 ringDelta=$delta", abs(delta) > 1e-4f)
        assertTrue("向右滑应前进（正方向），实际 $delta", delta > 0f)
    }

    @Test
    fun dragAlongArcAdvancesRingInSameDirectionAsFinger() {
        // 沿圆弧顺时针拖动 → 环应前进。
        // 取一个离枢轴足够远的点（混合权重=1，纯切线段）。
        val angle = 0.6f // 弧度，指向右下
        val radiusPx = 0.35f * stageW
        // 枢轴在**相对舞台中心**的坐标系里就是 PIVOT_X/PIVOT_Y * W
        // （几何层所有 offset 都是相对中心，未再减半宽）。
        val pivotX = 0.055f * stageW
        val pivotY = 0.095f * stageW
        val fingerX = pivotX + radiusPx * kotlin.math.cos(angle)
        val fingerY = pivotY + radiusPx * kotlin.math.sin(angle)
        // 顺时针切线方向（屏幕坐标 y 向下）= (-sin a, cos a)
        val step = 8f
        val dx = -kotlin.math.sin(angle) * step
        val dy = kotlin.math.cos(angle) * step
        val delta = GallerySlotGeometry.dragToRingDelta(
            dx = dx, dy = dy,
            fingerX = fingerX, fingerY = fingerY,
            stageWidthPx = stageW, triggerPx = triggerPx
        )
        assertTrue("沿弧顺时针拖动应前进，实际 $delta", delta > 0f)
    }

    @Test
    fun dragNearPivotFallsBackToHorizontal() {
        // 靠近枢轴：切线方向不稳定，必须平滑退回横向（不能"怎么滑都没反应"）。
        val pivotX = 0.055f * stageW
        val pivotY = 0.095f * stageW
        val delta = GallerySlotGeometry.dragToRingDelta(
            dx = 60f, dy = 0f,
            fingerX = pivotX, fingerY = pivotY,
            stageWidthPx = stageW, triggerPx = triggerPx
        )
        // 纯横向口径：60px / triggerPx(320px) = 0.1875 槽位
        assertEquals("枢轴处应退化为纯横向", 60f / triggerPx, delta, 1e-3f)
    }

    @Test
    fun radialBlendIsMonotonicAndBounded() {
        var prev = -1f
        var r = 0f
        while (r <= 0.6f) {
            val b = GallerySlotGeometry.radialBlend(r * stageW, stageW)
            assertTrue("blend 必须落在 [0,1]，r=$r -> $b", b in 0f..1f)
            assertTrue("blend 应随半径单调不减，r=$r prev=$prev b=$b", b >= prev - 1e-6f)
            prev = b
            r += 0.02f
        }
        assertEquals("枢轴处完全横向", 0f, GallerySlotGeometry.radialBlend(0f, stageW), 0f)
        assertEquals("远处完全切线", 1f, GallerySlotGeometry.radialBlend(0.5f * stageW, stageW), 1e-4f)
    }

    @Test
    fun dragProjectionScalesWithStageWidth() {
        // 同一比例位置、同一比例位移，在小屏与大屏上应得到相同的环位移（与像素无关）。
        fun deltaAt(w: Float): Float {
            val t = w * 0.25f
            return GallerySlotGeometry.dragToRingDelta(
                dx = w * 0.05f, dy = 0f,
                fingerX = (0.27f - 0.5f) * w, fingerY = (0.20f - 0.5f) * w,
                stageWidthPx = w, triggerPx = t
            )
        }
        assertEquals(deltaAt(1280f), deltaAt(800f), 1e-3f)
    }

    // ---------- 圆环几何 ----------
    @Test
    fun fourSlotsFormClosedRing() {
        // 四个槽位围绕枢轴构成圆环：极角单调递增、相邻角差 < 180°、总扫角 360°
        val angles = (0 until 4).map { GallerySlotGeometry.angleOf(GallerySlotGeometry.slot(it)) }
        var total = 0f
        for (i in 0 until 4) {
            var delta = angles[(i + 1) % 4] - angles[i]
            while (delta > PI.toFloat()) delta -= (2f * PI).toFloat()
            while (delta < -PI.toFloat()) delta += (2f * PI).toFloat()
            assertTrue("Slot$i → Slot${(i + 1) % 4} 角差必须为正（顺时针）：$delta", delta > 0f)
            assertTrue("Slot$i → Slot${(i + 1) % 4} 角差必须 < π：$delta", delta < PI.toFloat())
            total += delta
        }
        assertEquals(
            "四槽位总扫角必须为 360°（闭环）",
            (2f * PI.toFloat()),
            total,
            1e-3f
        )
    }

    @Test
    fun geometryAtIntegerSlotsEqualsSlotTable() {
        for (slot in 0 until 4) {
            assertEquals(
                "geometry($slot) 应精确等于槽位表",
                GallerySlotGeometry.slot(slot),
                GallerySlotGeometry.geometry(slot.toFloat())
            )
        }
    }

    @Test
    fun geometryWrapsAroundRing() {
        // geometry 对环形位置取模：4.5 等价于 0.5
        val a = GallerySlotGeometry.geometry(0.5f)
        val b = GallerySlotGeometry.geometry(4.5f)
        assertEquals("环形位置 4.5 应等价于 0.5 (x)", a.offsetX, b.offsetX, 1e-4f)
        assertEquals("环形位置 4.5 应等价于 0.5 (y)", a.offsetY, b.offsetY, 1e-4f)
    }

    @Test
    fun positionFollowsArcNotStraightLine() {
        // 中间点到枢轴的距离 == 两端半径的线性插值（角度走弧），
        // 且中点不落在弦上（否则就是直线插值）。
        for (from in 0 until 4) {
            val a = GallerySlotGeometry.slot(from)
            val b = GallerySlotGeometry.slot(from + 1)
            val mid = GallerySlotGeometry.geometry(from + 0.5f)
            val radiusA = GallerySlotGeometry.radiusOf(a)
            val radiusB = GallerySlotGeometry.radiusOf(b)
            assertEquals(
                "Slot$from→${from + 1} 中点半径应为两端线性插值",
                (radiusA + radiusB) / 2f,
                GallerySlotGeometry.radiusOf(mid),
                1e-4f
            )
            val chordX = (a.offsetX + b.offsetX) / 2f
            val chordY = (a.offsetY + b.offsetY) / 2f
            val deviation = hypot(mid.offsetX - chordX, mid.offsetY - chordY)
            assertTrue(
                "Slot$from→${from + 1} 的中点在弦上（仍是折线）：dev=$deviation",
                deviation > 1e-3f
            )
        }
    }

    @Test
    fun geometryIsContinuousAcrossSlotBoundaries() {
        for (slot in 0 until 4) {
            val left = GallerySlotGeometry.geometry(slot + 0.9999f)
            val right = GallerySlotGeometry.geometry((slot + 1).toFloat())
            assertEquals("Slot$slot 右极限 offsetX", left.offsetX, right.offsetX, 1e-2f)
            assertEquals("Slot$slot 右极限 offsetY", left.offsetY, right.offsetY, 1e-2f)
        }
    }

    @Test
    fun easingHasZeroVelocityAtSlotBoundaries() {
        // smoothStep 的效果：槽位交界处速度趋近 0（无速度折点）
        for (slot in 0 until 4) {
            val eps = 1e-3f
            val at = GallerySlotGeometry.geometry(slot.toFloat())
            val before = GallerySlotGeometry.geometry(slot - eps)
            val after = GallerySlotGeometry.geometry(slot + eps)
            val vIn = hypot(at.offsetX - before.offsetX, at.offsetY - before.offsetY) / eps
            val vOut = hypot(after.offsetX - at.offsetX, after.offsetY - at.offsetY) / eps
            val mid = GallerySlotGeometry.geometry(slot + 0.5f)
            val midPrev = GallerySlotGeometry.geometry(slot + 0.5f - eps)
            val vMid = hypot(mid.offsetX - midPrev.offsetX, mid.offsetY - midPrev.offsetY) / eps
            assertTrue(
                "Slot$slot 交界处速度(in=$vIn out=$vOut)应小于中段速度($vMid)",
                vIn < vMid && vOut < vMid
            )
        }
    }

    @Test
    fun cardClusterFitsHorizontally() {
        var left = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        for (slot in 0 until 4) {
            val s = GallerySlotGeometry.slot(slot)
            val half = GallerySlotGeometry.BASE_SIZE_FRACTION * 0.5f * s.scale
            left = minOf(left, s.offsetX - half)
            right = maxOf(right, s.offsetX + half)
        }
        assertTrue("卡片簇左越界：left=$left", left > -0.5f)
        assertTrue("卡片簇右越界：right=$right", right < 0.5f)
    }

    @Test
    fun baseSizeIsAbout205Dp() {
        val baseSizeDp = 1280f * GallerySlotGeometry.BASE_SIZE_FRACTION / (520f / 160f)
        assertTrue("baseSize=${baseSizeDp}dp 应约为 205dp", baseSizeDp in 200f..210f)
    }

    // ---------- 橡皮筋 / 文字 ----------

    @Test
    fun rubberBandStaysBounded() {
        val above = GallerySlotGeometry.rubberBand(2.5f, 0f, 2f)
        val below = GallerySlotGeometry.rubberBand(-0.5f, 0f, 2f)
        assertTrue("上越界应大于上边界", above > 2f)
        assertTrue("上越界位移必须收敛在 0.46 槽位内", above - 2f < 0.46f)
        assertTrue("下越界应小于下边界", below < 0f)
        assertTrue("下越界位移必须收敛在 0.46 槽位内", -below < 0.46f)
        assertEquals(1.25f, GallerySlotGeometry.rubberBand(1.25f, 0f, 2f), 0f)
    }

    @Test
    fun textCrossfadeKeepsConstantVisibility() {
        var o = 0f
        while (o <= 1f) {
            val sum = GallerySlotGeometry.textAlpha(o) + GallerySlotGeometry.textAlpha(o - 1f)
            assertEquals("offset=$o 处两层文字可见性之和应为 1", 1f, sum, 1e-5f)
            o += 0.05f
        }
        assertEquals(1f, GallerySlotGeometry.textAlpha(0f), 0f)
        assertEquals(0f, GallerySlotGeometry.textAlpha(-1f), 0f)
        assertEquals(0f, GallerySlotGeometry.textTranslationYDp(0f), 0f)
    }
}
