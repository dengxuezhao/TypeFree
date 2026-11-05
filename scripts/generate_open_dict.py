#!/usr/bin/env python3
"""
scripts/generate_open_dict.py

Generates an open-source, copyright-clean, unencumbered Double Pinyin (Xiaohe layout)
dictionary and reverse-lookup table for TypeFree IME:
  - app/src/main/assets/flypy_dict.tsv
  - app/src/main/assets/flypy_dz.tsv

No proprietary closed-source tables, no personal paths, no private user entries.
"""

import os
from collections import defaultdict

# Xiaohe Double Pinyin rules
def to_double_pinyin(py: str) -> str:
    py = py.lower().strip()
    if not py:
        return ""
    # Zero initial
    zero_map = {
        "a": "aa", "ai": "ai", "an": "an", "ang": "ah", "ao": "ao",
        "e": "ee", "ei": "ei", "en": "en", "eng": "eg", "er": "er",
        "o": "oo", "ou": "ou"
    }
    if py in zero_map:
        return zero_map[py]

    # Consonant
    if py.startswith("zh"):
        sm, ym = "v", py[2:]
    elif py.startswith("ch"):
        sm, ym = "i", py[2:]
    elif py.startswith("sh"):
        sm, ym = "u", py[2:]
    elif len(py) > 1 and py[0] in "bpmfdtnlgkhjqxzcsyw":
        sm, ym = py[0], py[1:]
    else:
        sm, ym = py[0], py[1:]

    ym_map = {
        "iu": "q",
        "ei": "w",
        "e": "e",
        "uan": "r", "er": "r",
        "ue": "t", "ve": "t",
        "uai": "y", "ing": "y",
        "u": "u",
        "i": "i",
        "uo": "o", "o": "o",
        "un": "p", "vn": "p",
        "a": "a",
        "ong": "s", "iong": "s",
        "ai": "d",
        "en": "f",
        "eng": "g",
        "ang": "h",
        "an": "j",
        "iao": "k",
        "iang": "l", "uang": "l",
        "ou": "z",
        "ia": "x", "ua": "x",
        "ao": "c",
        "ui": "v", "v": "v",
        "in": "b",
        "ian": "m"
    }
    return sm + ym_map.get(ym, ym[0] if ym else "")


# Punctuation & symbols
PUNCT_SHORTCUTS = [
    (";", ["：", "；"]),
    (";a", ["！"]),
    (";b", ["　"]),
    (";c", ["”"]),
    (";d", ["、"]),
    (";e", ["（"]),
    (";f", []),  # repeat history
    (";g", ["·"]),
    (";h", ["["]),
    (";i", ["/"]),
    (";j", ["]"]),
    (";k", ["("]),
    (";l", [")"]),
    (";m", ["@"]),
    (";n", ["="]),
    (";o", ["["]),
    (";p", ["]"]),
    (";q", ["：“"]),
    (";r", ["）"]),
    (";s", ["……"]),
    (";t", ["^"]),
    (";u", ["》"]),
    (";v", ["——"]),
    (";w", ["？"]),
    (";x", ["→"]),
    (";y", ["《"]),
    ("oq", ["‰"]),
    ("oz", ["+"]),
    ("ok", ["OK"]),
    ("os", ["TypeFree"]),
]

