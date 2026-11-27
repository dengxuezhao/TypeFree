package com.typefree.ime.ime.expression

import com.typefree.ime.data.ExpressionTab
import com.typefree.ime.i18n.AppLang
import com.typefree.ime.i18n.AppStrings

enum class EmojiCategory(val icon: String) {
    RECENT("🕒"),
    SMILEYS("😀"),
    GESTURES("👋"),
    HEARTS_SYMBOLS("❤️"),
    ANIMALS_NATURE("🐱"),
    FOOD_DRINK("☕"),
    ACTIVITIES_OBJECTS("🎉")
}

enum class KaomojiCategory {
    HAPPY,
    CUTE,
    SPEECHLESS,
    SAD_HUG,
    ANGRY_SHOCK,
    COOL_CHEER
}

enum class SymbolCategory {
    COMMON_CN,
    COMMON_EN,
    MATH,
    NUMBERED,
    ARROWS_SHAPES,
    CURRENCY_SPECIAL
}

data class EmojiItem(
    val glyph: String,
    val keywords: String,
    val skinVariants: List<String> = emptyList()
)

data class SymbolItem(
    val display: String,
    val commitText: String = display,
    val pairedOpen: String? = null,
    val pairedClose: String? = null
) {
    val isPaired: Boolean get() = pairedOpen != null && pairedClose != null
}

data class EmojiQuickFilterChip(
    val query: String,
    val labelZh: String,
    val labelEn: String,
    val labelTw: String
) {
    fun label(lang: AppLang): String = AppStrings.pick(lang, labelZh, labelEn, labelTw)
}

/**
 * Pure-Kotlin curated Emoji / Kaomoji / Extended Symbol catalog with runtime glyph filtering,
 * keyword search, skin-tone memory resolution, and recent FIFO history helpers.
 */
object ExpressionCatalog {

    const val MAX_RECENT_EMOJIS = 24

    val DEFAULT_RECENT_EMOJIS: List<String> = listOf(
        "😂", "👍", "🙏", "❤️", "😭", "🔥", "🎉", "👀",
        "😊", "😅", "🤔", "👏", "💪", "✨", "🫡", "🤝"
    )

    val QUICK_SEARCH_CHIPS: List<EmojiQuickFilterChip> = listOf(
        EmojiQuickFilterChip("笑", "笑", "Laugh", "笑"),
        EmojiQuickFilterChip("赞", "赞", "Thumb", "讚"),
        EmojiQuickFilterChip("爱", "爱", "Love", "愛"),
        EmojiQuickFilterChip("谢谢", "谢谢", "Thanks", "謝謝"),
        EmojiQuickFilterChip("哭", "哭", "Cry", "哭"),
        EmojiQuickFilterChip("无语", "无语", "Meh", "無語"),
        EmojiQuickFilterChip("加油", "加油", "Cheer", "加油"),
        EmojiQuickFilterChip("收到", "收到", "Roger", "收到"),
        EmojiQuickFilterChip("庆祝", "庆祝", "Party", "慶祝"),
        EmojiQuickFilterChip("火", "火", "Fire", "火"),
        EmojiQuickFilterChip("猫", "猫狗", "Pets", "貓狗"),
        EmojiQuickFilterChip("喝", "茶酒", "Drink", "茶酒"),
        EmojiQuickFilterChip("气", "生气", "Angry", "生氣"),
        EmojiQuickFilterChip("工作", "工作", "Work", "工作")
    )

    private val SKIN_TONE_MODIFIERS = listOf("\uD83C\uDFFB", "\uD83C\uDFFC", "\uD83C\uDFFD", "\uD83C\uDFFE", "\uD83C\uDFFF")

    private fun withSkinTones(base: String, keywords: String): EmojiItem {
        val cleanBase = base.replace("\uFE0F", "")
        val variants = buildList(6) {
            add(base)
            for (mod in SKIN_TONE_MODIFIERS) {
                add(cleanBase + mod)
            }
        }
        return EmojiItem(glyph = base, keywords = keywords, skinVariants = variants)
    }

