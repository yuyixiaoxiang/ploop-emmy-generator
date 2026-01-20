package com.emmylua.generator

import com.intellij.openapi.editor.Document

object LuaTableFormatter {

    private fun findCurlyBlockEndLine(lines: List<String>, startLine: Int): Int? {
        var depth = 0
        var started = false
        for (i in startLine until lines.size) {
            val line = lines[i]
            val openCount = line.count { it == '{' }
            val closeCount = line.count { it == '}' }
            if (openCount > 0) started = true
            if (!started) continue
            depth += openCount - closeCount
            if (started && depth <= 0) return i
        }
        return null
    }

    private fun pickEntryIndent(lines: List<String>, startLine: Int, endLine: Int): String {
        val baseIndent = lines.getOrNull(startLine)?.takeWhile { it.isWhitespace() } ?: ""
        val entryIndent = (startLine + 1 until endLine)
            .asSequence()
            .mapNotNull { idx ->
                val raw = lines.getOrNull(idx) ?: return@mapNotNull null
                if (raw.isBlank()) return@mapNotNull null
                val t = raw.trimStart()
                if (t.startsWith("--")) return@mapNotNull null
                raw.takeWhile { it.isWhitespace() }
            }
            .firstOrNull()
        return entryIndent ?: (baseIndent + "    ")
    }

    /**
     * 格式化形如 `local _LAZY_REQUIRE = { ... }` 的表：
     * - 拆分同一行多个 `key = "value"` 的情况
     * - 每个 entry 独占一行
     * - 强制每行末尾逗号
     */
    fun formatLazyRequireTable(doc: Document): Boolean {
        val lines = doc.text.lines()
        val startRe = Regex("""^\s*local\s+_LAZY_REQUIRE\s*=\s*\{\s*$""")
        val startLine = lines.indexOfFirst { startRe.containsMatchIn(it) }
        if (startLine == -1) return false

        val endLine = findCurlyBlockEndLine(lines, startLine) ?: return false
        val entryIndent = pickEntryIndent(lines, startLine, endLine)

        val keyValueRe = Regex(
            """(?:\[\s*[\"']([^\"']+)[\"']\s*\]|([A-Za-z_][A-Za-z0-9_]*))\s*=\s*[\"']([^\"']+)[\"']"""
        )

        val entries = mutableListOf<Pair<String, String>>()
        for (i in (startLine + 1) until endLine) {
            val raw = lines[i]
            val t = raw.trimStart()
            if (t.isEmpty()) continue
            if (t.startsWith("--")) continue

            val matches = keyValueRe.findAll(raw).toList()
            if (matches.isEmpty()) continue

            for (m in matches) {
                val key = (m.groupValues[1].ifBlank { m.groupValues[2] }).trim()
                val value = m.groupValues[3].trim()
                if (key.isNotBlank() && value.isNotBlank()) {
                    entries.add(key to value)
                }
            }
        }

        fun formatKey(key: String): String {
            val ok = Regex("""^[A-Za-z_][A-Za-z0-9_]*$""").matches(key)
            return if (ok) key else "[\"$key\"]"
        }

        val body = buildString {
            for ((k, v) in entries) {
                append(entryIndent)
                append(formatKey(k))
                append(" = \"")
                append(v)
                append("\",\n")
            }
        }

        val replaceStart = doc.getLineStartOffset(startLine + 1)
        val replaceEnd = doc.getLineStartOffset(endLine)
        doc.replaceString(replaceStart, replaceEnd, body)
        return true
    }

    /**
     * 格式化形如 `TableName = { ... }` 的字符串列表：
     * - 拆分同一行多个 "xxx" 的情况
     * - 每个 entry 独占一行
     * - 强制每行末尾逗号
     * 返回：是否找到并格式化。
     */
    fun formatStringListTable(doc: Document, tableName: String): Boolean {
        val lines = doc.text.lines()
        val startRe = Regex("""^\s*${Regex.escape(tableName)}\s*=\s*\{\s*$""")
        val startLine = lines.indexOfFirst { startRe.containsMatchIn(it) }
        if (startLine == -1) return false

        val endLine = findCurlyBlockEndLine(lines, startLine) ?: return false
        val entryIndent = pickEntryIndent(lines, startLine, endLine)

        val strRe = Regex("""[\"']([^\"']+)[\"']""")
        val values = mutableListOf<String>()
        for (i in (startLine + 1) until endLine) {
            val raw = lines[i]
            val t = raw.trimStart()
            if (t.isEmpty()) continue
            if (t.startsWith("--")) continue

            for (m in strRe.findAll(raw)) {
                val v = m.groupValues[1].trim()
                if (v.isNotBlank()) values.add(v)
            }
        }

        val body = buildString {
            for (v in values) {
                append(entryIndent)
                append('"')
                append(v)
                append("\",\n")
            }
        }

        val replaceStart = doc.getLineStartOffset(startLine + 1)
        val replaceEnd = doc.getLineStartOffset(endLine)
        doc.replaceString(replaceStart, replaceEnd, body)
        return true
    }
}