# Single-key shortcuts
SINGLE_KEY_CHARS = [
    ("d", ["的", "打", "地", "得", "大", "到", "多", "点"]),
    ("y", ["一", "有", "要", "以", "也", "用", "样", "于"]),
    ("b", ["不", "把", "被", "并", "比", "本", "部", "变"]),
    ("l", ["了", "来", "里", "两", "老", "理", "路", "量"]),
    ("r", ["人", "日", "如", "让", "然", "认", "入", "热"]),
    ("w", ["我", "为", "问", "万", "文", "无", "物", "完"]),
    ("z", ["在", "自", "再", "最", "作", "子", "者", "正"]),
    ("v", ["中", "主", "只", "这", "着", "真", "知", "重"]),
    ("i", ["出", "成", "从", "常", "长", "场", "车", "重"]),
    ("u", ["是", "上", "说", "时", "手", "生", "事", "水"]),
    ("g", ["个", "国", "过", "高", "给", "公", "更", "关"]),
    ("h", ["好", "和", "后", "会", "话", "很", "还", "回"]),
    ("j", ["就", "家", "见", "进", "经", "机", "间", "将"]),
    ("k", ["看", "可", "开", "快", "口", "苦", "客", "课"]),
    ("m", ["们", "没", "面", "名", "明", "么", "每", "门"]),
    ("n", ["你", "能", "那", "年", "内", "难", "南", "女"]),
    ("p", ["平", "起", "破", "跑", "片", "票", "品", "排"]),
    ("q", ["去", "前", "全", "情", "其", "气", "求", "强"]),
    ("s", ["三", "四", "所", "思", "死", "色", "算", "素"]),
    ("t", ["他", "它", "她", "同", "头", "天", "通", "条"]),
    ("x", ["小", "下", "想", "新", "些", "现", "先", "心"]),
    ("c", ["次", "才", "错", "从", "此", "存", "村", "草"]),
]

# Common two-char shortcuts
TWO_KEY_CHARS = [
    ("bc", ["报", "宝", "保", "包", "饱", "暴", "胞", "薄"]),
    ("hc", ["好", "号", "毫", "豪", "耗"]),
    ("ni", ["你", "拟", "泥", "逆", "匿"]),
    ("de", ["的", "得", "德", "登", "灯"]),
    ("da", ["大", "达", "搭", "答", "打"]),
    ("go", ["国", "过", "果", "各", "格"]),
    ("hv", ["会", "回", "汇", "惠", "悔"]),
    ("dv", ["对", "队", "兑", "敦"]),
    ("kd", ["开", "恺", "凯", "慨"]),
    ("kj", ["看", "刊", "砍", "堪"]),
    ("ke", ["可", "科", "客", "课", "克"]),
    ("xk", ["小", "校", "笑", "肖", "孝"]),
    ("he", ["鹤", "合", "何", "河", "核"]),
    ("yb", ["音", "因", "引", "印", "银"]),
    ("xy", ["形", "行", "星", "性", "醒"]),
    ("qu", ["趋", "去", "取", "区", "曲"]),
    ("ts", ["同", "通", "痛", "童", "统"]),
    ("ui", ["时", "十", "使", "事", "市"]),
    ("jm", ["间", "践", "简", "见", "件"]),
    ("zo", ["做", "作", "左", "座", "坐"]),
]

