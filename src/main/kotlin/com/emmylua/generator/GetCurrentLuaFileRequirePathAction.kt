package com.emmylua.generator

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 获取当前 Lua 文件的 require 相对路径。
 * 右键在文件任意位置都可使用。
 */
class GetCurrentLuaFileRequirePathAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (file == null || file.extension?.lowercase() != "lua") return

        val luaRoot = resolveLuaRoot(project)
        val requirePath = computeRequirePath(file, luaRoot)

        val msg = buildString {
            appendLine("File: ${file.path}")
            appendLine("LuaRoot: ${luaRoot?.toString() ?: "(not found)"}")
            appendLine("RequirePath: ${requirePath ?: "(cannot resolve)"}")
        }.trimEnd()

        // requirePath 能解析就用大弹窗显示，方便复制；否则用普通提示
        if (requirePath != null) {
            LargeInfoDialog.show(project, "获取本Lua的RequirePath", msg)
        } else {
            Messages.showInfoMessage(project, msg, "获取本Lua的RequirePath")
        }
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val isLua = file?.extension?.lowercase() == "lua"
        e.presentation.isEnabledAndVisible = editor != null && isLua
    }

    private fun resolveLuaRoot(project: Project): Path? {
        val base = project.basePath
        val candidates = buildList {
            if (!base.isNullOrBlank()) {
                add(Paths.get(base, "Assets", "client-code", "LuaFramework", "Lua"))
                add(Paths.get(base, "LuaFramework", "Lua"))
            }
            // fallback（常见路径）
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
}