    val EMOJIS_BY_CATEGORY: Map<EmojiCategory, List<EmojiItem>> = linkedMapOf(
        EmojiCategory.SMILEYS to listOf(
            EmojiItem("😂", "笑 哈哈 笑哭 laugh lol joy tears"),
            EmojiItem("🤣", "大笑 笑死 哈哈 rofl laugh"),
            EmojiItem("😄", "笑 开心 哈哈 grin happy smile"),
            EmojiItem("😊", "微笑 开心 礼貌 笑 smile blush"),
            EmojiItem("🥹", "感动 委屈 眼泪 哭 holding tears"),
            EmojiItem("😍", "爱 喜欢 花痴 heart eyes love"),
            EmojiItem("🥰", "爱 幸福 甜蜜 喜欢 love hearts"),
            EmojiItem("😘", "亲亲 么么哒 爱 kiss love"),
            EmojiItem("😋", "好吃 美味 馋 yummy"),
            EmojiItem("😎", "酷 墨镜 帅 cool sunglasses"),
            EmojiItem("🤩", "星星眼 崇拜 厉害 star struck"),
            EmojiItem("🥳", "庆祝 派对 生日 party celebrate"),
            EmojiItem("🤔", "思考 疑惑 想想 无语 think hmm"),
            EmojiItem("😅", "尴尬 苦笑 流汗 笑 sweat smile"),
            EmojiItem("😬", "尴尬 咧嘴 grimace awkward"),
            EmojiItem("🙄", "无语 白眼 eye roll"),
            EmojiItem("😑", "无语 面无表情 expressionless"),
            EmojiItem("😐", "无语 冷静 neutral"),
            EmojiItem("😶", "无语 沉默 闭嘴 silent"),
            EmojiItem("🫠", "融化 摆烂 社死 无语 melt"),
            EmojiItem("😭", "哭 大哭 伤心 cry sob"),
            EmojiItem("😢", "哭 流泪 难过 sad tear"),
            EmojiItem("🥺", "委屈 可怜 拜托 pleading"),
            EmojiItem("😤", "生气 气 哼 认真 huff angry"),
            EmojiItem("😡", "生气 气 愤怒 angry mad"),
            EmojiItem("🤬", "生气 气 骂人 furious"),
            EmojiItem("🤯", "震惊 炸裂 离谱 mind blown"),
            EmojiItem("😱", "吓死 惊恐 震惊 scream"),
            EmojiItem("😳", "脸红 震惊 害羞 flushed"),
            EmojiItem("🫣", "偷看 害羞 peek shy"),
            EmojiItem("🤫", "嘘 保密 shush quiet"),
            EmojiItem("🫡", "收到 敬礼 遵命 salute roger"),
            EmojiItem("🤗", "抱抱 拥抱 hug warm"),
            EmojiItem("😴", "困 睡觉 晚安 sleep tired"),
            EmojiItem("🥱", "困 打哈欠 yawn"),
            EmojiItem("🤒", "生病 发烧 sick fever"),
            EmojiItem("🤧", "感冒 喷嚏 sneeze"),
            EmojiItem("😇", "天使 无辜 angel"),
            EmojiItem("🤠", "牛仔 开心 cowboy"),
            EmojiItem("🤡", "小丑 clown"),
            EmojiItem("👻", "幽灵 鬼 ghost"),
            EmojiItem("💀", "笑死 骷髅 无语 skull dead")
        ),
        EmojiCategory.GESTURES to listOf(
            withSkinTones("👍", "赞 好 棒 顶 收到 thumbs up good"),
            withSkinTones("👎", "踩 不好 thumbs down"),
            withSkinTones("👏", "鼓掌 厉害 牛 赞 clap bravo"),
            withSkinTones("🙏", "谢谢 感谢 拜托 thanks pray please"),
            withSkinTones("👋", "你好 再见 拜拜 wave hello bye"),
            withSkinTones("👌", "好的 收到 ok"),
            withSkinTones("💪", "加油 强壮 努力 muscle strong cheer"),
            withSkinTones("✌️", "耶 胜利 剪刀手 peace victory"),
            withSkinTones("🫶", "比心 爱 感谢 heart hands love"),
            withSkinTones("👊", "碰拳 加油 冲 fist bump"),
            withSkinTones("🙌", "万岁 庆祝 太棒了 praise hands"),
            withSkinTones("🤞", "祈祷 好运 crossed fingers"),
            withSkinTones("🤙", "666 打电话 call me"),
            withSkinTones("✋", "停 击掌 举手 raised hand"),
            withSkinTones("🤏", "一点点 捏 pinching hand"),
            withSkinTones("🤌", "精髓 捏手 pinched fingers"),
            withSkinTones("👉", "右边 指向 point right"),
            withSkinTones("👈", "左边 指向 point left"),
            withSkinTones("👆", "上面 同意 point up"),
            withSkinTones("👇", "下面 往下 point down"),
            EmojiItem("🤝", "握手 合作 成交 收到 handshake"),
            EmojiItem("👀", "看 围观 吃瓜 eyes look"),
            EmojiItem("🧠", "大脑 思考 聪明 brain"),
            EmojiItem("🫂", "抱抱 安慰 拥抱 hug")
        ),
        EmojiCategory.HEARTS_SYMBOLS to listOf(
            EmojiItem("❤️", "爱 红心 喜欢 爱心 heart love"),
            EmojiItem("🧡", "橙心 爱 暖 orange heart"),
            EmojiItem("💛", "黄心 爱 yellow heart"),
            EmojiItem("💚", "绿心 翡翠 爱 green heart"),
            EmojiItem("💙", "蓝心 爱 blue heart"),
            EmojiItem("💜", "紫心 爱 purple heart"),
            EmojiItem("🖤", "黑心 酷 black heart"),
            EmojiItem("🤍", "白心 纯净 white heart"),
            EmojiItem("💔", "心碎 伤心 哭 broken heart"),
            EmojiItem("💕", "双心 爱 喜欢 two hearts"),
            EmojiItem("💖", "闪心 爱 sparkling heart"),
            EmojiItem("🔥", "火 热 爆 厉害 fire hot lit"),
            EmojiItem("✨", "闪 星星 亮点 精致 sparkles"),
            EmojiItem("⭐", "星星 收藏 好评 star"),
            EmojiItem("🌟", "亮星 发光 glowing star"),
            EmojiItem("💯", "满分 100 赞 100"),
            EmojiItem("⚡", "闪电 快 效率 zap fast"),
            EmojiItem("💥", "爆炸 震撼 boom"),
            EmojiItem("💫", "晕 星星 dizzy"),
            EmojiItem("💦", "流汗 水滴 sweat"),
            EmojiItem("💢", "生气 气 怒 anger"),
            EmojiItem("💤", "睡觉 困 zzz"),
            EmojiItem("✅", "完成 对勾 收到 确认 check done"),
            EmojiItem("❌", "错误 取消 叉 cross no"),
            EmojiItem("⭕", "圆圈 对 circle"),
            EmojiItem("❗", "感叹 重要 提醒 exclamation"),
            EmojiItem("❓", "疑问 问号 question"),
            EmojiItem("⚠️", "警告 注意 warning"),
            EmojiItem("📌", "置顶 标记 工作 pin"),
            EmojiItem("💡", "灵感 主意 提示 工作 bulb idea"),
            EmojiItem("💬", "聊天 对话 说话 speech"),
            EmojiItem("🎵", "音乐 歌 music")
        ),
        EmojiCategory.ANIMALS_NATURE to listOf(
            EmojiItem("🐱", "猫 猫咪 宠物 cat"),
            EmojiItem("🐶", "狗 狗狗 宠物 dog"),
            EmojiItem("🐼", "熊猫 可爱 panda"),
            EmojiItem("🦊", "狐狸 fox"),
            EmojiItem("🐰", "兔子 可爱 rabbit"),
            EmojiItem("🐻", "熊 小熊 bear"),
            EmojiItem("🐨", "考拉 koala"),
            EmojiItem("🐯", "老虎 虎 tiger"),
            EmojiItem("🦁", "狮子 lion"),
            EmojiItem("🐮", "牛 牛逼 cow"),
            EmojiItem("🐷", "猪 猪头 pig"),
            EmojiItem("🐸", "青蛙 蛙 frog"),
            EmojiItem("🐵", "猴子 猴 monkey"),
            EmojiItem("🐔", "鸡 chicken"),
            EmojiItem("🐧", "企鹅 penguin"),
            EmojiItem("🦆", "鸭子 冲鸭 duck"),
            EmojiItem("🦉", "猫头鹰 熬夜 owl"),
            EmojiItem("🦋", "蝴蝶 butterfly"),
            EmojiItem("🐝", "蜜蜂 忙 工作 bee"),
            EmojiItem("🐢", "乌龟 慢 turtle"),
            EmojiItem("🐟", "鱼 摸鱼 fish"),
            EmojiItem("🦈", "鲨鱼 shark"),
            EmojiItem("🦄", "独角兽 unicorn"),
            EmojiItem("🌸", "樱花 花 春天 blossom flower"),
            EmojiItem("🌹", "玫瑰 爱 花 rose"),
            EmojiItem("🌻", "向日葵 阳光 sunflower"),
            EmojiItem("🍀", "四叶草 好运 clover"),
            EmojiItem("☀️", "太阳 晴天 sun"),
            EmojiItem("🌙", "月亮 晚安 moon"),
            EmojiItem("🌈", "彩虹 rainbow"),
            EmojiItem("🌧️", "下雨 雨 rain"),
            EmojiItem("❄️", "雪 下雪 冷 snow")
        ),
        EmojiCategory.FOOD_DRINK to listOf(
            EmojiItem("☕", "咖啡 喝 提神 工作 coffee"),
            EmojiItem("🧋", "奶茶 喝 快乐水 boba tea"),
            EmojiItem("🍵", "茶 绿茶 喝 tea"),
            EmojiItem("🍺", "啤酒 喝酒 干杯 庆祝 beer"),
            EmojiItem("🥂", "干杯 香槟 庆祝 cheers"),
            EmojiItem("🍷", "红酒 喝 wine"),
            EmojiItem("🥤", "饮料 喝 可乐 soda"),
            EmojiItem("🍉", "西瓜 吃瓜 watermelon"),
            EmojiItem("🍓", "草莓 水果 strawberry"),
            EmojiItem("🍑", "桃子 peach"),
            EmojiItem("🍒", "樱桃 cherry"),
            EmojiItem("🍋", "柠檬 酸 lemon"),
            EmojiItem("🍎", "苹果 apple"),
            EmojiItem("🍌", "香蕉 banana"),
            EmojiItem("🍰", "蛋糕 生日 甜品 cake"),
            EmojiItem("🎂", "生日 蛋糕 庆祝 birthday"),
            EmojiItem("🍦", "冰淇淋 甜品 ice cream"),
            EmojiItem("🍫", "巧克力 chocolate"),
            EmojiItem("🍜", "拉面 面条 吃饭 ramen"),
            EmojiItem("🍲", "火锅 聚餐 hotpot"),
            EmojiItem("🥟", "饺子 dumpling"),
            EmojiItem("🍣", "寿司 sushi"),
            EmojiItem("🍕", "披萨 pizza"),
            EmojiItem("🍔", "汉堡 burger"),
            EmojiItem("🍟", "薯条 fries"),
            EmojiItem("🍗", "鸡腿 加鸡腿 chicken leg"),
            EmojiItem("🍚", "米饭 干饭 rice"),
            EmojiItem("🥢", "筷子 干饭 chopsticks")
        ),
        EmojiCategory.ACTIVITIES_OBJECTS to listOf(
            EmojiItem("🎉", "庆祝 撒花 恭喜 派对 tada party"),
            EmojiItem("🎊", "庆祝 彩带 confetti"),
            EmojiItem("🎈", "气球 庆祝 balloon"),
            EmojiItem("🎁", "礼物 惊喜 gift"),
            EmojiItem("🏆", "奖杯 冠军 厉害 trophy"),
            EmojiItem("🥇", "金牌 第一 gold medal"),
            EmojiItem("🎯", "目标 精准 工作 target"),
            EmojiItem("🚀", "火箭 起飞 发布 冲 rocket launch"),
            EmojiItem("✈️", "飞机 旅行 出差 airplane"),
            EmojiItem("🚗", "汽车 开车 car"),
            EmojiItem("🏠", "家 回家 home"),
            EmojiItem("💻", "电脑 写代码 工作 laptop code"),
            EmojiItem("📱", "手机 phone"),
            EmojiItem("⌨️", "键盘 打字 工作 keyboard"),
            EmojiItem("🎧", "耳机 听歌 headphones"),
            EmojiItem("📷", "相机 拍照 camera"),
            EmojiItem("📝", "笔记 记录 工作 memo"),
            EmojiItem("📅", "日历 日程 工作 calendar"),
            EmojiItem("⏰", "闹钟 提醒 时间 alarm"),
            EmojiItem("🔒", "锁 隐私 安全 lock"),
            EmojiItem("🔑", "钥匙 密钥 key"),
            EmojiItem("💰", "钱 发财 红包 money"),
            EmojiItem("🧧", "红包 恭喜 发财 red envelope"),
            EmojiItem("📦", "快递 包裹 package"),
            EmojiItem("🛒", "购物车 买买买 cart"),
            EmojiItem("🎮", "游戏 开黑 game"),
            EmojiItem("⚽", "足球 运动 soccer"),
            EmojiItem("🏀", "篮球 运动 basketball")
        )
    )

