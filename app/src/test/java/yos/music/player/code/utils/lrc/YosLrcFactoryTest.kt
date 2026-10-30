package yos.music.player.code.utils.lrc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import yos.music.player.data.objects.MediaViewModelObject
import java.util.Base64

class YosLrcFactoryTest {
    private val factory = YosLrcFactory()

    @Test
    fun sameTimestampLrcKeepsInlineTranslation() {
        val entries = factory.formatLrcEntries("[00:01.00]Hello\n[00:01.00]你好")

        assertEquals(1, entries.size)
        assertEquals("Hello", entries.single().mainLyric.joinToString("") { it.second })
        assertEquals("你好", entries.single().translation)
    }

    @Test
    fun separateTranslationLrcIsMatchedByTimestamp() {
        val entries = factory.formatLrcEntries(
            "[00:01.00]Hello\n[00:03.00]World",
            "[00:01.20]你好\n[00:03.10]世界"
        )

        assertEquals(listOf("你好", "世界"), entries.map { it.translation })
    }

    @Test
    fun unicodeEscapedTranslationIsDecodedBeforeRendering() {
        val entry = factory.formatLrcEntries(
            "[00:01.00]If you",
            "[00:01.00]\\U82E5\\U4F60"
        ).single()

        assertEquals("若你", entry.translation)
    }

    @Test
    fun singleLanguageLrcHasNoSyntheticTranslation() {
        val entry = factory.formatLrcEntries("[00:01.00]Hello").single()

        assertNull(entry.translation)
        assertEquals("Hello", entry.mainLyric.joinToString("") { it.second })
    }

    @Test
    fun krcKeepsWordTimingWhenTranslationHasWholeSecondTimestamp() {
        val entry = factory.formatKrcEntries(
            "[1000,2000]<0,500,0>H<500,500,0>i",
            "[00:01]你好"
        ).single()

        assertEquals("你好", entry.translation)
        assertTrue(entry.mainLyric.map { it.first }.distinct().size > 2)
    }

    @Test
    fun embeddedTranslationsSkipOriginalSingerMetadataRows() {
        val languageJson = """
            {"content":[{"language":0,"lyricContent":[["译文一"],["译文二"]]}]}
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(languageJson.toByteArray())
        val entries = factory.formatKrcEntries(
            """
                [language:$encoded]
                [0,500]原曲歌手：歌手名
                [1000,1000]<0,500,0>First<500,500,0>line
                [3000,1000]<0,500,0>Second<500,500,0>line
            """.trimIndent()
        )

        assertEquals(listOf("Firstline", "Secondline"), entries.map { it.mainLyric.joinToString("") { pair -> pair.second } })
        assertEquals(listOf("译文一", "译文二"), entries.map { it.translation })
    }

    @Test
    fun emptyEmbeddedTranslationSlotDoesNotShiftLaterLines() {
        val languageJson = """{"content":[{"language":0,"lyricContent":[["译文一"],[],["译文三"]]}]}"""
        val encoded = Base64.getEncoder().encodeToString(languageJson.toByteArray())
        val entries = factory.formatKrcEntries(
            """
                [language:$encoded]
                [1000,1000]<0,500,0>First<500,500,0>line
                [3000,1000]<0,500,0>Middle<500,500,0>line
                [5000,1000]<0,500,0>Third<500,500,0>line
            """.trimIndent()
        )

        assertEquals(listOf("译文一", null, "译文三"), entries.map { it.translation })
    }

    @Test
    fun krcEndMarkerIsNotUsedAsTranslation() {
        val entry = factory.formatKrcEntries(
            "[1000,2000]<0,500,0>H<500,500,0>i"
        ).single()

        assertNull(entry.translation)
        assertEquals("Hi", entry.mainLyric.joinToString("") { it.second })
        assertEquals(3000f, entry.mainLyric.last().first, 0.0f)
    }

    @Test
    fun krcLanguagePayloadProvidesTranslation() {
        val languageJson = """
            {"content":[
              {"language":0,"lyricContent":[["你好"]]},
              {"language":1,"lyricContent":[["ni","hao"]]}
            ]}
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(languageJson.toByteArray())
        val entries = factory.formatKrcEntries(
            "[language:$encoded]\n[1000,2000]<0,500,0>H<500,500,0>i"
        )

        assertEquals("你好", entries.single().translation)
    }

