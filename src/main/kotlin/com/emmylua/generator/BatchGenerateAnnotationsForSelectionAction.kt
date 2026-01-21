package com.emmylua.generator

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager

/**
 * 在 Project 视图中：
 * - 选中多个 Lua 文件 / 文件夹
 * - 右键执行：为选中的所有 Lua 文件批量生成/刷新 EmmyLua 注释
 */
class BatchGenerateAnnotationsForSelectionAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val selected = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
            ?: e.getData(CommonDataKeys.VIRTUAL_FILE)?.let { arrayOf(it) }
            ?: return

        val luaFiles = collectLuaFiles(selected)
        if (luaFiles.isEmpty()) {
            Messages.showInfoMessage(project, "未选择任何 .lua 文件。", "提示")
            return
        }

        val fileDocumentManager = FileDocumentManager.getInstance()
        val psiDocumentManager = PsiDocumentManager.getInstance(project)
        val psiManager = PsiManager.getInstance(project)

        val total = AnnotationGenerator.GenerationStats()
        val touchedDocs = LinkedHashSet<com.intellij.openapi.editor.Document>()

        WriteCommandAction.runWriteCommandAction(project, "Generate EmmyLua Annotations", null, Runnable {
            for (vf in luaFiles) {
                if (!vf.isWritable) {
                    total.filesSkipped++
                    continue
                }

                val document = fileDocumentManager.getDocument(vf)
                    ?: psiManager.findFile(vf)?.let { psiDocumentManager.getDocument(it) }

                if (document == null) {
                    total.filesSkipped++
                    continue
                }

                val stats = AnnotationGenerator.generateAnnotationsForFileInWriteContext(project, document, vf)
                stats.filesProcessed = 1
                total.mergeFrom(stats)
                touchedDocs.add(document)
            }

            // 确保 PSI 同步
            psiDocumentManager.commitAllDocuments()
        })

        // 保存修改
        for (doc in touchedDocs) {
            fileDocumentManager.saveDocument(doc)
        }

        val msg = buildString {
            appendLine("已处理 ${total.filesProcessed} 个文件，跳过 ${total.filesSkipped} 个文件。")
            appendLine(total.toSummaryMessage())
        }.trimEnd()

        Messages.showInfoMessage(project, msg, "完成")
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val selected = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
            ?: e.getData(CommonDataKeys.VIRTUAL_FILE)?.let { arrayOf(it) }

        if (selected == null || selected.isEmpty()) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        e.presentation.isEnabledAndVisible = hasAnyLuaOrDir(selected)
    }

    private fun hasAnyLuaOrDir(files: Array<VirtualFile>): Boolean {
        for (vf in files) {
            if (vf.isDirectory) return true
            if (vf.extension?.equals("lua", ignoreCase = true) == true) return true
        }
        return false
    }

    private fun collectLuaFiles(selected: Array<VirtualFile>): List<VirtualFile> {
        val result = LinkedHashSet<VirtualFile>()

        fun accept(vf: VirtualFile) {
            if (!vf.isDirectory && vf.extension?.equals("lua", ignoreCase = true) == true) {
                result.add(vf)
            }
        }

        for (vf in selected) {
            if (vf.isDirectory) {
                VfsUtilCore.iterateChildrenRecursively(
                    vf,
                    null,
                ) { child ->
                    accept(child)
                    true
                }
            } else {
                accept(vf)
            }
        }

        return result.toList()
    }
}