    val ALL_SEARCHABLE_EMOJIS: List<EmojiItem> = EMOJIS_BY_CATEGORY.values.flatten()

    val KAOMOJI_BY_CATEGORY: Map<KaomojiCategory, List<String>> = linkedMapOf(
        KaomojiCategory.HAPPY to listOf(
            "(≧▽≦)", "(◕‿◕✿)", "ヽ(✿ﾟ▽ﾟ)ノ", "(*^▽^*)",
            "٩(ˊᗜˋ*)و", "(｡･ω･｡)ﾉ♡", "ヾ(≧▽≦*)o", "o(*￣▽￣*)ブ",
            "(ﾉ◕ヮ◕)ﾉ*:･ﾟ✧", "(☆▽☆)", "ヽ(´▽`)/", "(*≧ω≦)"
        ),
        KaomojiCategory.CUTE to listOf(
            "QwQ", "(*/ω＼*)", "(´,,•ω•,,)♡", "(=・ω・=)",
            "(｡•̀ᴗ-)✧", "( ˘ ³˘)♥", "(๑´ㅂ`๑)", "(●'◡'●)",
            "(づ￣ ³￣)づ", "(/▽＼)", "(◍•ᴗ•◍)", "(*ฅ́˘ฅ̀*)♡"
        ),
        KaomojiCategory.SPEECHLESS to listOf(
            "¯\\_(ツ)_/¯", "(￣_￣|||)", "(눈_눈)", "(-_-メ)",
            "(；一_一)", "╮(╯▽╰)╭", "(¬_¬\")", "┐(´д`)┌",
            "(ー_ー)!!", "(￣▽￣\")", "(⊙_⊙;)", "(-_-;)"
        ),
        KaomojiCategory.SAD_HUG to listOf(
            "(｡•́︿•̀｡)", "(つ´ω`)つ", "(っ˘̩╭╮˘̩)っ", "(T_T)",
            "(╥﹏╥)", "(´；ω；`)", "༼ つ ◕_◕ ༽つ", "(ノへ￣、)",
            "(｡ŏ﹏ŏ)", "o(╥﹏╥)o", "( ´･･)ﾉ(._.`)", "ಥ_ಥ"
        ),
        KaomojiCategory.ANGRY_SHOCK to listOf(
            "(╯°□°）╯︵ ┻━┻", "(╬▔皿▔)╯", "Σ(っ °Д °;)っ", "(#`O′)",
            "ヽ(`Д´)ﾉ", "(ﾉꐦ ◎曲◎)ﾉ", "w(ﾟДﾟ)w", "∑(O_O;)",
            "(°ロ°)!", "(；☉_☉)", "┻━┻ ︵ヽ(`Д´)ﾉ︵ ┻━┻", "(ಠ_ಠ)"
        ),
        KaomojiCategory.COOL_CHEER to listOf(
            "(๑•̀ㅂ•́)و✧", "(ง •̀_•́)ง", "( •_•)>⌐■-■", "(⌐■_■)",
            "✧(≖ ◡ ≖✿)", "(￣y▽,￣)╭", "φ(゜▽゜*)♪", "(*•̀ᴗ•́*)و ̑̑",
            "☆⌒(*＾-゜)v", "( •̀ ω •́ )y", "d(`･∀･)b", "٩(•̤̀ᵕ•̤́๑)ᵒᵏ"
        )
    )