    @Test
    fun krcLanguagePayloadProvidesRomanization() {
        val languageJson = """
            {"content":[
              {"language":0,"lyricContent":[["你好"]]},
              {"language":1,"lyricContent":[["ni","hao"]]}
            ]}
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(languageJson.toByteArray())
        val entry = factory.formatKrcEntries(
            "[language:$encoded]\n[1000,2000]<0,500,0>H<500,500,0>i"
        ).single()

        assertEquals("你好", entry.translation)
        assertEquals("ni hao", entry.romanization)
    }

    @Test
    fun singleElementRomanizationRowIsNotTreatedAsTranslation() {
        // language=1 即便整行只有一段文本，也归罗马音、不得当翻译（守住既有语义）
        val languageJson = """{"content":[{"language":1,"lyricContent":[["若你"]]}]}"""
        val encoded = Base64.getEncoder().encodeToString(languageJson.toByteArray())
        val entry = factory.formatKrcEntries(
            "[language:$encoded]\n[1000,2000]<0,500,0>H"
        ).single()

        assertNull(entry.translation)
        assertEquals("若你", entry.romanization)
    }

    @Test
    fun krcLanguagePayloadAllowsFieldsAfterLyricContent() {
        val languageJson = """
            {"content":[{"language":0,"lyricContent":[["你好"]],"extra":true}]}
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(languageJson.toByteArray())
        val entry = factory.formatKrcEntries(
            "[language:$encoded]\n[1000,2000]<0,500,0>H"
        ).single()

        assertEquals("你好", entry.translation)
    }

    @Test
    fun romanizationPayloadIsNotShownAsTranslation() {
        val languageJson = """{"content":[{"language":1,"lyricContent":[["若你"]]}]}"""
        val encoded = Base64.getEncoder().encodeToString(languageJson.toByteArray())
        val entry = factory.formatKrcEntries(
            "[language:$encoded]\n[1000,2000]<0,500,0>H"
        ).single()

        assertNull(entry.translation)
    }

    @Test
    fun explicitTranslationTakesPriorityOverEmbeddedKrcTranslation() {
        val languageJson = """{"content":[{"language":0,"lyricContent":[["内嵌译文"]]}]}"""
        val encoded = Base64.getEncoder().encodeToString(languageJson.toByteArray())
        val entries = factory.formatKrcEntries(
            "[language:$encoded]\n[1000,2000]<0,500,0>H<500,500,0>i",
            "[00:01.00]显式译文"
        )

        assertEquals("显式译文", entries.single().translation)
    }

    @Test
    fun malformedLanguagePayloadDegradesWithoutDroppingKrc() {
        val entries = factory.formatKrcEntries(
            "[language:not-valid-base64]\n[1000,2000]<0,500,0>H"
        )

        assertTrue(entries.isNotEmpty())
        assertNull(entries.single().translation)
    }