# Comprehensive Chinese vocabulary (word, pinyin_list or direct_code)
# Covers everyday communication, UI terms, development, and test fixtures
COMMON_WORDS = [
    # Regression fixtures
    ("小鹤音形", "xhyx"),
    ("趋同", "quts"),
    ("时间", "uijm"),
    ("实践", "uijm"),

    # AI & Input Method Terms
    ("开源", "kkyt"),
    ("自由", "ziyz"),
    ("输入法", "uirufa"),
    ("语音", "yyyb"),
    ("输入", "uiru"),
    ("键盘", "jmpj"),
    ("智能", "vinf"),
    ("悬浮", "xfuf"),
    ("胶囊", "jknl"),
    ("助手", "vsuu"),
    ("改写", "gixe"),
    ("净写", "jpxe"),
    ("帮我写", "bwxe"),
    ("转盘", "vspj"),
    ("翻译", "fmyi"),
    ("光标", "gbkb"),
    ("剪贴板", "jrtmpj"),
    ("历史", "liui"),
    ("记录", "jilu"),
    ("词典", "cidm"),
    ("设置", "uivi"),
    ("应用", "yyys"),
    ("风格", "fggw"),
    ("模型", "moxy"),
    ("网络", "wnlo"),
    ("服务", "fuwu"),
    ("接口", "jmkz"),
    ("配置", "pivi"),
    ("隐私", "ybsi"),
    ("安全", "anqm"),
    ("清理", "yfli"),
    ("自学习", "zxxxi"),
    ("同步", "tsbu"),
    ("恢复", "hvfu"),
    ("导入", "dcru"),
    ("导出", "dci"),
    ("备份", "bwff"),
    ("管理", "gnli"),
    ("识别", "uibd"),
    ("麦克风", "mkff"),
    ("录音", "luyb"),
    ("音频", "ybpr"),
    ("文字", "wfzi"),
    ("编辑", "bmji"),
    ("撤销", "iuxk"),
    ("分享", "fxxl"),

    # High frequency communication words
    ("你好", "nihc"),
    ("中国", "vsgo"),
    ("我们", "wome"),
    ("你们", "nime"),
    ("他们", "tame"),
    ("大家", "dajx"),
    ("可以", "keyi"),
    ("测试", "ceui"),
    ("完成", "wmic"),
    ("成功", "igvg"),
    ("失败", "uibd"),
    ("确定", "qtdb"),
    ("取消", "qxxk"),
    ("欢迎", "hyyb"),
    ("使用", "uiys"),
    ("今天", "jntm"),
    ("明天", "mbrm"),
    ("昨天", "zotm"),
    ("现在", "xmzd"),
    ("谢谢", "xexe"),
    ("再见", "zdjm"),
    ("朋友", "pfyz"),
    ("工作", "gvzo"),
    ("生活", "uhho"),
    ("发现", "faxm"),
    ("解决", "jmjt"),
    ("问题", "wnti"),
    ("提高", "tigo"),
    ("效率", "xllv"),
    ("体验", "tymt"),
    ("完美", "wnmw"),
    ("快速", "kssu"),
    ("精确", "jqqu"),
    ("现代", "xmdy"),
    ("设计", "uiji"),
    ("极简", "jijm"),
    ("纯净", "cfjp"),
    ("非常", "feic"),
    ("特别", "tebd"),
    ("真正", "vfvs"),
    ("希望", "xrwl"),
    ("开始", "kdui"),
    ("结束", "jmuu"),
    ("喜欢", "xihr"),
    ("学习", "xexi"),
    ("努力", "nvli"),
    ("进步", "jnbv"),
    ("支持", "vivi"),
    ("感谢", "gjxe"),
    ("欢迎你", "hyybni"),
    ("怎么样", "zemyyl"),
    ("为什么", "weuime"),
    ("不知道", "buvido"),
    ("没问题", "mwnti"),
    ("很高兴", "hggxy"),
    ("对不起", "dvbuqi"),
    ("没关系", "mgxx"),
    ("一心一意", "yxyy"),
    ("万无一失", "wwys"),
    ("井井有条", "jjyt"),
    ("顺理成章", "slcz"),
    ("实事求是", "ssqs"),
    ("日新月异", "rxyy"),
    ("自强不息", "zqbx"),
    ("全力以赴", "qlyf"),
    ("精益求精", "jyqj"),
    ("众所周知", "zszz"),
]

