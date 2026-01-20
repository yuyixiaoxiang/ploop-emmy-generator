package com.emmylua.generator

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 在 Lua 文件中选中一个“类型名/模块名”，自动：
 * 1) 在工程/LuaRoot 中查找对应的 `TypeName.lua`
 * 2) 计算 require("xxx/yyy/TypeName") 路径
 * 3) 若当前文件未 require 过，则在文件顶部 require 进来（避免重复）
 * 4) 同时把 require 语句复制到剪贴板
 */
class LuaRequirePathAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        EditorMouseLineTracker.ensureInstalled(project)

        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val document = editor.document

        val rawToken = getSelectedOrWordUnderCaret(editor, document)
        val typeName = normalizeTypeName(rawToken)
        if (typeName == null) {
            Messages.showInfoMessage(project, "请先选中一个类型名（例如 CommonContainer）。", "提示")
            return
        }

        val luaRoot = resolveLuaRoot(project)
        val requirePath = findRequirePathByTypeName(project, luaRoot, typeName)
        if (requirePath == null) {
            val hint = luaRoot?.toString()?.let { "\nLuaRoot: $it" } ?: ""
            Messages.showInfoMessage(project, "未找到 $typeName 的定义文件。$hint", "Get Lua Require Path")
            return
        }

        val requireLine = "require(\"$requirePath\")"

        // 如果解析出来的文件就是当前文件，则无需 require（但仍可复制路径）
        val currentFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val currentRequirePath = currentFile?.let { computeRequirePath(it, luaRoot) }
        if (currentRequirePath != null && currentRequirePath.equals(requirePath, ignoreCase = true)) {
            copyToClipboard(requireLine)
            Messages.showInfoMessage(project, "类型定义就在当前文件：$requirePath（无需 require，已复制到剪贴板）", "Get Lua Require Path")
            return
        }

        // 去重：已经 require 过就不再插入
        if (hasRequire(document.text, requirePath)) {
            copyToClipboard(requireLine)
            Messages.showInfoMessage(project, "已存在：$requireLine（已复制到剪贴板）", "Get Lua Require Path")
            return
        }

        val insertOffset = findRequireInsertOffset(document)
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(insertOffset, "$requireLine\n")
        }

        copyToClipboard(requireLine)
        Messages.showInfoMessage(project, "已添加：$requireLine（已复制到剪贴板）", "Get Lua Require Path")
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)

        // 只在Lua文件中启用
        val isLuaFile = file?.extension?.lowercase() == "lua"
        e.presentation.isEnabledAndVisible = editor != null && isLuaFile
    }

    private fun getSelectedOrWordUnderCaret(editor: Editor, document: Document): String? {
        val selected = editor.selectionModel.selectedText?.trim()
        if (!selected.isNullOrBlank()) return selected

        val text = document.charsSequence
        if (text.isEmpty()) return null

        val offset = editor.caretModel.offset.coerceIn(0, text.length)
        val idx = if (offset == text.length) offset - 1 else offset
        if (idx !in text.indices) return null

        fun isWordChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_' || ch == '.'

        var start = idx
        while (start > 0 && isWordChar(text[start - 1])) start--

        var end = idx
        while (end < text.length && isWordChar(text[end])) end++

        if (start >= end) return null
        return text.subSequence(start, end).toString().trim()
    }

    private fun normalizeTypeName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null

        val trimmed = raw.trim()
            .trim('"', '\'', '`')
            .trim()

        // 只保留第一个合法标识符（例如从 `--require variable:CommonContainer` 中提取 CommonContainer）
        val m = Regex("""[A-Za-z_][A-Za-z0-9_]*""").find(trimmed)
        return m?.value
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

    private fun findRequirePathByTypeName(
        project: com.intellij.openapi.project.Project,
        luaRoot: Path?,
        typeName: String
    ): String? {
        val fileName = "$typeName.lua"

        val candidates = mutableListOf<VirtualFile>()

        // 1) 优先：同名文件 `TypeName.lua`
        run {
            val scope = GlobalSearchScope.projectScope(project)
            val indexed = FilenameIndex.getVirtualFilesByName(fileName, scope)
            candidates.addAll(indexed)

            // 索引找不到时，fallback：在 LuaRoot 下做一次文件系统扫描（慢，但只扫 LuaRoot）
            if (candidates.isEmpty() && luaRoot != null) {
                val fsMatches = findFilesByNameUnderRoot(luaRoot, fileName)
                val lfs = LocalFileSystem.getInstance()
                for (p in fsMatches) {
                    lfs.findFileByPath(p.toString())?.let { candidates.add(it) }
                }
            }
        }

        // 2) 次选：类型定义可能在别的文件里（例如 `ArenaPlayerWrapData` 定义在 `ArenaData.lua`）
        if (candidates.isEmpty() && luaRoot != null) {
            val fsMatches = findLuaFilesDefiningTypeName(luaRoot, typeName)
            val lfs = LocalFileSystem.getInstance()
            for (p in fsMatches) {
                lfs.findFileByPath(p.toString())?.let { candidates.add(it) }
            }
        }

        if (candidates.isEmpty()) return null

        // 计算 requirePath，并尽量优先选在 luaRoot 下的文件
        val requirePaths = candidates.mapNotNull { vf ->
            computeRequirePath(vf, luaRoot)
        }.distinct()

        if (requirePaths.isEmpty()) return null
        if (requirePaths.size == 1) return requirePaths.first()

        // 多个候选时，让用户选择
        // 当前平台版本的 showChooseDialog 返回的是 index（Int），而不是选中的字符串
        val chosenIndex = Messages.showChooseDialog(
            project,
            "找到多个候选文件，请选择 $typeName 的 require 路径：",
            "Get Lua Require Path",
            Messages.getQuestionIcon(),
            requirePaths.toTypedArray(),
            requirePaths.first()
        )

        return if (chosenIndex in requirePaths.indices) requirePaths[chosenIndex] else null
    }

    private fun findFilesByNameUnderRoot(root: Path, fileName: String): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()

        val result = mutableListOf<Path>()
        // 仅做名称匹配（Windows 环境下忽略大小写）
        Files.walk(root).use { stream ->
            stream.filter { p ->
                Files.isRegularFile(p) && p.fileName.toString().equals(fileName, ignoreCase = true)
            }.forEach { result.add(it) }
        }
        return result
    }

    /**
     * 在 LuaRoot 下扫描所有 lua 文件，查找是否包含该类型的定义：
     * - PLoop: class/interface/struct/enum "TypeName" (
     * - EmmyLua: ---@class / ---@enum / ---@alias TypeName
     */
    private fun findLuaFilesDefiningTypeName(root: Path, typeName: String, maxMatches: Int = 20): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()

        // PLoop 常见：class "TypeName" (function(_ENV)
        // 注意：typeName 后面一般跟的是引号 + 空格/括号，不是“单词边界”，所以不能在引号后用 \b
        val ploopTypeRe = Regex(
            """\b(class|interface|struct|enum)\s+[\"']${Regex.escape(typeName)}[\"'](?=\s|\(|$)""",
            setOf(RegexOption.IGNORE_CASE)
        )

        // EmmyLua：---@class / ---@enum / ---@alias
        val annClassRe = Regex(
            """---@class\s+${Regex.escape(typeName)}\b""",
            setOf(RegexOption.IGNORE_CASE)
        )
        val annEnumRe = Regex(
            """---@enum\s+${Regex.escape(typeName)}\b""",
            setOf(RegexOption.IGNORE_CASE)
        )
        val annAliasRe = Regex(
            """---@alias\s+${Regex.escape(typeName)}\b""",
            setOf(RegexOption.IGNORE_CASE)
        )

        val result = mutableListOf<Path>()
        Files.walk(root).use { stream ->
            stream
                .filter { p ->
                    Files.isRegularFile(p) && p.toString().endsWith(".lua", ignoreCase = true)
                }
                .forEach { p ->
                    if (result.size >= maxMatches) return@forEach

                    // 逐行扫描，命中就提前结束
                    runCatching {
                        Files.newBufferedReader(p).useLines { lines ->
                            for (line in lines) {
                                if (
                                    ploopTypeRe.containsMatchIn(line) ||
                                    annClassRe.containsMatchIn(line) ||
                                    annEnumRe.containsMatchIn(line) ||
                                    annAliasRe.containsMatchIn(line)
                                ) {
                                    result.add(p)
                                    break
                                }
                            }
                        }
                    }
                }
        }
        return result
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

    private fun hasRequire(documentText: String, requirePath: String): Boolean {
        val escaped = Regex.escape(requirePath)
        val patterns = listOf(
            Regex("""(?m)^\s*require\s*\(\s*[\"']$escaped[\"']\s*\)\s*$"""),
            Regex("""(?m)^\s*require\s+[\"']$escaped[\"']\s*$""")
        )
        return patterns.any { it.containsMatchIn(documentText) }
    }

    private fun findRequireInsertOffset(document: Document): Int {
        val lines = document.text.lines()
        if (lines.isEmpty()) return 0

        // 默认：插入到文件顶部的“require 区块”之后
        var lastRelatedLine = -1

        fun isRequireLine(t: String): Boolean {
            val s = t.trimStart()
            if (s.startsWith("--require")) return true
            if (s.startsWith("require(")) return true
            // require "xxx" / require 'xxx'
            if (Regex("""^require\s+[\"']""").containsMatchIn(s)) return true
            return false
        }

        for (i in 0..minOf(lines.lastIndex, 300)) {
            val t = lines[i]
            val trimmed = t.trim()

            if (trimmed.isEmpty()) {
                if (lastRelatedLine >= 0) {
                    lastRelatedLine = i
                }
                continue
            }

            if (isRequireLine(t)) {
                lastRelatedLine = i
                continue
            }

            // 文件头注释也算“可插入区域”，把 require 放在头注释之后
            if (lastRelatedLine < 0 && trimmed.startsWith("--")) {
                lastRelatedLine = i
                continue
            }

            break
        }

        val insertLine = (lastRelatedLine + 1).coerceIn(0, document.lineCount)
        return if (insertLine >= document.lineCount) document.textLength else document.getLineStartOffset(insertLine)
    }

    private fun copyToClipboard(text: String) {
        val selection = StringSelection(text)
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(selection, null)
    }
}
