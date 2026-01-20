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
 * 在以 Module 结尾、且文件路径/requirePath 含 GameModule 的类名上右键：
 * 1) 提取 className 与 requirePath
 * 2) 注册到 Common/GamePlay/GameModule/GameModule.lua 的 local _LAZY_REQUIRE 表（不重复；若 path 已存在但 key 错则修正 key）
 * 3) 同时把 className 去掉 Module 后缀，注册到 GameModule/init.lua 的 ModuleNames 列表（不重复）
 */
class RegisterModuleClassAction : AnAction() {

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
            Messages.showInfoMessage(project, "请在 class 定义行的类名上右键。", "注册当前Module类")
            return
        }

        val className = PloopParser.parseClassNameFromLine(lineText)
        if (className.isNullOrBlank() || !className.endsWith("Module", ignoreCase = true)) {
            Messages.showInfoMessage(project, "当前 class 不以 Module 结尾，无法注册。", "注册当前Module类")
            return
        }

        val currentFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (currentFile == null) {
            Messages.showInfoMessage(project, "未能获取当前文件。", "注册当前Module类")
            return
        }

        val luaRoot = resolveLuaRoot(project)
        val requirePath = computeRequirePath(currentFile, luaRoot)
        if (requirePath.isNullOrBlank()) {
            val hint = luaRoot?.toString()?.let { "\nLuaRoot: $it" } ?: ""
            Messages.showInfoMessage(project, "未能计算当前文件的 require 路径。$hint", "注册当前Module类")
            return
        }

        if (!requirePath.contains("GameModule", ignoreCase = true) && !currentFile.path.contains("GameModule", ignoreCase = true)) {
            Messages.showInfoMessage(project, "当前文件不在 GameModule 路径下：$requirePath", "注册当前Module类")
            return
        }

        val gameModuleFile = findGameModuleLua(project, luaRoot)
        if (gameModuleFile == null) {
            val hint = luaRoot?.resolve("Common")
                ?.resolve("GamePlay")
                ?.resolve("GameModule")
                ?.resolve("GameModule.lua")
                ?.toString()
                ?.let { "\n期望路径: $it" }
                ?: ""
            Messages.showInfoMessage(project, "未找到 GameModule.lua。$hint", "注册当前Module类")
            return
        }

        val gameModuleDoc = FileDocumentManager.getInstance().getDocument(gameModuleFile)
        if (gameModuleDoc == null) {
            Messages.showInfoMessage(project, "无法打开 GameModule.lua 的 Document。", "注册当前Module类")
            return
        }

        val initFile = findGameModuleInit(project, luaRoot)
        if (initFile == null) {
            val hint = luaRoot?.resolve("GameModule")?.resolve("init.lua")?.toString()?.let { "\n期望路径: $it" } ?: ""
            Messages.showInfoMessage(project, "未找到 GameModule/init.lua。$hint", "注册当前Module类")
            return
        }

        val initDoc = FileDocumentManager.getInstance().getDocument(initFile)
        if (initDoc == null) {
            Messages.showInfoMessage(project, "无法打开 GameModule/init.lua 的 Document。", "注册当前Module类")
            return
        }

        val shortName = className.removeSuffix("Module").ifBlank { className }

        var lazyEdit: LazyRequireEdit = LazyRequireEdit.NotFound("not computed")
        var initEdit: InitEditResult = InitEditResult.NotFound("not computed")
        var formattedLazy = false
        var formattedNames = false

        // 一次写入（两个文件）：先格式化表，再注册
        WriteCommandAction.runWriteCommandAction(project) {
            formattedLazy = LuaTableFormatter.formatLazyRequireTable(gameModuleDoc)
            formattedNames = LuaTableFormatter.formatStringListTable(initDoc, "ModuleNames")

            lazyEdit = planLazyRequireEdit(gameModuleDoc, className, requirePath)
            initEdit = if (className.equals("GameModule", ignoreCase = true) || shortName.equals(className, ignoreCase = true)) {
                InitEditResult.Skip("当前 class 为 $className，不需要写入 init.lua 的 ModuleNames")
            } else {
                planModuleNamesEdit(initDoc, shortName)
            }

            applyLazyRequireEdit(gameModuleDoc, lazyEdit)
            applyInitEdit(initDoc, initEdit)
        }
        FileDocumentManager.getInstance().saveDocument(gameModuleDoc)
        FileDocumentManager.getInstance().saveDocument(initDoc)

        val msg = buildString {
            appendLine("Action: RegisterModule")
            appendLine("Class: $className")
            appendLine("Path:  $requirePath")
            appendLine("GameModule.lua: ${gameModuleFile.path}")
            appendLine("init.lua:       ${initFile.path}")
            appendLine()
            appendLine("[0] Format")
            appendLine("GameModule._LAZY_REQUIRE formatted: $formattedLazy")
            appendLine("init.lua ModuleNames formatted:     $formattedNames")
            appendLine()
            appendLine("[1] GameModule._LAZY_REQUIRE")
            appendLine(lazyEdit.describe())
            appendLine()
            appendLine("[2] GameModule/init.lua ModuleNames")
            appendLine(initEdit.describe())
        }.trimEnd()

        LargeInfoDialog.show(project, "注册当前Module类", msg)
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
        if (className.isNullOrBlank() || !className.endsWith("Module", ignoreCase = true)) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        // 仅在路径包含 GameModule 的文件上显示
        e.presentation.isEnabledAndVisible = file.path.contains("GameModule", ignoreCase = true)
    }

    private data class FixLine(
        val line: Int,
        val startOffset: Int,
        val endOffset: Int,
        val oldLine: String,
        val newLine: String
    )

    private sealed interface LazyRequireEdit {
        fun describe(): String

        data class Skip(val reason: String) : LazyRequireEdit {
            override fun describe(): String = "Skip: $reason"
        }

        data class NotFound(val reason: String) : LazyRequireEdit {
            override fun describe(): String = "Fail: $reason"
        }

        data class Insert(
            val insertOffset: Int,
            val beforeClosingBraceLine: Int,
            val newLine: String,
            val fixPrevLine: FixLine? = null
        ) : LazyRequireEdit {
            override fun describe(): String = buildString {
                appendLine("Insert")
                appendLine("  位置：表尾（原 '}' 前，参考行号 $beforeClosingBraceLine）")
                fixPrevLine?.let {
                    appendLine("  修正上一项：line ${it.line + 1}")
                    appendLine("    原：${it.oldLine.trim()}")
                    appendLine("    新：${it.newLine.trim()}")
                }
                appendLine("  写入：${newLine.trim()}")
            }.trimEnd()
        }

        data class Replace(
            val line: Int,
            val startOffset: Int,
            val endOffset: Int,
            val oldLine: String,
            val newLine: String
        ) : LazyRequireEdit {
            override fun describe(): String = buildString {
                appendLine("Replace")
                appendLine("  原因：已存在相同 path，修正 key 为当前 class")
                appendLine("  位置：line ${line + 1}")
                appendLine("  原：${oldLine.trim()}")
                appendLine("  新：${newLine.trim()}")
            }.trimEnd()
        }
    }

    private sealed interface InitEditResult {
        fun describe(): String

        data class Skip(val reason: String) : InitEditResult {
            override fun describe(): String = "Skip: $reason"
        }

        data class NotFound(val reason: String) : InitEditResult {
            override fun describe(): String = "Fail: $reason"
        }

        data class Insert(
            val insertOffset: Int,
            val beforeClosingBraceLine: Int,
            val newLine: String,
            val fixPrevLine: FixLine? = null
        ) : InitEditResult {
            override fun describe(): String = buildString {
                appendLine("Insert")
                appendLine("  位置：ModuleNames 表尾（原 '}' 前，参考行号 $beforeClosingBraceLine）")
                fixPrevLine?.let {
                    appendLine("  修正上一项：line ${it.line + 1}")
                    appendLine("    原：${it.oldLine.trim()}")
                    appendLine("    新：${it.newLine.trim()}")
                }
                appendLine("  写入：${newLine.trim()}")
            }.trimEnd()
        }
    }

    private fun planLazyRequireEdit(doc: com.intellij.openapi.editor.Document, className: String, requirePath: String): LazyRequireEdit {
        val lines = doc.text.lines()
        val startRe = Regex("""^\s*local\s+_LAZY_REQUIRE\s*=\s*\{\s*$""")
        val startLine = lines.indexOfFirst { startRe.containsMatchIn(it) }
        if (startLine == -1) return LazyRequireEdit.NotFound("未找到 local _LAZY_REQUIRE = {")

        val endLine = findCurlyBlockEndLine(lines, startLine) ?: return LazyRequireEdit.NotFound("无法定位 _LAZY_REQUIRE 的 }")

        // key/value 解析：key = "value",
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
                return LazyRequireEdit.Skip("key 已存在（line ${i + 1}）：${raw.trim()}")
            }

            if (value.equals(requirePath, ignoreCase = true)) {
                val comma = if (trailingComma.isBlank()) "," else trailingComma
                val newLine = "${indent}${className} = ${quote}${requirePath}${quote}${comma}${if (comment.isNotBlank()) " $comment" else ""}"
                return LazyRequireEdit.Replace(
                    line = i,
                    startOffset = doc.getLineStartOffset(i),
                    endOffset = doc.getLineEndOffset(i),
                    oldLine = raw,
                    newLine = newLine
                )
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

        val entryLine = "${entryIndent}${className} = \"${requirePath}\","

        // 如果表尾最后一项没有逗号，插入新项会造成 Lua 语法错误：
        //   "Arena"
        //   "Login",
        // 因此这里在 Insert 时顺便把上一项补上逗号。
        val lastEntryLine = findLastListEntryLine(lines, startLine, endLine)
        val fixPrev = lastEntryLine?.let { li ->
            buildCommaFixIfNeeded(doc, lines, li)
        }

        // 选择插入点：尽量插在“最后一个元素”之后（避免插入到 } 之前的空行之后，造成中间出现空行）
        val insertLine = (lastEntryLine?.plus(1) ?: endLine).coerceAtMost(endLine)

        return LazyRequireEdit.Insert(
            insertOffset = doc.getLineStartOffset(insertLine),
            beforeClosingBraceLine = endLine + 1,
            newLine = entryLine + "\n",
            fixPrevLine = fixPrev
        )
    }

    private fun applyLazyRequireEdit(doc: com.intellij.openapi.editor.Document, edit: LazyRequireEdit) {
        when (edit) {
            is LazyRequireEdit.Insert -> {
                // 先插入，再修正上一项逗号：避免“修正上一行”导致后续 insertOffset 偏移，进而把新内容插到同一行
                doc.insertString(edit.insertOffset, edit.newLine)
                edit.fixPrevLine?.let { fix ->
                    doc.replaceString(fix.startOffset, fix.endOffset, fix.newLine)
                }
            }

            is LazyRequireEdit.Replace -> doc.replaceString(edit.startOffset, edit.endOffset, edit.newLine)
            else -> Unit
        }
    }

    private fun planModuleNamesEdit(doc: com.intellij.openapi.editor.Document, moduleShortName: String): InitEditResult {
        val lines = doc.text.lines()
        val startRe = Regex("""^\s*ModuleNames\s*=\s*\{\s*$""")
        val startLine = lines.indexOfFirst { startRe.containsMatchIn(it) }
        if (startLine == -1) return InitEditResult.NotFound("未找到 ModuleNames = {")

        val endLine = findCurlyBlockEndLine(lines, startLine) ?: return InitEditResult.NotFound("无法定位 ModuleNames 的 }")

        val valueRe = Regex("""[\"']${Regex.escape(moduleShortName)}[\"']""", setOf(RegexOption.IGNORE_CASE))
        for (i in (startLine + 1) until endLine) {
            val raw = lines[i]
            if (valueRe.containsMatchIn(raw)) {
                return InitEditResult.Skip("已存在（line ${i + 1}）：${raw.trim()}")
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

        val entryLine = "${entryIndent}\"${moduleShortName}\"," // 统一用双引号 + 逗号

        val lastEntryLine = findLastListEntryLine(lines, startLine, endLine)
        val fixPrev = lastEntryLine?.let { li ->
            buildCommaFixIfNeeded(doc, lines, li)
        }
        val insertLine = (lastEntryLine?.plus(1) ?: endLine).coerceAtMost(endLine)

        return InitEditResult.Insert(
            insertOffset = doc.getLineStartOffset(insertLine),
            beforeClosingBraceLine = endLine + 1,
            newLine = entryLine + "\n",
            fixPrevLine = fixPrev
        )
    }

    private fun applyInitEdit(doc: com.intellij.openapi.editor.Document, edit: InitEditResult) {
        when (edit) {
            is InitEditResult.Insert -> {
                // 同上：先插入，再修正上一项逗号，避免 insertOffset 因为 replace 而偏移
                doc.insertString(edit.insertOffset, edit.newLine)
                edit.fixPrevLine?.let { fix ->
                    doc.replaceString(fix.startOffset, fix.endOffset, fix.newLine)
                }
            }

            else -> Unit
        }
    }

    private fun findLastListEntryLine(lines: List<String>, startLine: Int, endLine: Int): Int? {
        // 允许："Name" 或 "Name", 也允许末尾跟注释
        val entryRe = Regex("""^\s*[\"'][^\"']+[\"']\s*(,|;)?\s*(--.*)?$""")

        for (i in (endLine - 1) downTo (startLine + 1)) {
            val t = lines[i].trim()
            if (t.isEmpty()) continue
            if (t.startsWith("--")) continue
            if (entryRe.containsMatchIn(lines[i])) return i
            // 如果遇到看起来是表项的（例如 key = "value"），也算 entry
            if (lines[i].contains('=') && lines[i].contains('"')) return i
        }
        return null
    }

    private fun buildCommaFixIfNeeded(
        doc: com.intellij.openapi.editor.Document,
        lines: List<String>,
        lineIndex: Int
    ): FixLine? {
        val raw = lines.getOrNull(lineIndex) ?: return null

        // 拆掉行尾注释，判断是否已有分隔符
        val beforeComment = raw.substringBefore("--").trimEnd()
        if (beforeComment.endsWith(",") || beforeComment.endsWith(";")) return null

        // 只对看起来像“表项”的行做修正
        val looksLikeEntry = beforeComment.contains('"') || beforeComment.contains('\'')
        if (!looksLikeEntry) return null

        val commentPart = raw.substringAfter("--", missingDelimiterValue = "")
        val newLine = if (commentPart.isNotBlank()) {
            beforeComment + ", --" + commentPart
        } else {
            beforeComment + ","
        }

        return FixLine(
            line = lineIndex,
            startOffset = doc.getLineStartOffset(lineIndex),
            endOffset = doc.getLineEndOffset(lineIndex),
            oldLine = raw,
            newLine = newLine
        )
    }

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
            if (started && depth <= 0) {
                return i
            }
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

    private fun findGameModuleLua(project: com.intellij.openapi.project.Project, luaRoot: Path?): VirtualFile? {
        // 1) 固定路径优先
        if (luaRoot != null) {
            val candidate = luaRoot
                .resolve("Common")
                .resolve("GamePlay")
                .resolve("GameModule")
                .resolve("GameModule.lua")
            if (runCatching { Files.isRegularFile(candidate) }.getOrDefault(false)) {
                return LocalFileSystem.getInstance().findFileByPath(candidate.toString())
            }
        }

        // 2) 索引按文件名查找
        val scope = GlobalSearchScope.projectScope(project)
        val indexed = FilenameIndex.getVirtualFilesByName("GameModule.lua", scope)
        if (indexed.isNotEmpty()) {
            // 优先选 requirePath=Common/GamePlay/GameModule/GameModule
            val prefer = indexed.firstOrNull { vf ->
                val rp = computeRequirePath(vf, luaRoot)
                rp != null && rp.equals("Common/GamePlay/GameModule/GameModule", ignoreCase = true)
            }
            return prefer ?: indexed.first()
        }

        return null
    }

    private fun findGameModuleInit(project: com.intellij.openapi.project.Project, luaRoot: Path?): VirtualFile? {
        // 1) 固定路径优先
        if (luaRoot != null) {
            val candidate = luaRoot.resolve("GameModule").resolve("init.lua")
            if (runCatching { Files.isRegularFile(candidate) }.getOrDefault(false)) {
                return LocalFileSystem.getInstance().findFileByPath(candidate.toString())
            }
        }

        // 2) 索引查找 init.lua，优先 GameModule 目录
        val scope = GlobalSearchScope.projectScope(project)
        val indexed = FilenameIndex.getVirtualFilesByName("init.lua", scope)
        if (indexed.isNotEmpty()) {
            val prefer = indexed.firstOrNull { vf -> vf.path.replace('\\', '/').contains("/GameModule/", ignoreCase = true) }
            return prefer ?: indexed.first()
        }

        return null
    }
}