    val SYMBOLS_BY_CATEGORY: Map<SymbolCategory, List<SymbolItem>> = linkedMapOf(
        SymbolCategory.COMMON_CN to listOf(
            SymbolItem("，"), SymbolItem("。"), SymbolItem("？"), SymbolItem("！"), SymbolItem("、"), SymbolItem("："),
            SymbolItem("；"), SymbolItem("……"), SymbolItem("——"),
            SymbolItem("“”", pairedOpen = "“", pairedClose = "”"),
            SymbolItem("（）", pairedOpen = "（", pairedClose = "）"),
            SymbolItem("《》", pairedOpen = "《", pairedClose = "》"),
            SymbolItem("【】", pairedOpen = "【", pairedClose = "】"),
            SymbolItem("「」", pairedOpen = "「", pairedClose = "」"),
            SymbolItem("『』", pairedOpen = "『", pairedClose = "』"),
            SymbolItem("‘’", pairedOpen = "‘", pairedClose = "’"),
            SymbolItem("·"), SymbolItem("～"), SymbolItem("＠", "@"), SymbolItem("＃", "#"), SymbolItem("¥"), SymbolItem("%"),
            SymbolItem("—"), SymbolItem("　", "\u3000")
        ),
        SymbolCategory.COMMON_EN to listOf(
            SymbolItem(","), SymbolItem("."), SymbolItem("?"), SymbolItem("!"), SymbolItem(":"), SymbolItem(";"),
            SymbolItem("'"), SymbolItem("\""),
            SymbolItem("()", pairedOpen = "(", pairedClose = ")"),
            SymbolItem("[]", pairedOpen = "[", pairedClose = "]"),
            SymbolItem("{}", pairedOpen = "{", pairedClose = "}"),
            SymbolItem("<>", pairedOpen = "<", pairedClose = ">"),
            SymbolItem("/"), SymbolItem("\\"), SymbolItem("|"), SymbolItem("_"), SymbolItem("-"), SymbolItem("+"),
            SymbolItem("="), SymbolItem("*"), SymbolItem("&"), SymbolItem("^"), SymbolItem("%"), SymbolItem("$"),
            SymbolItem("#"), SymbolItem("@"), SymbolItem("~"), SymbolItem("`")
        ),
        SymbolCategory.MATH to listOf(
            SymbolItem("+"), SymbolItem("-"), SymbolItem("×"), SymbolItem("÷"), SymbolItem("="), SymbolItem("≠"),
            SymbolItem("≈"), SymbolItem("≤"), SymbolItem("≥"), SymbolItem("±"), SymbolItem("∞"), SymbolItem("√"),
            SymbolItem("∑"), SymbolItem("∏"), SymbolItem("∫"), SymbolItem("π"), SymbolItem("°"), SymbolItem("℃"),
            SymbolItem("℉"), SymbolItem("‰"), SymbolItem("²"), SymbolItem("³"), SymbolItem("½"), SymbolItem("¼"),
            SymbolItem("¾"), SymbolItem("∠"), SymbolItem("⊥"), SymbolItem("∥"), SymbolItem("∈"), SymbolItem("∉")
        ),
        SymbolCategory.NUMBERED to listOf(
            SymbolItem("①"), SymbolItem("②"), SymbolItem("③"), SymbolItem("④"), SymbolItem("⑤"), SymbolItem("⑥"),
            SymbolItem("⑦"), SymbolItem("⑧"), SymbolItem("⑨"), SymbolItem("⑩"), SymbolItem("⑴"), SymbolItem("⑵"),
            SymbolItem("⑶"), SymbolItem("⑷"), SymbolItem("⑸"), SymbolItem("Ⅰ"), SymbolItem("Ⅱ"), SymbolItem("Ⅲ"),
            SymbolItem("Ⅳ"), SymbolItem("Ⅴ"), SymbolItem("一、"), SymbolItem("二、"), SymbolItem("三、"), SymbolItem("•"),
            SymbolItem("▪"), SymbolItem("◦"), SymbolItem("※"), SymbolItem("§"), SymbolItem("№"), SymbolItem("・")
        ),
        SymbolCategory.ARROWS_SHAPES to listOf(
            SymbolItem("→"), SymbolItem("←"), SymbolItem("↑"), SymbolItem("↓"), SymbolItem("↔"), SymbolItem("↕"),
            SymbolItem("↗"), SymbolItem("↘"), SymbolItem("↙"), SymbolItem("↖"), SymbolItem("⇒"), SymbolItem("⇐"),
            SymbolItem("⇔"), SymbolItem("➜"), SymbolItem("➤"), SymbolItem("▶"), SymbolItem("◀"), SymbolItem("▲"),
            SymbolItem("▼"), SymbolItem("★"), SymbolItem("☆"), SymbolItem("◆"), SymbolItem("◇"), SymbolItem("✓"),
            SymbolItem("✗"), SymbolItem("♥"), SymbolItem("♦"), SymbolItem("♠"), SymbolItem("♣"), SymbolItem("●")
        ),
        SymbolCategory.CURRENCY_SPECIAL to listOf(
            SymbolItem("¥"), SymbolItem("$"), SymbolItem("€"), SymbolItem("£"), SymbolItem("¢"), SymbolItem("₽"),
            SymbolItem("₩"), SymbolItem("₹"), SymbolItem("₿"), SymbolItem("©"), SymbolItem("®"), SymbolItem("™"),
            SymbolItem("℗"), SymbolItem("℠"), SymbolItem("♀"), SymbolItem("♂"), SymbolItem("♫"), SymbolItem("♪"),
            SymbolItem("☀"), SymbolItem("☁"), SymbolItem("☂"), SymbolItem("❄"), SymbolItem("☎"), SymbolItem("✉")
        )
    )

