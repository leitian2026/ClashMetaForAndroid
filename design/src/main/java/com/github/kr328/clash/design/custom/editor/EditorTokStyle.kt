package com.github.kr328.clash.design.custom.editor

import android.graphics.Color

// CUSTOM (CUSTOM.md #46, not upstream): colours for YAML token kinds in the built-in editor.
internal class EditorTokStyle(night: Boolean) {
    private val colorKey = if (night) Color.parseColor("#82AAFF") else Color.parseColor("#1565C0")
    private val colorStr = if (night) Color.parseColor("#C3E88D") else Color.parseColor("#2E7D32")
    private val colorNum = if (night) Color.parseColor("#F78C6C") else Color.parseColor("#E65100")
    private val colorWord = if (night) Color.parseColor("#C792EA") else Color.parseColor("#7B1FA2")
    private val colorAnchor = if (night) Color.parseColor("#FFCB6B") else Color.parseColor("#8D6E00")
    private val colorComment = if (night) Color.parseColor("#7F8C98") else Color.parseColor("#757575")
    private val colorPunct = if (night) Color.parseColor("#89DDFF") else Color.parseColor("#00838F")

    fun colorOf(kind: Tok): Int = when (kind) {
        Tok.Key -> colorKey
        Tok.Str -> colorStr
        Tok.Num -> colorNum
        Tok.Word -> colorWord
        Tok.Anchor -> colorAnchor
        Tok.Comment -> colorComment
        Tok.Punct -> colorPunct
    }
}