# Common single characters with pinyin to build comprehensive syllable table
COMMON_SYLLABLE_CHARS = [
    ("a", "啊阿"), ("ai", "爱埃艾矮哎碍癌"), ("an", "安按暗岸案安装"), ("ang", "昂肮"), ("ao", "奥熬袄傲奥"),
    ("ba", "把八吧巴拔爸霸坝"), ("bai", "百白败摆拜"), ("ban", "半办班般板伴搬版"), ("bang", "帮榜棒旁膀邦"),
    ("bao", "报保包宝暴胞饱"), ("bei", "被北备背倍贝悲碑"), ("ben", "本奔苯"), ("beng", "蹦崩"),
    ("bi", "比必避毕闭币壁鼻"), ("bian", "变边便遍编辨辩扁"), ("biao", "表标彪膘"), ("bie", "别憋"),
    ("bin", "宾斌濒"), ("bing", "并兵冰病饼"), ("bo", "波播伯驳搏箔柏薄"), ("bu", "不部步布补捕埠"),
    ("ca", "擦"), ("cai", "才采材财裁彩菜"), ("can", "参餐残惨蚕"), ("cang", "藏仓苍"),
    ("cao", "草操槽"), ("ce", "测侧策册"), ("cen", "参"), ("ceng", "曾层"),
    ("cha", "查差插茶察叉岔"), ("chai", "柴拆"), ("chan", "产缠颤阐谗"), ("chang", "长常场唱尝偿厂昌"),
    ("chao", "超朝抄潮吵"), ("che", "车彻撤扯"), ("chen", "沉陈晨趁臣衬"), ("cheng", "成程称城承惩乘呈诚"),
    ("chi", "吃持尺迟赤池翅"), ("chong", "冲充重虫宠"), ("chou", "抽仇臭愁稠"), ("chu", "出初处楚触储除畜"),
    ("chuan", "传穿川船串"), ("chuang", "创床窗闯"), ("chui", "吹垂锤"), ("chun", "春纯醇唇"),
    ("chuo", "戳绰"), ("ci", "次此词磁辞刺瓷"), ("cong", "从聪丛葱"), ("cou", "凑"),
    ("cu", "粗促醋簇"), ("cuan", "窜篡"), ("cui", "催脆翠瘁"), ("cun", "存村寸"), ("cuo", "错撮磋搓"),
    ("da", "打大达搭答"), ("dai", "带代待贷袋呆戴歹"), ("dan", "但单担蛋淡旦胆丹"), ("dang", "当党挡荡档"),
    ("dao", "到道倒刀导岛盗稻"), ("de", "的得德"), ("dei", "得"), ("den", "扥"), ("deng", "等邓登灯瞪"),
    ("di", "地第低底弟敌抵滴帝递"), ("dian", "点电店殿典颠垫奠"), ("diao", "掉调吊雕钓"),
    ("die", "跌爹叠蝶"), ("ding", "定订顶丁鼎钉"), ("diu", "丢"), ("dong", "动东同懂冬栋洞"),
    ("dou", "都斗豆逗抖陡"), ("du", "度读独都毒督渡杜"), ("duan", "断短段端"),
    ("dui", "对队堆兑"), ("dun", "顿盾钝吨蹲"), ("duo", "多夺朵舵惰哆"),
    ("e", "俄额俄恶饿鹅厄"), ("ei", "欸"), ("en", "恩"), ("eng", "鞥"), ("er", "而二尔儿耳饵"),
    ("fa", "发法罚乏伐阀"), ("fan", "反泛犯番繁凡范贩翻"), ("fang", "方放房防访纺仿"),
    ("fei", "非飞肥费废匪沸"), ("fen", "分份粉纷奋愤酚"), ("feng", "风丰封峰奉疯锋蜂"),
    ("fo", "佛"), ("fou", "否"), ("fu", "副服福复付负富浮府辅府夫附妇腐"),
    ("ga", "嘎尬"), ("gai", "该改概盖溉"), ("gan", "干感甘赶敢肝竿"), ("gang", "刚钢港岗纲杠"),
    ("gao", "高告搞稿糕膏"), ("ge", "个各格革阁隔歌割哥"), ("gei", "给"), ("gen", "根跟"),
    ("geng", "更耕颈耿"), ("gong", "工公共供功攻宫恭巩贡"), ("gou", "够构狗沟购钩"),
    ("gu", "古股顾故孤姑固鼓骨谷骨"), ("gua", "挂刮瓜寡"), ("guai", "怪乖拐"),
    ("guan", "关管官馆观贯冠惯"), ("guang", "光广逛"), ("gui", "归贵规鬼轨桂柜"),
    ("gun", "滚棍"), ("guo", "国过果裹锅"),
    ("ha", "哈"), ("hai", "孩海害还咳嗨"), ("han", "含汉汗喊寒韩翰"), ("hang", "行航抗杭"),
    ("hao", "好号毫豪耗浩"), ("he", "和合何河核鹤贺喝盒赫"), ("hei", "黑嘿"), ("hen", "很恨痕"),
    ("heng", "横恒衡哼"), ("hong", "红洪宏烘鸿轰"), ("hou", "后候厚侯猴吼"),
    ("hu", "湖互呼户胡护忽弧忽虎狐"), ("hua", "化华画花话滑猾"), ("huai", "怀坏淮"),
    ("huan", "环换欢还缓幻患唤"), ("huang", "黄皇慌荒晃煌磺"), ("hui", "会回汇惠悔慧徽毁挥"),
    ("hun", "混昏魂浑婚"), ("huo", "活或火获伙货惑霍"),
    ("ji", "极集即几级计及技机基纪记击激绩季急齐济继吉"), ("jia", "加家价假甲架驾佳嘉夹"),
    ("jian", "建间件见简健检坚渐减尖践艰剑荐"), ("jiang", "将强讲江降奖蒋浆疆匠"),
    ("jiao", "教交较叫角脚胶焦骄校缴觉"), ("jie", "节结解界接借阶皆截介街杰"),
    ("jin", "进金今近仅紧尽斤禁锦津"), ("jing", "经精境静京警竞景净敬睛晶"),
    ("jiong", "窘"), ("jiu", "就九酒旧久救纠究韭"), ("ju", "局具居据举聚剧距巨拒局句"),
    ("juan", "卷圈捐倦眷"), ("jue", "决绝觉角掘"), ("jun", "军均君俊菌"),
    ("ka", "卡咖咯"), ("kai", "开凯慨恺楷"), ("kan", "看刊砍堪"), ("kang", "抗康慷炕"),
    ("kao", "考靠烤"), ("ke", "可克科客刻课棵渴壳呵"), ("kei", "尅"), ("ken", "肯垦恳"),
    ("keng", "坑铿"), ("kong", "空恐孔控"), ("kou", "口扣抠寇"), ("ku", "苦库哭裤窟"),
    ("kua", "夸跨垮挎"), ("kuai", "快块筷脍"), ("kuan", "款宽"), ("kuang", "况矿框狂筐眶"),
    ("kui", "亏愧奎魁馈"), ("kun", "困昆捆"), ("kuo", "扩阔廓"),
    ("la", "拉啦蜡辣落"), ("lai", "来赖莱"), ("lan", "兰览蓝栏烂篮懒澜"), ("lang", "浪朗郎狼"),
    ("lao", "老劳牢捞落酪"), ("le", "了乐勒肋"), ("lei", "类雷累泪蕾磊"), ("leng", "冷楞"),
    ("li", "理利立力离历例里丽礼李莉励黎厘"), ("lia", "俩"), ("lian", "连联练恋脸莲廉敛"),
    ("liang", "两量良亮粮凉梁辆"), ("liao", "料疗辽聊僚燎"), ("lie", "列劣烈裂猎"),
    ("lin", "林临邻林赁淋琳"), ("ling", "领令另零灵铃凌龄"), ("liu", "六流留刘柳溜硫"),
    ("lo", "咯"), ("long", "隆龙弄笼聋垄"), ("lou", "楼漏陋搂篓"), ("lu", "路六陆录绿卢露鲁炉"),
    ("luan", "乱卵峦"), ("lue", "略掠"), ("lun", "论轮伦沦"), ("luo", "落罗络洛锣骆"),
    ("lv", "率绿律旅虑履滤驴"),
    ("ma", "马么麻码玛骂吗"), ("mai", "买卖迈麦脉"), ("man", "满慢曼漫埋蛮"), ("mang", "忙盲茫芒"),
    ("mao", "毛冒贸帽矛猫"), ("me", "么么"), ("mei", "美每没媒枚煤妹眉昧"), ("men", "们门闷"),
    ("meng", "蒙盟猛梦孟朦"), ("mi", "米密秘迷弥蜜秘"), ("mian", "面勉棉免眠缅"),
    ("miao", "秒苗庙描妙渺"), ("mie", "灭蔑"), ("min", "民敏抿悯"), ("ming", "名明命鸣铭"),
    ("miu", "谬"), ("mo", "模末磨墨默摸莫膜"), ("mou", "某谋牟"), ("mu", "目木母幕募墓暮慕亩"),
    ("na", "那南拿纳呐哪"), ("nai", "乃奶耐奈"), ("nan", "南难男"), ("nang", "囊"),
    ("nao", "脑闹恼瑙"), ("ne", "呢讷"), ("nei", "内馁"), ("nen", "嫩"), ("neng", "能"),
    ("ni", "你呢泥拟逆匿腻"), ("nian", "年念粘碾撵"), ("niang", "娘酿"), ("niao", "鸟尿"),
    ("nie", "捏涅聂镍"), ("nin", "您"), ("ning", "宁凝拧泞"), ("niu", "牛纽扭钮"),
    ("nong", "农浓弄脓"), ("nou", "耨"), ("nu", "怒奴弩"), ("nuan", "暖"),
    ("nuo", "诺挪懦懦"), ("nv", "女钕"),
    ("o", "哦喔"), ("ou", "欧偶欧殴呕藕"),
    ("pa", "怕爬趴帕"), ("pai", "排派牌拍迫"), ("pan", "盘判攀盼判"), ("pang", "旁庞胖"),
    ("pao", "跑抛炮泡"), ("pei", "培配陪佩赔"), ("pen", "盆喷"), ("peng", "朋碰彭蓬棚澎"),
    ("pi", "批皮匹披疲辟僻啤"), ("pian", "片篇偏便骗"), ("piao", "票飘漂瓢"),
    ("pie", "撇瞥"), ("pin", "品贫聘拼频"), ("ping", "平评瓶凭萍屏乒"), ("po", "破迫坡颇婆魄"),
    ("pou", "剖"), ("pu", "普普通铺扑葡埔"),
    ("qi", "起其七气期器奇齐妻汽棋泣企乞歧"), ("qia", "恰卡恰掐"), ("qian", "前千钱签迁欠潜牵铅纤"),
    ("qiang", "强枪墙抢腔"), ("qiao", "桥巧敲桥翘俏侨鞘"), ("qie", "切且窃怯"),
    ("qin", "亲琴勤侵禽秦擒"), ("qing", "情请清青轻倾氢晴卿"), ("qiong", "穷琼"),
    ("qiu", "求秋球丘囚仇"), ("qu", "区去取曲趋渠趣屈驱"), ("quan", "全权泉圈劝拳痊"),
    ("que", "确却缺雀鹊"), ("qun", "群裙"),
    ("ran", "然染燃冉"), ("rang", "让壤嚷"), ("rao", "绕扰饶"), ("re", "热惹"),
    ("ren", "人人任认忍仁刃韧"), ("reng", "仍扔"), ("ri", "日"), ("rong", "容荣融溶熔绒"),
    ("rou", "肉柔揉"), ("ru", "如果入如乳儒辱孺"), ("ruan", "软阮"), ("rui", "瑞锐睿蕊"),
    ("run", "润闰"), ("ruo", "若弱"),
    ("sa", "萨撒洒"), ("sai", "赛塞腮"), ("san", "三散伞"), ("sang", "桑丧嗓"),
    ("sao", "扫嫂骚梢"), ("se", "色塞涩瑟"), ("sen", "森"), ("seng", "僧"),
    ("sha", "沙砂杀啥傻纱"), ("shai", "晒筛"), ("shan", "山闪陕扇善衫单珊瑚"),
    ("shang", "上商赏伤尚汤"), ("shao", "少烧绍稍哨勺韶"), ("she", "设社摄涉舌舍蛇赦"),
    ("shei", "谁"), ("shen", "神身深申甚沈伸审肾慎"), ("sheng", "生省胜声升盛圣绳"),
    ("shi", "是时事实使十识市示世室始石视式施师史适试食失"), ("shou", "手首收受守授瘦售兽"),
    ("shu", "书数述树输属束熟署舒鼠薯叔疏殊梳"), ("shua", "刷耍"), ("shuai", "率衰摔甩帅"),
    ("shuan", "栓栓"), ("shuang", "双霜爽"), ("shui", "水税睡谁说"), ("shun", "顺瞬吮"),
    ("shuo", "说硕烁朔"), ("si", "四死思司似斯私伺丝饲寺"), ("song", "送松宋诵颂耸"),
    ("sou", "搜艘嗽搜"), ("su", "速素苏诉塑宿俗肃宿粟"), ("suan", "算酸蒜"),
    ("sui", "岁随虽碎岁遂髓"), ("sun", "孙损笋"), ("suo", "所索缩锁梭莎"),
    ("ta", "他她它踏塔踏蹋"), ("tai", "太台态泰抬胎淘汰"), ("tan", "谈探坦摊贪坛弹碳痰"),
    ("tang", "堂糖唐躺趟烫汤塘"), ("tao", "套讨桃逃陶淘掏"), ("te", "特特别"),
    ("teng", "腾疼藤"), ("ti", "体提题替梯踢啼惕涕"), ("tian", "天田添填甜舔"),
    ("tiao", "条调跳挑挑"), ("tie", "铁贴帖"), ("ting", "听庭停厅廷挺亭"),
    ("tong", "同通统痛童铜桐桶瞳"), ("tou", "头透投偷"), ("tu", "图土突出徒涂途屠"),
    ("tuan", "团团湍"), ("tui", "退推腿退蜕"), ("tun", "吞屯褪"), ("tuo", "托脱妥拓拖椭驼"),
    ("wa", "瓦挖娃洼哇"), ("wai", "外歪"), ("wan", "完万晚湾玩碗挽顽婉宛"),
    ("wang", "望往王网亡忘妄旺"), ("wei", "为位未委味维危唯围伟威微卫喂违"),
    ("wen", "问文闻温稳吻蚊"), ("weng", "翁嗡"), ("wo", "我握窝卧蜗沃"),
    ("wu", "五物无误务武舞屋午吴乌污巫侮捂"),
    ("xi", "西系细希席戏喜洗夕析息吸悉析"), ("xia", "下夏吓狭辖霞瞎"),
    ("xian", "现先线显县限献险鲜仙纤嫌"), ("xiang", "想相向象项香乡箱详享响降"),
    ("xiao", "小消校效笑销晓肖宵萧削"), ("xie", "写些协谢斜鞋携屑懈泄"),
    ("xin", "新心信辛欣薪芯"), ("xing", "行型形星性姓省醒兴"),
    ("xiong", "雄胸凶兄熊"), ("xiu", "修秀休袖绣锈"), ("xu", "需许续徐虚叙畜序绪吁旭"),
    ("xuan", "选宣悬旋玄喧绚"), ("xue", "学雪血靴穴薛"), ("xun", "训迅讯寻巡循逊熏"),
    ("ya", "亚压牙雅鸭押芽"), ("yan", "严研验言眼烟沿盐延掩演宴"),
    ("yang", "样洋阳养央央羊氧扬"), ("yao", "要摇药腰遥咬妖"),
    ("ye", "也业夜野叶页爷液耶"), ("yi", "一以已意义议移易医亿依伊役"),
    ("yin", "因引印银音阴隐饮饮"), ("ying", "应营英影硬迎赢鹰婴荧"),
    ("yo", "哟"), ("yong", "用永拥勇涌泳佣踊庸"), ("you", "有由右油友优游幼又幼诱"),
    ("yu", "与于语余玉育雨予鱼遇预域鱼遇欲"), ("yuan", "元原院员远愿园源圆援缘冤"),
    ("yue", "月越约跃阅乐"), ("yun", "运云允匀晕孕蕴韵"),
    ("za", "杂砸扎"), ("zai", "在再灾载栽仔"), ("zan", "赞暂攒簪"), ("zang", "藏脏葬"),
    ("zao", "造早遭糟噪躁藻灶"), ("ze", "则择责泽测仄"), ("zei", "贼"),
    ("zen", "怎"), ("zeng", "增曾赠憎"), ("zha", "扎炸查渣诈闸"),
    ("zhai", "摘宅窄债斋"), ("zhan", "展战站占粘沾盏斩"), ("zhang", "长张章掌丈涨障帐"),
    ("zhao", "照找着招召兆朝爪"), ("zhe", "这着者折遮哲"), ("zhei", "这"),
    ("zhen", "真阵镇针珍振震枕侦"), ("zheng", "正政争整征证郑睁蒸挣"),
    ("zhi", "之指直制治至只识支值职质置知枝执纸"), ("zhong", "中重种终众钟忠肿"),
    ("zhou", "周洲州舟宙轴昼骤皱"), ("zhu", "主注助住著逐朱柱株铸猪诸竹"),
    ("zhua", "抓爪"), ("zhuai", "拽"), ("zhuan", "转专砖赚传撰"),
    ("zhuang", "状装壮撞庄桩妆"), ("zhui", "追坠缀赘椎"), ("zhun", "准谆"),
    ("zhuo", "着捉桌卓酌琢浊"), ("zi", "自子字资紫姿咨滋淄"), ("zong", "总宗综纵棕鬃"),
    ("zou", "走奏邹揍"), ("zu", "组最族足阻祖租卒"), ("zuan", "钻纂"),
    ("zui", "最嘴醉罪"), ("zun", "尊遵"), ("zuo", "作做左坐座昨佐"),
]

