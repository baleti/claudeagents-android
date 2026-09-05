package dev.local.clauderelay

import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan

/**
 * Generic (language-agnostic) code token colorer -- no highlighting
 * library is reachable from this build, and Claude's replies mix bash/
 * python/kotlin/json/etc. freely enough that hand-maintaining a grammar
 * per language isn't worth it here. One combined regex classifies
 * comments/strings/numbers/keywords across the common scripting/C-like
 * languages actually seen in this environment's tool output; anything
 * else renders as plain code-colored text rather than guessing wrong.
 */
object SyntaxHighlight {
    private val KEYWORDS = setOf(
        "if", "else", "elif", "fi", "then", "for", "while", "do", "done", "case", "esac",
        "def", "function", "fn", "return", "import", "from", "export", "as",
        "class", "struct", "enum", "interface", "object", "trait", "impl",
        "let", "const", "var", "val", "new", "this", "self", "super",
        "try", "catch", "except", "finally", "throw", "raise", "switch", "match",
        "break", "continue", "pass", "true", "false", "true_", "None", "null", "nil", "undefined",
        "and", "or", "not", "in", "is", "async", "await", "yield", "lambda",
        "public", "private", "protected", "static", "final", "override", "abstract",
        "void", "int", "str", "bool", "float", "double", "char", "long", "short",
        "echo", "exit", "local", "readonly", "declare", "export"
    )

    // One alternation, longest/most-specific first: line comments (# or //),
    // block comments (/* */), triple/single/double/backtick-quoted strings,
    // then a bare word (checked against KEYWORDS) or a number.
    private val TOKEN_RE = Regex(
        "(#[^\n]*)" +
            "|(//[^\n]*)" +
            "|(/\\*.*?\\*/)" +
            "|(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`)" +
            "|\\b([A-Za-z_][A-Za-z0-9_]*)\\b" +
            "|\\b(\\d+(?:\\.\\d+)?)\\b"
    )

    fun append(out: SpannableStringBuilder, code: String) {
        var last = 0
        for (m in TOKEN_RE.findAll(code)) {
            if (m.range.first > last) out.append(code, last, m.range.first)
            val g = m.groups
            val start = out.length
            when {
                g[1] != null || g[2] != null || g[3] != null -> { // comment
                    out.append(m.value)
                    out.setSpan(ForegroundColorSpan(Theme.muted), start, out.length, 0)
                }
                g[4] != null -> { // string
                    out.append(m.value)
                    out.setSpan(ForegroundColorSpan(Theme.syntaxString), start, out.length, 0)
                }
                g[5] != null -> { // bare word: keyword or plain identifier
                    out.append(m.value)
                    if (m.value in KEYWORDS) {
                        out.setSpan(ForegroundColorSpan(Theme.syntaxKeyword), start, out.length, 0)
                    }
                }
                g[6] != null -> { // number
                    out.append(m.value)
                    out.setSpan(ForegroundColorSpan(Theme.syntaxNumber), start, out.length, 0)
                }
            }
            last = m.range.last + 1
        }
        if (last < code.length) out.append(code, last, code.length)
    }
}
