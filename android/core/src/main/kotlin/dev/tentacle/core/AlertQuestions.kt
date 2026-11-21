package dev.tentacle.core

import java.text.Normalizer
import java.util.Locale
import kotlin.random.Random

data class AlertQuestion(
    val lang: String,
    val prompt: String,
    val answers: List<String>,
    val domain: String,
) {
    fun matches(input: String): Boolean {
        val normalized = normalizeAlertAnswer(input)
        return normalized.isNotEmpty() && answers.any { normalizeAlertAnswer(it) == normalized }
    }
}

// No regex here: Android's ICU engine rejects the JVM-only (?U) flag, so whitespace is collapsed by hand.
fun normalizeAlertAnswer(value: String): String = collapseWhitespace(Normalizer.normalize(value, Normalizer.Form.NFKC))
    .lowercase(Locale.ROOT)
    .trimEnd('.', '。', '!', '！')
    .trimEnd()

private fun collapseWhitespace(value: String): String = buildString {
    var pendingSpace = false
    for (character in value) {
        if (character.isWhitespace() || Character.isSpaceChar(character)) {
            pendingSpace = isNotEmpty()
        } else {
            if (pendingSpace) append(' ')
            pendingSpace = false
            append(character)
        }
    }
}

data class QuestionPick(val question: AlertQuestion, val changed: Boolean)

/** Keeps question choice and the two-wrong-answer rotation rule independent of Android UI state. */
class AlertQuestionPicker(
    private val questions: List<AlertQuestion>,
    private val randomIndex: (Int) -> Int = { Random.nextInt(it) },
) {
    init { require(questions.isNotEmpty()) { "At least one question is required" } }

    private var selectedIndex = chooseIndex(null)
    private var wrongAttempts = 0

    fun current(): AlertQuestion = questions[selectedIndex]

    fun submitWrong(): QuestionPick {
        wrongAttempts++
        if (wrongAttempts < WRONG_ATTEMPTS_PER_QUESTION) return QuestionPick(current(), false)
        wrongAttempts = 0
        selectedIndex = chooseIndex(selectedIndex)
        return QuestionPick(current(), true)
    }

    private fun chooseIndex(previous: Int?): Int {
        if (questions.size == 1) return 0
        val candidate = randomIndex(questions.size - if (previous == null) 0 else 1)
        require(candidate >= 0 && candidate < questions.size - if (previous == null) 0 else 1) {
            "randomIndex returned an invalid index"
        }
        return if (previous != null && candidate >= previous) candidate + 1 else candidate
    }

    companion object { const val WRONG_ATTEMPTS_PER_QUESTION = 2 }
}