    fun tabLabel(tab: ExpressionTab, lang: AppLang): String = when (tab) {
        ExpressionTab.EMOJI -> AppStrings.pick(lang, "Emoji", "Emoji", "Emoji")
        ExpressionTab.KAOMOJI -> AppStrings.pick(lang, "颜文字", "Kaomoji", "顏文字")
        ExpressionTab.SYMBOL -> AppStrings.pick(lang, "符号", "Symbols", "符號")
    }

    fun emojiCategoryLabel(category: EmojiCategory, lang: AppLang): String = when (category) {
        EmojiCategory.RECENT -> AppStrings.pick(lang, "最近", "Recent", "最近")
        EmojiCategory.SMILEYS -> AppStrings.pick(lang, "表情", "Smileys", "表情")
        EmojiCategory.GESTURES -> AppStrings.pick(lang, "手势", "Hands", "手勢")
        EmojiCategory.HEARTS_SYMBOLS -> AppStrings.pick(lang, "心形", "Hearts", "心形")
        EmojiCategory.ANIMALS_NATURE -> AppStrings.pick(lang, "动物", "Nature", "動物")
        EmojiCategory.FOOD_DRINK -> AppStrings.pick(lang, "饮食", "Food", "飲食")
        EmojiCategory.ACTIVITIES_OBJECTS -> AppStrings.pick(lang, "物品", "Objects", "物品")
    }

