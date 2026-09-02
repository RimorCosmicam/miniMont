package com.minimont.cover.model


/**
 * The colour pair the halftone field is drawn from: a ground and the ink of the dots.
 *
 * Mont allows one accent at a time, so the ink doubles as the interface accent — there is never a
 * second colour competing with it. Mustard is the default because it is the language's poster
 * colour, the one onboarding is built from.
 */

enum class HalftoneColorway(
    val displayName: String,
    val groundHex: Long,
    val inkHex: Long
) {
    MUSTARD("Mustard", 0xFF141210, 0xFFD8A628),
    PAPER("Paper", 0xFFF4F1EA, 0xFF17171A),
    SIGNAL("Signal", 0xFFFAFAF7, 0xFF2F6BFF),
    MONO("Mono", 0xFF030303, 0xFFBFBFBF),
    OPERATOR("Operator", 0xFF000300, 0xFF00C73B),
    AMBER("Amber", 0xFF050300, 0xFFFFA51E),
    EMBER("Ember", 0xFF060201, 0xFFE0642A),
    ICE("Ice", 0xFF01040A, 0xFF63B8E8),
    TEAL("Teal", 0xFF01060A, 0xFF3FB8AE),
    MOSS("Moss", 0xFF020402, 0xFF71B27A),
    SAKURA("Sakura", 0xFF080306, 0xFFE676A6),
    LAVENDER("Lavender", 0xFF040308, 0xFFA58AD8),
    VIOLET("Violet", 0xFF04020A, 0xFF7B4FD1),
    ROSE("Rose", 0xFF070405, 0xFFD98C7A),
    COTTON("Cotton", 0xFFFFF4F8, 0xFFFF6FA6),
    MINT("Mint", 0xFFEFFFF8, 0xFF3FD1A0)
}


enum class VisualFilter(val displayName: String) {
    NONE("Clean"),
    VIVID("Vivid"),
    MONO("Mono"),
    WARM("Warm"),
    COOL("Cool"),
    CHROMATIC("Chroma"),
    ACID("Acid"),
    INVERT("Negative"),
    DREAM("Dream")
}


enum class HapticStrength(val displayName: String, val durationMs: Long) {
    OFF("Off", 0L),
    SUBTLE("Subtle", 10L),
    CRISP("Crisp", 20L),
    STRONG("Strong", 35L)
}


enum class KeyHeightLevel(val displayName: String, val heightDp: Int) {
    COMPACT("Compact", 40),
    BALANCED("Balanced", 46),
    TALL("Tall", 52)
}