object AlertQuestions {
    val bundled: List<AlertQuestion> = listOf(
        // English: seven stable, short-answer questions in each required domain.
        q("en", "math", "12 × 3 = ?", "36", "thirty six", "thirty-six"),
        q("en", "math", "What is the square root of 81?", "9", "nine"),
        q("en", "math", "What is 15 + 27?", "42", "forty two", "forty-two"),
        q("en", "math", "What is 100 ÷ 4?", "25", "twenty five", "twenty-five"),
        q("en", "math", "How many degrees are in a right angle?", "90", "90 degrees", "ninety", "ninety degrees"),
        q("en", "math", "What is 7 squared?", "49", "forty nine", "forty-nine"),
        q("en", "math", "What is 3/4 as a decimal?", "0.75", ".75"),
        q("en", "geography", "What is the capital of Japan?", "Tokyo"),
        q("en", "geography", "Which continent contains Kenya?", "Africa"),
        q("en", "geography", "What ocean lies east of Africa?", "Indian Ocean", "the Indian Ocean"),
        q("en", "geography", "What is the capital of Canada?", "Ottawa"),
        q("en", "geography", "Which major river flows through Cairo?", "Nile", "the Nile", "Nile River", "the Nile River"),
        q("en", "geography", "Which continent is Brazil in?", "South America"),
        q("en", "geography", "What is the capital of Italy?", "Rome"),
        q("en", "physics", "What is the SI unit of force?", "newton", "newtons", "N"),
        q("en", "physics", "What force pulls objects toward Earth?", "gravity", "gravitational force"),
        q("en", "physics", "What is the SI unit of electric current?", "ampere", "amperes", "amp", "amps", "A"),
        q("en", "physics", "Does sound travel faster in air or water?", "water", "in water"),
        q("en", "physics", "What type of energy is stored in a stretched spring?", "elastic potential energy", "potential energy"),
        q("en", "physics", "What instrument measures electric current?", "ammeter", "an ammeter"),
        q("en", "physics", "What particle has a negative electric charge?", "electron", "an electron"),
        q("en", "chemistry", "What is the chemical formula for water?", "H2O"),
        q("en", "chemistry", "What element has the symbol O?", "oxygen"),
        q("en", "chemistry", "Is a solution with pH 2 acidic or basic?", "acidic", "acid"),
        q("en", "chemistry", "What gas is released when an acid reacts with a carbonate?", "carbon dioxide", "CO2"),
        q("en", "chemistry", "What is the chemical symbol for gold?", "Au"),
        q("en", "chemistry", "What is the smallest unit of an element?", "atom", "an atom"),
        q("en", "chemistry", "What element has atomic number 1?", "hydrogen"),
        q("en", "biology", "What organ pumps blood around the human body?", "heart", "the heart"),
        q("en", "biology", "What process lets plants make food using light?", "photosynthesis"),
        q("en", "biology", "What molecule carries genetic information?", "DNA"),
        q("en", "biology", "Which blood cells carry oxygen?", "red blood cells", "red cells", "erythrocytes"),
        q("en", "biology", "What organ is mainly responsible for breathing?", "lungs", "the lungs"),
        q("en", "biology", "What is the basic unit of life?", "cell", "a cell"),
        q("en", "biology", "Which part of a plant absorbs most water?", "roots", "the roots", "root"),
        q("en", "history", "Which ancient civilization built the pyramids at Giza?", "Egyptians", "ancient Egyptians", "Egypt"),
        q("en", "history", "In which country did the Renaissance begin?", "Italy"),
        q("en", "history", "Who wrote the Declaration of Independence's first draft?", "Thomas Jefferson", "Jefferson"),
        q("en", "history", "Which volcano buried Pompeii in AD 79?", "Vesuvius", "Mount Vesuvius"),
        q("en", "history", "What writing system did ancient Egyptians use?", "hieroglyphs", "hieroglyphics"),
        q("en", "history", "What long wall was built in ancient China to defend its northern border?", "Great Wall", "the Great Wall", "Great Wall of China", "the Great Wall of China"),
        q("en", "history", "Who was the first person to walk on the Moon?", "Neil Armstrong", "Armstrong"),
        q("en", "language", "What is the past tense of 'go'?", "went"),
        q("en", "language", "What is the plural of 'child'?", "children"),
        q("en", "language", "What part of speech is the word 'quickly'?", "adverb", "an adverb"),
        q("en", "language", "What is the opposite of 'hot'?", "cold"),
        q("en", "language", "How many syllables are in 'computer'?", "3", "three"),
        q("en", "language", "What punctuation mark ends a direct question?", "question mark", "a question mark", "?"),
        q("en", "language", "What is the comparative form of 'good'?", "better"),

        // Chinese: the same breadth, with natural short answers and stable facts.
        q("zh", "math", "12 × 3 等于多少？", "36", "三十六"),
        q("zh", "math", "81 的算术平方根是多少？", "9", "九"),
        q("zh", "math", "15 + 27 等于多少？", "42", "四十二"),
        q("zh", "math", "100 ÷ 4 等于多少？", "25", "二十五"),
        q("zh", "math", "直角是多少度？", "90", "90度", "九十", "九十度"),
        q("zh", "math", "7 的平方是多少？", "49", "四十九"),
        q("zh", "math", "四分之三写成小数是多少？", "0.75", ".75"),
        q("zh", "geography", "日本的首都是哪里？", "东京", "东京都"),
        q("zh", "geography", "肯尼亚位于哪个大洲？", "非洲"),
        q("zh", "geography", "非洲东侧是什么大洋？", "印度洋"),
        q("zh", "geography", "加拿大的首都是哪里？", "渥太华"),
        q("zh", "geography", "流经开罗的主要河流是什么？", "尼罗河"),
        q("zh", "geography", "巴西位于哪个大洲？", "南美洲", "南美"),
        q("zh", "geography", "意大利的首都是哪里？", "罗马", "罗马市"),
        q("zh", "physics", "力的国际单位是什么？", "牛顿", "N"),
        q("zh", "physics", "把物体拉向地球的力是什么？", "重力", "万有引力"),
        q("zh", "physics", "电流的国际单位是什么？", "安培", "安", "A"),
        q("zh", "physics", "声音在空气和水中哪个传播得更快？", "水", "水中", "在水中"),
        q("zh", "physics", "拉伸的弹簧储存什么能量？", "弹性势能", "势能"),
        q("zh", "physics", "测量电流的仪器叫什么？", "电流表", "安培表"),
        q("zh", "physics", "带负电的粒子是什么？", "电子"),
        q("zh", "chemistry", "水的化学式是什么？", "H2O"),
        q("zh", "chemistry", "元素符号 O 代表什么元素？", "氧", "氧元素"),
        q("zh", "chemistry", "pH 为 2 的溶液是酸性还是碱性？", "酸性", "酸"),
        q("zh", "chemistry", "酸与碳酸盐反应会释放什么气体？", "二氧化碳", "CO2"),
        q("zh", "chemistry", "金的化学符号是什么？", "Au"),
        q("zh", "chemistry", "保持元素化学性质的最小粒子是什么？", "原子"),
        q("zh", "chemistry", "原子序数为 1 的元素是什么？", "氢", "氢元素"),
        q("zh", "biology", "人体中泵送血液的器官是什么？", "心脏", "心"),
        q("zh", "biology", "植物利用光制造有机物的过程叫什么？", "光合作用"),
        q("zh", "biology", "携带遗传信息的主要分子是什么？", "DNA", "脱氧核糖核酸"),
        q("zh", "biology", "哪种血细胞负责运输氧气？", "红细胞", "红血球"),
        q("zh", "biology", "人体负责呼吸的主要器官是什么？", "肺", "肺脏"),
        q("zh", "biology", "生命的基本单位是什么？", "细胞"),
        q("zh", "biology", "植物主要通过哪个部位吸收水分？", "根", "根部"),
        q("zh", "history", "秦始皇统一六国后建立的朝代是哪个？", "秦", "秦朝"),
        q("zh", "history", "文艺复兴最早兴起于哪个国家？", "意大利"),
        q("zh", "history", "张骞出使西域发生在哪个朝代？", "汉", "汉朝", "西汉"),
        q("zh", "history", "公元 79 年掩埋庞贝城的是哪座火山？", "维苏威火山", "维苏威"),
        q("zh", "history", "中国古代四大发明中用来指示方向的是什么？", "指南针", "司南", "罗盘"),
        q("zh", "history", "郑和下西洋发生在哪个朝代？", "明", "明朝"),
        q("zh", "history", "第一位登上月球的人是谁？", "尼尔·阿姆斯特朗", "阿姆斯特朗", "尼尔阿姆斯特朗"),
        q("zh", "language", "“床前明月光”的下一句是什么？", "疑是地上霜"),
        q("zh", "language", "《静夜思》的作者是谁？", "李白"),
        q("zh", "language", "“迅速地”通常属于什么词性？", "副词"),
        q("zh", "language", "“高”的反义词是什么？", "低", "矮"),
        q("zh", "language", "“欲穷千里目”的下一句是什么？", "更上一层楼"),
        q("zh", "language", "直接问句末尾通常使用什么标点？", "问号", "?", "？"),
        q("zh", "language", "“三人行，必有我师焉”出自哪部书？", "论语", "《论语》"),
    )

    fun forLanguage(lang: String): List<AlertQuestion> = bundled.filter { it.lang == lang }

    private fun q(lang: String, domain: String, prompt: String, vararg answers: String) =
        AlertQuestion(lang, prompt, answers.toList(), domain)
}