    fun kaomojiCategoryLabel(category: KaomojiCategory, lang: AppLang): String = when (category) {
        KaomojiCategory.HAPPY -> AppStrings.pick(lang, "开心", "Happy", "開心")
        KaomojiCategory.CUTE -> AppStrings.pick(lang, "撒娇", "Cute", "撒嬌")
        KaomojiCategory.SPEECHLESS -> AppStrings.pick(lang, "无语", "Meh", "無語")
        KaomojiCategory.SAD_HUG -> AppStrings.pick(lang, "抱抱", "Hug", "抱抱")
        KaomojiCategory.ANGRY_SHOCK -> AppStrings.pick(lang, "抓狂", "Shock", "抓狂")
        KaomojiCategory.COOL_CHEER -> AppStrings.pick(lang, "装酷", "Cool", "裝酷")
    }

    fun symbolCategoryLabel(category: SymbolCategory, lang: AppLang): String = when (category) {
        SymbolCategory.COMMON_CN -> AppStrings.pick(lang, "常用", "CN", "常用")
        SymbolCategory.COMMON_EN -> AppStrings.pick(lang, "英文", "EN", "英文")
        SymbolCategory.MATH -> AppStrings.pick(lang, "数学", "Math", "數學")
        SymbolCategory.NUMBERED -> AppStrings.pick(lang, "序号", "Num", "序號")
        SymbolCategory.ARROWS_SHAPES -> AppStrings.pick(lang, "箭头", "Arrow", "箭頭")
        SymbolCategory.CURRENCY_SPECIAL -> AppStrings.pick(lang, "特殊", "Misc", "特殊")
    }