    @Test
    fun romajiEntryWithSameLanguageStillClassifiesByStructure() {
        // 《Precious My Heroes》(fox capture plan) 实测结构：罗马音与翻译都是
        // language=0，且罗马音条目在前。按结构分类：多元素行=罗马音，单元素行=翻译。
        val languageJson = """
            {"content":[
              {"language":0,"lyricContent":[["fo","x cap"],["ture"]]},
              {"language":0,"lyricContent":[["译文一"],["译文二"]]}
            ]}
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(languageJson.toByteArray())
        val entries = factory.formatKrcEntries(
            """
                [language:$encoded]
                [1000,1000]<0,500,0>First<500,500,0>line
                [3000,1000]<0,500,0>Second<500,500,0>line
            """.trimIndent()
        )

        assertEquals(listOf("译文一", "译文二"), entries.map { it.translation })
        assertEquals(listOf("fo x cap", "ture"), entries.map { it.romanization })
    }

    // ---- 逐字时间戳 LRC（每个字符各带 [mm:ss.xxx] 标签，常见于 FLAC 内嵌歌词）----

    @Test
    fun wordTimedLrcMergesIntoSingleKaraokeEntry() {
        val entries = factory.formatLrcEntries("[00:10.000]夜[00:10.500]奔[00:11.000] - [00:11.500]黄")

        assertEquals(1, entries.size)
        val entry = entries.single()
        // 词间空格保留（" - " 不被 trim 吞掉），不再一字一行
        assertEquals("夜奔 - 黄", entry.mainLyric.joinToString("") { it.second })
        // 词级形状：行起点锚点 + 逐词 + 尾部空锚点（与 KRC 逐词路径同形）
        assertEquals(listOf("", "夜", "奔", " - ", "黄", ""), entry.mainLyric.map { it.second })
        // 词时间语义 = 该词高亮结束时间 = 下一个标签时间
        assertEquals(10000f, entry.mainLyric.first().first, 0f)
        assertEquals(10500f, entry.mainLyric[1].first, 0f)
        assertEquals(11000f, entry.mainLyric[2].first, 0f)
        assertEquals(11500f, entry.mainLyric[3].first, 0f)
    }

    @Test
    fun wordTimedLrcKeepsEnglishTokensIntact() {
        // 夜奔-黄诗扶 FLAC 内嵌歌词的真实两行：英文按整词带标签，中文按字带标签
        val lrc = "[00:02.108]Tureleon[00:02.176]郭[00:02.244]超[00:02.312]\n" +
            "[00:02.449] [00:02.517]Guitar：[00:02.585]Tureleon[00:02.653]郭[00:02.721]超[00:02.789]"
        val entries = factory.formatLrcEntries(lrc)

        assertEquals(2, entries.size)
        assertEquals("Tureleon郭超", entries[0].mainLyric.joinToString("") { it.second })
        assertTrue(entries[0].mainLyric.size <= 5)
        assertEquals(" Guitar：Tureleon郭超", entries[1].mainLyric.joinToString("") { it.second })
        // 前行末词的结束时间由下一行首个时间戳回填
        assertEquals(2449f, entries[0].mainLyric.last().first, 0f)
    }

    @Test
    fun lastWordOfFinalWordTimedLineFallsBackToStartPlusThreeSeconds() {
        val entry = factory.formatLrcEntries("[00:10.000]夜[00:10.500]奔").single()

        assertEquals(13000f, entry.mainLyric.last().first, 0f)
    }

    @Test
    fun wordTimedLrcTranslationMatchesByTimestamp() {
        val entries = factory.formatLrcEntries(
            "[00:10.000]夜[00:10.500]奔\n[00:20.000]第[00:20.500]二[00:21.000]行",
            "[00:10.200]逃\n[00:20.100]Line two"
        )

        assertEquals(listOf("逃", "Line two"), entries.map { it.translation })
    }

    @Test
    fun classicMultiTimestampLrcBehaviorIsUnchanged() {
        val entries = factory.formatLrcEntries("[00:01.00][00:02.00]Hello\n[00:03.00]World")

        assertEquals(listOf("Hello", "World"), entries.map { it.mainLyric.joinToString("") { pair -> pair.second } })
        assertEquals(listOf(2000f, 3000f), entries.map { it.startTime })
        // 行级行保持两元素形状（行级高亮，无词级时间轴）
        assertTrue(entries.all { it.mainLyric.size == 2 })
    }

    @Test
    fun plainTextFallbackPathIsUnchangedByWordTimedParsing() {
        val entries = factory.formatLrcEntriesWithFallback("第一行\n第二行", 20_000)

        assertEquals(2, entries.size)
        assertEquals(listOf("第一行", "第二行"), entries.map { it.mainLyric.joinToString("") { pair -> pair.second } })
        assertEquals(listOf(0f, 10000f), entries.map { it.startTime })
        assertTrue(entries.all { it.mainLyric.size == 2 })
    }

    // ---- A2 增强型 LRC（行级 [mm:ss] + 字级 <mm:ss> 尖括号标签，12.31.flac 内嵌歌词实测）----

    @Test
    fun angleWordTimedA2LrcMergesIntoKaraokeEntry() {
        // 字级标签不再漏进正文；行末孤立标签（其后无文本）作为末词的结束时间
        val entries = factory.formatLrcEntries(
            "[00:32.960] <00:32.960>欠<00:33.182>你<00:33.389>还<00:34.456>路<00:35.456>"
        )

        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals("欠你还路", entry.mainLyric.joinToString("") { it.second })
        assertTrue(!entry.mainLyric.any { it.second.contains('<') })
        assertEquals(listOf("", "欠", "你", "还", "路", ""), entry.mainLyric.map { it.second })
        assertEquals(32960f, entry.mainLyric.first().first, 0f)
        assertEquals(33182f, entry.mainLyric[1].first, 0f)
        assertEquals(35456f, entry.mainLyric[4].first, 0f)
        assertEquals(35456f, entry.mainLyric.last().first, 0f)
    }

    @Test
    fun a2LrcLastWordWithoutTrailingTagFallsBackToNextLineStart() {
        val entries = factory.formatLrcEntries(
            "[00:10.000] <00:10.000>夜<00:10.500>奔\n[00:12.000] <00:12.000>第<00:12.500>二"
        )

        assertEquals(2, entries.size)
        assertEquals(12000f, entries[0].mainLyric.last().first, 0f)
        // 末行末词无后继标签 → 既有兜底 start+3s
        assertEquals(15000f, entries[1].mainLyric.last().first, 0f)
    }

    @Test
    fun a2LrcRealEmbeddedSampleHasNoTimestampsInBody() {
        // 12.31.flac（郑润泽）VORBIS_COMMENT LYRICS 的真实前四行，含无行级标签接续行
        val sample = """
            [00:00.000] <00:00.000>12.31 <00:00.033>- <00:00.066>郑<00:00.099>润<00:00.132>泽<00:00.165>
            [00:00.165] <00:00.165>作<00:00.198>词<00:00.231>: <00:00.264>郑<00:00.297>润<00:00.330>泽<00:00.363>
            [00:32.960] <00:32.960>欠<00:33.182>你<00:33.389>还<00:33.597>没<00:33.856>走<00:34.201>的<00:34.456>路<00:35.456>
            [00:35.745] <00:35.745>就<00:36.043>这<00:36.249>样<00:36.715>结<00:37.016>束<00:38.016>
        """.trimIndent()
        val entries = factory.formatLrcEntriesWithFallback(sample, 210_000)

        // 制作信息行（`作词:`）与 KRC 路径一致地被过滤，不上屏
        assertEquals(3, entries.size)
        assertEquals(listOf("12.31 - 郑润泽", "欠你还没走的路", "就这样结束"),
            entries.map { it.mainLyric.joinToString("") { pair -> pair.second }.trim() })
        assertTrue(entries.all { entry -> entry.mainLyric.all { !it.second.contains('<') } })
        // 词级形状（行起点锚点 + 逐词 + 尾部空锚点），走逐字卡拉OK渲染
        assertTrue(entries[1].mainLyric.size > 4)
    }

    @Test
    fun classicLrcWithNonTimestampAngleBracketsIsUnchanged() {
        // 尖括号非时间戳形态（无 mm:ss 结构）不得触发词级分支
        val entries = factory.formatLrcEntries("[00:01.00]爱<3>你")

        assertEquals(1, entries.size)
        assertEquals("爱<3>你", entries.single().mainLyric.joinToString("") { it.second })
        assertEquals(2, entries.single().mainLyric.size)
    }

    // ---- 演唱者报幕标签（左右对唱排版） ----

    @Test
    fun krcTitleTokenEndingWithColonIsNotSingerLabel() {
        // Re:Re: 的真实 KRC：首行首词是歌名 "Re:Re: "，冒号收尾但不是报幕标签
        val entries = factory.formatKrcEntries(
            """
                [40,1780]<0,240,0>Re:Re: <240,260,0>- <500,250,0>亚洲
                [32020,2910]<0,250,0>君<250,220,0>を
                [34930,3140]<0,250,0>途<250,190,0>切
            """.trimIndent()
        )

        assertEquals(3, entries.size)
        assertTrue(entries[0].mainLyric.joinToString("") { it.second }.contains("Re:Re: "))
        assertEquals(listOf(false, false, false), MediaViewModelObject.otherSideForLines.toList())
    }

    @Test
    fun firstSingerLabelStaysLeftAndLaterLabelsAlternate() {
        val entries = factory.formatKrcEntries(
            """
                [1000,1000]<0,300,0>男：<300,300,0>君を
                [2000,1000]<0,300,0>女：<300,300,0>僕を
                [3000,1000]<0,300,0>男：<300,300,0>また
            """.trimIndent()
        )

        assertEquals(3, entries.size)
        assertEquals(listOf(false, true, false), MediaViewModelObject.otherSideForLines.toList())
        // 报幕词从正文里摘掉，只留歌词
        assertEquals("君を", entries[0].mainLyric.joinToString("") { it.second })
    }

    @Test
    fun repeatedSameSingerLabelNeverSwitchesSide() {
        factory.formatKrcEntries(
            """
                [1000,1000]<0,300,0>男：<300,300,0>一
                [2000,1000]<0,300,0>男：<300,300,0>二
            """.trimIndent()
        )

        assertEquals(listOf(false, false), MediaViewModelObject.otherSideForLines.toList())
    }

    // ---- 制作人员署名不得被当成报幕标签（独唱歌曲整首跑右边） ----

    @Test
    fun creditLinesEachAppearingOnceNeverSwitchSide() {
        // 万里长风（周深独唱）的真实署名行：每行只出现一次，曾把整首歌词翻到右侧
        val entries = factory.formatKrcEntries(
            """
                [0,3000]<0,300,0>万里长风<300,300,0> - <600,300,0>周深
                [5382,1000]<0,300,0>词：<300,300,0>毕磊
                [8073,1000]<0,300,0>曲：<300,300,0>钱雷
                [10764,1000]<0,300,0>编曲：<300,300,0>钱雷
                [29625,1000]<0,300,0>我们会到哪里
                [32859,1000]<0,300,0>曾经有人问起
            """.trimIndent()
        )

        assertTrue(MediaViewModelObject.otherSideForLines.all { !it })
        // `编曲：` 属既有 isNonLyricMetadata 过滤范围，与 KRC 路径一致地被移除
        assertEquals(
            listOf("万里长风 - 周深", "词：毕磊", "曲：钱雷", "我们会到哪里", "曾经有人问起"),
            entries.map { it.mainLyric.joinToString("") { pair -> pair.second }.trim() }
        )
    }

    @Test
    fun englishCreditLinesNeverSwitchSide() {
        // First Love 的真实署名行：Guitar：/Strings： 都是英文形态
        factory.formatKrcEntries(
            """
                [1000,1000]<0,300,0>Guitar：<300,300,0>秋山浩徳
                [2000,1000]<0,300,0>Strings：<300,300,0>後藤勇一郎
                [3000,1000]<0,300,0>最後のキスは
            """.trimIndent()
        )

        assertEquals(listOf(false, false, false), MediaViewModelObject.otherSideForLines.toList())
    }

    @Test
    fun singleDistinctSingerLabelNeverEnablesSplitLayout() {
        // 只有一个歌手标签时不分栏：全程左对齐
        factory.formatKrcEntries(
            """
                [1000,1000]<0,300,0>男：<300,300,0>一
                [2000,1000]<0,300,0>男：<300,300,0>二
                [3000,1000]<0,300,0>男：<300,300,0>三
            """.trimIndent()
        )

        assertEquals(listOf(false, false, false), MediaViewModelObject.otherSideForLines.toList())
    }

    @Test
    fun onceOnlySingerLabelBetweenRepeatedLabelsStillSplits() {
        // 某位歌手只唱一句，另一位重复出现：仍应判为对唱
        factory.formatKrcEntries(
            """
                [1000,1000]<0,300,0>男：<300,300,0>一
                [2000,1000]<0,300,0>女：<300,300,0>二
                [3000,1000]<0,300,0>男：<300,300,0>三
            """.trimIndent()
        )

        assertEquals(listOf(false, true, false), MediaViewModelObject.otherSideForLines.toList())
    }

    @Test
    fun multiWordSingerLabelIsFullyStripped() {
        // KRC 把 `男：` 逐字切成 ["男", "："] 两个词，剥离不能残留冒号
        val entries = factory.formatKrcEntries(
            """
                [1000,1000]<0,300,0>男<300,300,0>：<600,300,0>君を
                [2000,1000]<0,300,0>女<300,300,0>：<600,300,0>僕を
                [3000,1000]<0,300,0>男<300,300,0>：<600,300,0>また
            """.trimIndent()
        )

        assertEquals(listOf("君を", "僕を", "また"),
            entries.map { it.mainLyric.joinToString("") { pair -> pair.second }.trim() })
        assertEquals(listOf(false, true, false), MediaViewModelObject.otherSideForLines.toList())
    }

    @Test
    fun standaloneSingerLabelLineIsRemoved() {
        // 蔷薇青竹的真实形态：`男：` 独占一行，既不应显示也不应占位
        val entries = factory.formatKrcEntries(
            """
                [1000,1000]<0,300,0>男<300,300,0>：
                [2000,1000]<0,300,0>君を
                [3000,1000]<0,300,0>女<300,300,0>：
                [4000,1000]<0,300,0>僕を
                [5000,1000]<0,300,0>男<300,300,0>：
                [6000,1000]<0,300,0>また
            """.trimIndent()
        )

        assertEquals(listOf("君を", "僕を", "また"),
            entries.map { it.mainLyric.joinToString("") { pair -> pair.second }.trim() })
        // sides 与被删行同步对齐：君を左、僕を右、また左
        assertEquals(listOf(false, true, false), MediaViewModelObject.otherSideForLines.toList())
    }

    @Test
    fun lrcPathFiltersCreditLinesLikeKrcPath() {
        val entries = factory.formatLrcEntries(
            """
                [00:01.00]歌名 - 歌手
                [00:02.00]作词: 某人
                [00:03.00]作曲: 某人
                [00:04.00]真正的歌词
            """.trimIndent()
        )

        assertEquals(listOf("歌名 - 歌手", "真正的歌词"),
            entries.map { it.mainLyric.joinToString("") { pair -> pair.second }.trim() })
    }
}
