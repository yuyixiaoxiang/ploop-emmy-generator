package com.emmylua.generator

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 在以 Data 结尾、且文件路径/requirePath 含 GameData 的类名上右键：
 * 1) 提取 className 与 requirePath
 * 2) 注册到 Common/GamePlay/GameData/GameData.lua 的 local _LAZY_REQUIRE 表（不重复；若 path 已存在但 key 错则修正 key）
 * 3) 同时把 className 去掉 Data 后缀，注册到 GameData/init.lua 的 DataNames 列表（不重复；若没有 DataNames 则尝试 ModuleNames）
 */
class RegisterDataClassAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        EditorMouseLineTracker.ensureInstalled(project)

        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val document = editor.document

        val trackedMouseLine = editor.getUserData(EditorMouseLineTracker.LAST_MOUSE_LINE_KEY)
        val line = (trackedMouseLine ?: editor.caretModel.logicalPosition.line)
            .coerceIn(0, document.lineCount - 1)

        val lineText = document.text.lines().getOrNull(line) ?: ""
        if (!PloopParser.isClassDefinitionLine(lineText)) {
            Messages.showInfoMessage(project, "请在 class 定义行的类名上右键。", "注册当前Data类")
            return
        }

        val className = PloopParser.parseClassNameFromLine(lineText)
        if (className.isNullOrBlank() || !className.endsWith("Data", ignoreCase = true)) {
            Messages.showInfoMessage(project, "当前 class 不以 Data 结尾，无法注册。", "注册当前Data类")
            return
        }

        val currentFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (currentFile == null) {
            Messages.showInfoMessage(project, "未能获取当前文件。", "注册当前Data类")
            return
        }

        val luaRoot = resolveLuaRoot(project)
        val requirePath = computeRequirePath(currentFile, luaRoot)
        if (requirePath.isNullOrBlank()) {
            val hint = luaRoot?.toString()?.let { "\nLuaRoot: $it" } ?: ""
            Messages.showInfoMessage(project, "未能计算当前文件的 require 路径。$hint", "注册当前Data类")
            return
        }

        if (!requirePath.contains("GameData", ignoreCase = true) && !currentFile.path.contains("GameData", ignoreCase = true)) {
            Messages.showInfoMessage(project, "当前文件不在 GameData 路径下：$requirePath", "注册当前Data类")
            return
        }

        val gameDataFile = findGameDataLua(project, luaRoot)
        if (gameDataFile == null) {
            val hint = luaRoot?.resolve("Common")
                ?.resolve("GamePlay")
                ?.resolve("GameData")
                ?.resolve("GameData.lua")
                ?.toString()
                ?.let { "\n期望路径: $it" }
                ?: ""
            Messages.showInfoMessage(project, "未找到 GameData.lua。$hint", "注册当前Data类")
            return
        }

        val gameDataDoc = FileDocumentManager.getInstance().getDocument(gameDataFile)
        if (gameDataDoc == null) {
            Messages.showInfoMessage(project, "无法打开 GameData.lua 的 Document。", "注册当前Data类")
            return
        }

        val initFile = findGameDataInit(project, luaRoot)
        if (initFile == null) {
            val hint = luaRoot?.resolve("GameData")?.resolve("init.lua")?.toString()?.let { "\n期望路径: $it" } ?: ""
            Messages.showInfoMessage(project, "未找到 GameData/init.lua。$hint", "注册当前Data类")
            return
        }

        val initDoc = FileDocumentManager.getInstance().getDocument(initFile)
        if (initDoc == null) {
            Messages.showInfoMessage(project, "无法打开 GameData/init.lua 的 Document。", "注册当前Data类")
            return
        }

        val shortName = className.removeSuffix("Data").ifBlank { className }

        var formattedLazy = false
        var formattedNames = false
        var namesTableUsed: String? = null
        var lazyEdit: LazyEdit = LazyEdit.NotFound("not computed")
        var initEdit: ListEdit = ListEdit.NotFound("not computed")

        WriteCommandAction.runWriteCommandAction(project) {
            formattedLazy = LuaTableFormatter.formatLazyRequireTable(gameDataDoc)

            // 优先 DataNames，找不到则 fallback ModuleNames
            formattedNames = LuaTableFormatter.formatStringListTable(initDoc, "DataNames")
            namesTableUsed = if (formattedNames) "DataNames" else if (LuaTableFormatter.formatStringListTable(initDoc, "ModuleNames")) {
                formattedNames = true
                "ModuleNames"
            } else null

            lazyEdit = planLazyRequireEdit(gameDataDoc, className, requirePath)
            initEdit = planNamesListEdit(initDoc, namesTableUsed, shortName)

            applyLazyEdit(gameDataDoc, lazyEdit)
            applyListEdit(initDoc, initEdit)
        }

        FileDocumentManager.getInstance().saveDocument(gameDataDoc)
        FileDocumentManager.getInstance().saveDocument(initDoc)

        val msg = buildString {
            appendLine("Action: RegisterData")
            appendLine("Class: $className")
            appendLine("Path:  $requirePath")
            appendLine("GameData.lua: ${gameDataFile.path}")
            appendLine("init.lua:    ${initFile.path}")
            appendLine()
            appendLine("[0] Format")
            appendLine("GameData._LAZY_REQUIRE formatted: $formattedLazy")
            appendLine("init.lua names table formatted:   $formattedNames (${namesTableUsed ?: "not found"})")
            appendLine()
            appendLine("[1] GameData._LAZY_REQUIRE")
            appendLine(lazyEdit.describe())
            appendLine()
            appendLine("[2] GameData/init.lua ${namesTableUsed ?: "(names table not found)"}")
            appendLine(initEdit.describe())
        }.trimEnd()

        LargeInfoDialog.show(project, "注册当前Data类", msg)
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (editor == null || file?.extension?.lowercase() != "lua") {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val document = editor.document
        val trackedMouseLine = editor.getUserData(EditorMouseLineTracker.LAST_MOUSE_LINE_KEY)
        val line = (trackedMouseLine ?: editor.caretModel.logicalPosition.line)
            .coerceIn(0, document.lineCount - 1)

        val lineText = document.text.lines().getOrNull(line) ?: ""
        if (!PloopParser.isClassDefinitionLine(lineText)) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val className = PloopParser.parseClassNameFromLine(lineText)
        if (className.isNullOrBlank() || !className.endsWith("Data", ignoreCase = true)) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        e.presentation.isEnabledAndVisible = file.path.contains("GameData", ignoreCase = true)
    }

    private sealed interface LazyEdit {
        fun describe(): String

        data class Skip(val reason: String) : LazyEdit {
            override fun describe(): String = "Skip: $reason"
        }

        data class NotFound(val reason: String) : LazyEdit {
            override fun describe(): String = "Fail: $reason"
        }

        data class Insert(val insertOffset: Int, val beforeClosingBraceLine: Int, val newLine: String) : LazyEdit {
            override fun describe(): String = buildString {
                appendLine("Insert")
                appendLine("  位置：表尾（原 '}' 前，参考行号 $beforeClosingBraceLine）")
                appendLine("  写入：${newLine.trim()}")
            }.trimEnd()
        }

        data class Replace(
            val line: Int,
            val startOffset: Int,
            val endOffset: Int,
            val oldLine: String,
            val newLine: String
        ) : LazyEdit {
            override fun describe(): String = buildString {
                appendLine("Replace")
                appendLine("  原因：已存在相同 path，修正 key 为当前 class")
                appendLine("  位置：line ${line + 1}")
                appendLine("  原：${oldLine.trim()}")
                appendLine("  新：${newLine.trim()}")
            }.trimEnd()
        }
    }

    private sealed interface ListEdit {
        fun describe(): String

        data class Skip(val reason: String) : ListEdit {
            override fun describe(): String = "Skip: $reason"
        }

        data class NotFound(val reason: String) : ListEdit {
            override fun describe(): String = "Fail: $reason"
        }

        data class Insert(val insertOffset: Int, val beforeClosingBraceLine: Int, val newLine: String) : ListEdit {
            override fun describe(): String = buildString {
                appendLine("Insert")
                appendLine("  位置：表尾（原 '}' 前，参考行号 $beforeClosingBraceLine）")
                appendLine("  写入：${newLine.trim()}")
            }.trimEnd()
        }
    }

    private fun planLazyRequireEdit(doc: com.intellij.openapi.editor.Document, className: String, requirePath: String): LazyEdit {
        val lines = doc.text.lines()
        val startRe = Regex("""^\s*local\s+_LAZY_REQUIRE\s*=\s*\{\s*$""")
        val startLine = lines.indexOfFirst { startRe.containsMatchIn(it) }
        if (startLine == -1) return LazyEdit.NotFound("未找到 local _LAZY_REQUIRE = {")

        val endLine = findCurlyBlockEndLine(lines, startLine) ?: return LazyEdit.NotFound("无法定位 _LAZY_REQUIRE 的 }")

        val entryRe = Regex(
            """^\s*(?:\[\s*([\"'])([^\"']+)\1\s*\]|([A-Za-z_][A-Za-z0-9_]*))\s*=\s*([\"'])([^\"']+)\4\s*(,?)\s*(--.*)?$"""
        )

        for (i in (startLine + 1) until endLine) {
            val raw = lines[i]
            val m = entryRe.find(raw) ?: continue

            val key = (m.groupValues[2].takeIf { it.isNotBlank() } ?: m.groupValues[3]).trim()
            val value = m.groupValues[5].trim()
            val trailingComma = m.groupValues[6]
            val comment = m.groupValues.getOrNull(7)?.takeIf { it.isNotBlank() } ?: ""
            val indent = raw.takeWhile { it.isWhitespace() }
            val quote = m.groupValues[4].ifBlank { "\"" }

            if (key.equals(className, ignoreCase = true)) {
                return LazyEdit.Skip("key 已存在（line ${i + 1}）：${raw.trim()}")
            }

            if (value.equals(requirePath, ignoreCase = true)) {
                val comma = if (trailingComma.isBlank()) "," else trailingComma
                val newLine = "${indent}${className} = ${quote}${requirePath}${quote}${comma}${if (comment.isNotBlank()) " $comment" else ""}"
                return LazyEdit.Replace(
                    line = i,
                    startOffset = doc.getLineStartOffset(i),
                    endOffset = doc.getLineEndOffset(i),
                    oldLine = raw,
                    newLine = newLine
                )
            }
        }

        // 插入到 endLine 对应的 `}` 之前
        val baseIndent = lines[startLine].takeWhile { it.isWhitespace() }
        val entryIndent = (startLine + 1 until endLine)
            .asSequence()
            .mapNotNull { idx ->
                val t = lines[idx]
                if (t.isBlank()) null else t.takeWhile { it.isWhitespace() }
            }
            .firstOrNull()
            ?: (baseIndent + "    ")

        val entryLine = "${entryIndent}${className} = \"${requirePath}\","
        return LazyEdit.Insert(
            insertOffset = doc.getLineStartOffset(endLine),
            beforeClosingBraceLine = endLine + 1,
            newLine = entryLine + "\n"
        )
    }

    private fun applyLazyEdit(doc: com.intellij.openapi.editor.Document, edit: LazyEdit) {
        when (edit) {
            is LazyEdit.Insert -> doc.insertString(edit.insertOffset, edit.newLine)
            is LazyEdit.Replace -> doc.replaceString(edit.startOffset, edit.endOffset, edit.newLine)
            else -> Unit
        }
    }

    private fun planNamesListEdit(doc: com.intellij.openapi.editor.Document, tableName: String?, shortName: String): ListEdit {
        if (tableName.isNullOrBlank()) return ListEdit.NotFound("未找到 DataNames/ModuleNames 表")

        val lines = doc.text.lines()
        val startRe = Regex("""^\s*${Regex.escape(tableName)}\s*=\s*\{\s*$""")
        val startLine = lines.indexOfFirst { startRe.containsMatchIn(it) }
        if (startLine == -1) return ListEdit.NotFound("未找到 $tableName = {")

        val endLine = findCurlyBlockEndLine(lines, startLine) ?: return ListEdit.NotFound("无法定位 $tableName 的 }")

        val valueRe = Regex("""[\"']${Regex.escape(shortName)}[\"']""", setOf(RegexOption.IGNORE_CASE))
        for (i in (startLine + 1) until endLine) {
            val raw = lines[i]
            if (valueRe.containsMatchIn(raw)) {
                return ListEdit.Skip("已存在（line ${i + 1}）：${raw.trim()}")
            }
        }

        val baseIndent = lines[startLine].takeWhile { it.isWhitespace() }
        val entryIndent = (startLine + 1 until endLine)
            .asSequence()
            .mapNotNull { idx ->
                val t = lines[idx]
                if (t.isBlank()) null else t.takeWhile { it.isWhitespace() }
            }
            .firstOrNull()
            ?: (baseIndent + "    ")

        val entryLine = "${entryIndent}\"${shortName}\","
        return ListEdit.Insert(
            insertOffset = doc.getLineStartOffset(endLine),
            beforeClosingBraceLine = endLine + 1,
            newLine = entryLine + "\n"
        )
    }

    private fun applyListEdit(doc: com.intellij.openapi.editor.Document, edit: ListEdit) {
        when (edit) {
            is ListEdit.Insert -> doc.insertString(edit.insertOffset, edit.newLine)
            else -> Unit
        }
    }

    private fun findCurlyBlockEndLine(lines: List<String>, startLine: Int): Int? {
        var depth = 0
        var started = false
        for (i in startLine until lines.size) {
            val l = lines[i]
            val openCount = l.count { it == '{' }
            val closeCount = l.count { it == '}' }
            if (openCount > 0) started = true
            if (!started) continue
            depth += openCount - closeCount
            if (started && depth <= 0) return i
        }
        return null
    }

    private fun resolveLuaRoot(project: com.intellij.openapi.project.Project): Path? {
        val base = project.basePath
        val candidates = buildList {
            if (!base.isNullOrBlank()) {
                add(Paths.get(base, "Assets", "client-code", "LuaFramework", "Lua"))
                add(Paths.get(base, "LuaFramework", "Lua"))
            }
            add(Paths.get("D:\\gig-u3dclient\\Assets\\client-code\\LuaFramework\\Lua"))
        }

        return candidates.firstOrNull { p ->
            runCatching { Files.isDirectory(p) }.getOrDefault(false)
        }
    }

    private fun computeRequirePath(vf: VirtualFile, luaRoot: Path?): String? {
        val filePath = runCatching { Paths.get(vf.path).toAbsolutePath().normalize() }.getOrNull() ?: return null

        if (luaRoot != null) {
            val rootPath = luaRoot.toAbsolutePath().normalize()
            if (filePath.startsWith(rootPath)) {
                val rel = rootPath.relativize(filePath).toString()
                    .replace('\\', '/')
                    .removeSuffix(".lua")
                if (rel.isNotBlank()) return rel
            }
        }

        val normalized = vf.path.replace('\\', '/')
        val marker = "/LuaFramework/Lua/"
        val idx = normalized.indexOf(marker, ignoreCase = true)
        if (idx >= 0) {
            return normalized.substring(idx + marker.length).removeSuffix(".lua")
        }

        return null
    }

    private fun findGameDataLua(project: com.intellij.openapi.project.Project, luaRoot: Path?): VirtualFile? {
        if (luaRoot != null) {
            val candidate = luaRoot
                .resolve("Common")
                .resolve("GamePlay")
                .resolve("GameData")
                .resolve("GameData.lua")
            if (runCatching { Files.isRegularFile(candidate) }.getOrDefault(false)) {
                return LocalFileSystem.getInstance().findFileByPath(candidate.toString())
            }
        }

        val scope = GlobalSearchScope.projectScope(project)
        val indexed = FilenameIndex.getVirtualFilesByName("GameData.lua", scope)
        if (indexed.isNotEmpty()) {
            val prefer = indexed.firstOrNull { vf ->
                val rp = computeRequirePath(vf, luaRoot)
                rp != null && rp.equals("Common/GamePlay/GameData/GameData", ignoreCase = true)
            }
            return prefer ?: indexed.first()
        }

        return null
    }

    private fun findGameDataInit(project: com.intellij.openapi.project.Project, luaRoot: Path?): VirtualFile? {
        if (luaRoot != null) {
            val candidate = luaRoot.resolve("GameData").resolve("init.lua")
            if (runCatching { Files.isRegularFile(candidate) }.getOrDefault(false)) {
                return LocalFileSystem.getInstance().findFileByPath(candidate.toString())
            }
        }

        val scope = GlobalSearchScope.projectScope(project)
        val indexed = FilenameIndex.getVirtualFilesByName("init.lua", scope)
        if (indexed.isNotEmpty()) {
            val prefer = indexed.firstOrNull { vf -> vf.path.replace('\\', '/').contains("/GameData/", ignoreCase = true) }
            return prefer ?: indexed.first()
        }

        return null
    }
}
