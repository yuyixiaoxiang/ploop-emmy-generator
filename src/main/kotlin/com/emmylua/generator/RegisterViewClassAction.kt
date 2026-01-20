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
 * 在继承自 ViewBase 的界面类上右键：
 * 1) 提取当前 class 名与该文件的 require 相对路径
 * 2) 找到 GameView.lua 的 local _LAZY_REQUIRE 表
 * 3) 将 className -> requirePath 注册到表中（不重复）
 */
class RegisterViewClassAction : AnAction() {

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
            Messages.showInfoMessage(project, "请在 class 定义行的类名上右键。", "注册当前界面类")
            return
        }

        val className = PloopParser.parseClassNameFromLine(lineText)
        if (className.isNullOrBlank()) {
            Messages.showInfoMessage(project, "未能解析 class 名称。", "注册当前界面类")
            return
        }

        val classInfo = PloopParser.parseClassInfo(document.text, line)
        if (classInfo?.parentClass?.equals("ViewBase", ignoreCase = true) != true) {
            Messages.showInfoMessage(project, "当前 class 未继承 ViewBase，无法注册。", "注册当前界面类")
            return
        }

        val currentFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (currentFile == null) {
            Messages.showInfoMessage(project, "未能获取当前文件。", "注册当前界面类")
            return
        }

        val luaRoot = resolveLuaRoot(project)
        val requirePath = computeRequirePath(currentFile, luaRoot)
        if (requirePath.isNullOrBlank()) {
            val hint = luaRoot?.toString()?.let { "\nLuaRoot: $it" } ?: ""
            Messages.showInfoMessage(project, "未能计算当前文件的 require 路径。$hint", "注册当前界面类")
            return
        }

        // 找到 GameView.lua
        val gameViewFile = findGameViewLua(project, luaRoot)
        if (gameViewFile == null) {
            val hint = luaRoot?.resolve("GameView.lua")?.toString()?.let { "\n期望路径: $it" } ?: ""
            Messages.showInfoMessage(project, "未找到 GameView.lua。$hint", "注册当前界面类")
            return
        }

        val gameViewDoc = FileDocumentManager.getInstance().getDocument(gameViewFile)
        if (gameViewDoc == null) {
            Messages.showInfoMessage(project, "无法打开 GameView.lua 的 Document。", "注册当前界面类")
            return
        }

        val gameViewPath = gameViewFile.path

        var formattedLazy = false
        var result: RegisterResult = RegisterResult.NotFoundLazyTable

        WriteCommandAction.runWriteCommandAction(project) {
            formattedLazy = LuaTableFormatter.formatLazyRequireTable(gameViewDoc)
            result = registerIntoLazyRequire(gameViewDoc, className, requirePath)

            // 这里不直接写文件，交由后面分支处理（Insert/Replace）
            // format 已经在上面完成
        }
        // format 可能已经修改了文档，即使后续跳过注册，也要保存
        FileDocumentManager.getInstance().saveDocument(gameViewDoc)

        fun header(action: String) = buildString {
            appendLine("Action: $action")
            appendLine("Class: $className")
            appendLine("Path:  $requirePath")
            appendLine("GameView: $gameViewPath")
            appendLine("LazyRequire formatted: $formattedLazy")
        }.trimEnd()

        // result 是在 WriteCommandAction 的闭包里赋值的 var，Kotlin 无法对它做 smart-cast。
        // 这里复制一份 val 快照，再用 when 做类型分发。
        val resultSnapshot = result

        when (val r = resultSnapshot) {
            is RegisterResult.AlreadyExists -> {
                val msg = header("Skip") + "\n\n" + r.details
                LargeInfoDialog.show(project, "注册当前界面类", msg)
            }

            is RegisterResult.NotFoundLazyTable -> {
                val msg = header("Fail") + "\n\n" + "未找到 GameView.lua 中的 local _LAZY_REQUIRE = { ... } 表。"
                LargeInfoDialog.show(project, "注册当前界面类", msg)
            }

            is RegisterResult.ToInsert -> {
                WriteCommandAction.runWriteCommandAction(project) {
                    gameViewDoc.insertString(r.insertOffset, r.textToInsert)
                }
                FileDocumentManager.getInstance().saveDocument(gameViewDoc)

                val msg = header("Insert") + "\n\n" + buildString {
                    appendLine("插入位置：_LAZY_REQUIRE 表尾（原 '}' 前，原始行号约 ${r.beforeClosingBraceLine}）")
                    appendLine("写入内容：${r.preview}")
                }.trimEnd()
                LargeInfoDialog.show(project, "注册当前界面类", msg)
            }

            is RegisterResult.ToReplaceLine -> {
                WriteCommandAction.runWriteCommandAction(project) {
                    gameViewDoc.replaceString(r.startOffset, r.endOffset, r.newLine)
                }
                FileDocumentManager.getInstance().saveDocument(gameViewDoc)

                val msg = header("Replace") + "\n\n" + buildString {
                    appendLine("原因：表中已存在相同 path，需要把 key 修正为当前 class。")
                    appendLine("位置：line ${r.line + 1}")
                    appendLine("原内容：${r.oldLine.trim()}")
                    appendLine("新内容：${r.newLine.trim()}")
                }.trimEnd()
                LargeInfoDialog.show(project, "注册当前界面类", msg)
            }
        }
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

        val info = PloopParser.parseClassInfo(document.text, line)
        e.presentation.isEnabledAndVisible = info?.parentClass?.equals("ViewBase", ignoreCase = true) == true
    }

    private sealed interface RegisterResult {
        data class AlreadyExists(val details: String) : RegisterResult
        data object NotFoundLazyTable : RegisterResult
        data class ToInsert(
            val insertOffset: Int,
            val textToInsert: String,
            val beforeClosingBraceLine: Int,
            val preview: String
        ) : RegisterResult

        data class ToReplaceLine(
            val line: Int,
            val startOffset: Int,
            val endOffset: Int,
            val oldLine: String,
            val newLine: String
        ) : RegisterResult
    }

    private fun registerIntoLazyRequire(doc: com.intellij.openapi.editor.Document, className: String, requirePath: String): RegisterResult {
        val lines = doc.text.lines()
        val startRe = Regex("""^\s*local\s+_LAZY_REQUIRE\s*=\s*\{\s*$""")

        val startLine = lines.indexOfFirst { startRe.containsMatchIn(it) }
        if (startLine == -1) return RegisterResult.NotFoundLazyTable

        val endLine = findCurlyBlockEndLine(lines, startLine) ?: return RegisterResult.NotFoundLazyTable

        // 解析表项：key = "value",
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
                val details = buildString {
                    appendLine("原因：key 已存在，跳过注册。")
                    appendLine("位置：line ${i + 1}")
                    appendLine("已存在：${raw.trim()}")
                }.trimEnd()
                return RegisterResult.AlreadyExists(details)
            }

            // 如果 path 已存在但 key 不一致：把该行 key 修正为当前 className（避免出现 panel1 这种错 key）
            if (value.equals(requirePath, ignoreCase = true)) {
                val comma = if (trailingComma.isBlank()) "," else trailingComma
                val newLine = "${indent}${className} = ${quote}${requirePath}${quote}${comma}${if (comment.isNotBlank()) " $comment" else ""}"

                val startOffset = doc.getLineStartOffset(i)
                val endOffset = doc.getLineEndOffset(i)
                return RegisterResult.ToReplaceLine(
                    line = i,
                    startOffset = startOffset,
                    endOffset = endOffset,
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

        // 插入到 endLine 对应的 `}` 之前（用 Document 的行 offset，避免 CRLF 造成偏移）
        val insertOffset = doc.getLineStartOffset(endLine)

        // endLine 是原始 '}' 行；插入会把 '}' 下推一行，所以这里返回一个“插入前的参考行号”
        return RegisterResult.ToInsert(
            insertOffset = insertOffset,
            textToInsert = entryLine + "\n",
            beforeClosingBraceLine = endLine + 1,
            preview = entryLine.trim()
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
            // fallback（用户环境常见路径）
            add(Paths.get("D:\\gig-u3dclient\\Assets\\client-code\\LuaFramework\\Lua"))
        }

        return candidates.firstOrNull { p ->
            runCatching { Files.isDirectory(p) }.getOrDefault(false)
        }
    }

    private fun computeRequirePath(vf: VirtualFile, luaRoot: Path?): String? {
        val filePath = runCatching { Paths.get(vf.path).toAbsolutePath().normalize() }.getOrNull() ?: return null

        // 优先：如果有 luaRoot 并且文件在 root 内，用 root 相对路径
        if (luaRoot != null) {
            val rootPath = luaRoot.toAbsolutePath().normalize()
            if (filePath.startsWith(rootPath)) {
                val rel = rootPath.relativize(filePath).toString()
                    .replace('\\', '/')
                    .removeSuffix(".lua")
                if (rel.isNotBlank()) return rel
            }
        }

        // fallback：从路径中截取 /LuaFramework/Lua/ 之后的部分
        val normalized = vf.path.replace('\\', '/')
        val marker = "/LuaFramework/Lua/"
        val idx = normalized.indexOf(marker, ignoreCase = true)
        if (idx >= 0) {
            return normalized.substring(idx + marker.length).removeSuffix(".lua")
        }

        return null
    }

    private fun findGameViewLua(project: com.intellij.openapi.project.Project, luaRoot: Path?): VirtualFile? {
        // 1) 直接用 LuaRoot/GameView.lua
        if (luaRoot != null) {
            val candidate = luaRoot.resolve("GameView.lua")
            if (runCatching { Files.isRegularFile(candidate) }.getOrDefault(false)) {
                return LocalFileSystem.getInstance().findFileByPath(candidate.toString())
            }
        }

        // 2) 用索引按文件名查找
        val scope = GlobalSearchScope.projectScope(project)
        val indexed = FilenameIndex.getVirtualFilesByName("GameView.lua", scope)
        if (indexed.isNotEmpty()) {
            // 优先选择 requirePath=GameView 的那个
            val prefer = indexed.firstOrNull { vf ->
                val rp = computeRequirePath(vf, luaRoot)
                rp != null && rp.equals("GameView", ignoreCase = true)
            }
            return prefer ?: indexed.first()
        }

        return null
    }
}