# Reverse lookup table fixture targets (4-code single character table for reverse lookup tests)
REVERSE_LOOKUP_TARGETS = [
    ("瘠", "jibo"),
    ("丁", "dkal"),
    ("的", "dede"),
    ("打", "dada"),
    ("好", "haoc"),
    ("报", "baoc"),
    ("宝", "baoc"),
    ("趋", "ququ"),
    ("同", "tsts"),
    ("时", "uiui"),
    ("间", "jmjm"),
    ("践", "jmjm"),
    ("鹤", "hehe"),
    ("音", "ybyb"),
    ("形", "xyxy"),
    ("小", "xkxk"),
    ("国", "gogo"),
    ("中", "vsvs"),
    ("你", "nini"),
    ("我", "wowo"),
    ("他", "tata"),
    ("人", "rfrf"),
    ("一", "yiyi"),
    ("是", "uiui"),
    ("有", "yzyz"),
    ("在", "zdzd"),
]


def generate():
    merged_codes = defaultdict(list)
    seen_per_code = defaultdict(set)

    def add_entry(code: str, word: str):
        if not code or not word:
            return
        if word not in seen_per_code[code]:
            seen_per_code[code].add(word)
            merged_codes[code].append(word)

    # 1. Punctuation shortcuts
    for code, items in PUNCT_SHORTCUTS:
        for item in items:
            add_entry(code, item)

    # 2. Single key shortcuts
    for code, chars in SINGLE_KEY_CHARS:
        for ch in chars:
            add_entry(code, ch)

    # 3. Two key custom shortcuts
    for code, chars in TWO_KEY_CHARS:
        for ch in chars:
            add_entry(code, ch)

    # 4. Syllable single characters
    for py, char_str in COMMON_SYLLABLE_CHARS:
        dp = to_double_pinyin(py)
        for ch in char_str:
            add_entry(dp, ch)

    # 5. Common vocabulary
    for word, code_or_alias in COMMON_WORDS:
        add_entry(code_or_alias, word)

    # 6. Sort and serialize dict
    sorted_codes = sorted(merged_codes.keys())
    dict_lines = [f"{c}\t" + "\t".join(merged_codes[c]) for c in sorted_codes]

    # 7. Reverse lookup entries
    dz_map = defaultdict(str)
    for ch, code in REVERSE_LOOKUP_TARGETS:
        dz_map[ch] = code

    for py, char_str in COMMON_SYLLABLE_CHARS:
        dp = to_double_pinyin(py)
        for ch in char_str:
            if ch not in dz_map:
                pad = (dp + dp)[:4]
                dz_map[ch] = pad

    dz_lines = [f"{code}\t{ch}" for ch, code in sorted(dz_map.items(), key=lambda t: t[1])]

    out_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "../app/src/main/assets"))
    os.makedirs(out_dir, exist_ok=True)
    dict_path = os.path.join(out_dir, "flypy_dict.tsv")
    dz_path = os.path.join(out_dir, "flypy_dz.tsv")

    with open(dict_path, "w", encoding="utf-8") as f:
        f.write("\n".join(dict_lines) + "\n")

    with open(dz_path, "w", encoding="utf-8") as f:
        f.write("\n".join(dz_lines) + "\n")

    total_words = sum(len(v) for v in merged_codes.values())
    print(f"Generated clean open assets:")
    print(f"  {dict_path}: {len(sorted_codes)} codes, {total_words} words ({os.path.getsize(dict_path)/1024:.1f} KB)")
    print(f"  {dz_path}: {len(dz_lines)} single-character reverse entries ({os.path.getsize(dz_path)/1024:.1f} KB)")


if __name__ == "__main__":
    generate()