    fun resolveRecentEmojis(savedRecent: List<String>): List<EmojiItem> {
        val source = if (savedRecent.isNotEmpty()) savedRecent else DEFAULT_RECENT_EMOJIS
        val byBase = ALL_SEARCHABLE_EMOJIS.associateBy { it.glyph }
        return source.map { glyph ->
            byBase[glyph] ?: EmojiItem(glyph = glyph, keywords = "")
        }
    }

    fun recordRecentEmoji(
        existing: List<String>,
        picked: String,
        maxCount: Int = MAX_RECENT_EMOJIS
    ): List<String> {
        val clean = picked.trim()
        if (clean.isEmpty()) return existing
        val out = ArrayList<String>(maxCount)
        out.add(clean)
        for (item in existing) {
            if (item != clean && item.isNotBlank()) {
                out.add(item)
                if (out.size >= maxCount) break
            }
        }
        return out
    }

    fun resolveDisplayGlyph(item: EmojiItem, skinTonePrefs: Map<String, String>): String {
        if (item.skinVariants.isEmpty()) return item.glyph
        return skinTonePrefs[item.glyph]?.takeIf { it in item.skinVariants } ?: item.glyph
    }

    fun searchEmojis(query: String): List<EmojiItem> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return ALL_SEARCHABLE_EMOJIS.filter { item ->
            item.glyph.contains(q, ignoreCase = true) ||
                item.keywords.lowercase().contains(q)
        }
    }

    fun filterSupportedEmojis(
        items: List<EmojiItem>,
        hasGlyph: (String) -> Boolean
    ): List<EmojiItem> {
        return items.filter { item ->
            runCatching { hasGlyph(item.glyph) }.getOrDefault(true)
        }
    }
}
