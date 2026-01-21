package com.emmylua.generator

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages

class EmmyLuaAnnotationAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        EditorMouseLineTracker.ensureInstalled(project)
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val document = editor.document
        val caretModel = editor.caretModel
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        
        // 右键菜单选择动作时，AnActionEvent.inputEvent 通常是“点击菜单项”的事件，
        // 坐标不是 editor 的，所以不能用来算行号。
        // 我们通过 EditorMouseLineTracker 记录“最后一次鼠标所在行”，这里直接读取。
        val trackedMouseLine = editor.getUserData(EditorMouseLineTracker.LAST_MOUSE_LINE_KEY)
        var currentLine = (trackedMouseLine ?: caretModel.logicalPosition.line)
            .coerceIn(0, document.lineCount - 1)

        var lineStartOffset = document.getLineStartOffset(currentLine)
        var lineEndOffset = document.getLineEndOffset(currentLine)
        var currentLineText = document.getText(com.intellij.openapi.util.TextRange(lineStartOffset, lineEndOffset))

        // 兼容：有时用户右键点在注释上（比如 @class/@field），caret 不一定在目标语句行。
        // 如果当前行是注释/空行，则向下找最近的一行代码（最多 50 行）。
        run {
            val trimmed = currentLineText.trimStart()
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                val lines = document.text.lines()
                for (i in (currentLine + 1)..minOf(currentLine + 50, lines.lastIndex)) {
                    val t = lines[i].trimStart()
                    if (t.isEmpty()) continue
                    if (t.startsWith("--")) continue
                    currentLine = i
                    lineStartOffset = document.getLineStartOffset(currentLine)
                    lineEndOffset = document.getLineEndOffset(currentLine)
                    currentLineText = document.getText(com.intellij.openapi.util.TextRange(lineStartOffset, lineEndOffset))
                    break
                }
            }
        }

        // property 注释（允许在 property 块内任意行右键；优先级高于 enum/class）
        val propertyLine = when {
            PloopParser.isPropertyDefinitionLine(currentLineText) -> currentLine
            else -> PloopParser.findPropertyDefinitionAtOrAbove(document.text, currentLine)?.first
        }
        if (propertyLine != null) {
            AnnotationGenerator.generateAnnotationForProperty(e, document, propertyLine)
            return
        }

        // enum 注释（允许在枚举块内任意行右键）
        PloopParser.findEnumDefinitionAtOrAbove(document.text, currentLine)?.let { (enumLine, _) ->
            AnnotationGenerator.generateAnnotationForEnum(e, document, enumLine)
            return
        }

        // 检查是否是 class 定义行
        if (PloopParser.isClassDefinitionLine(currentLineText)) {
            AnnotationGenerator.generateAnnotationsForClass(e, document, currentLine, currentLineText, virtualFile)
            return
        }

        // 方法注释
        if (PloopParser.parseMethodAtLine(currentLineText, currentLine) != null) {
            AnnotationGenerator.generateAnnotationForMethod(e, document, currentLine, currentLineText)
            return
        }

        // 诊断信息：帮助排查右键菜单取到的行号是否正确
        val caretLine = caretModel.logicalPosition.line
        val caretText = runCatching {
            val s = document.getLineStartOffset(caretLine.coerceIn(0, document.lineCount - 1))
            val ed = document.getLineEndOffset(caretLine.coerceIn(0, document.lineCount - 1))
            document.getText(com.intellij.openapi.util.TextRange(s, ed))
        }.getOrNull()

        val tracked = trackedMouseLine
        val chosenLine = currentLine
        val chosenText = currentLineText

        val msg = buildString {
            appendLine("请将光标放在 enum / class / property / function 定义行上。")
            appendLine()
            appendLine("File: ${virtualFile?.path ?: "(unknown)"}")
            appendLine("document.lineCount=${document.lineCount}")
            appendLine("trackedMouseLine=${tracked?.let { it + 1 } ?: "(null)"}")
            appendLine("caretLine=${caretLine + 1}")
            appendLine("chosenLine=${chosenLine + 1}")
            appendLine()
            appendLine("chosenLineText: ${chosenText.trimEnd()}")
            if (caretText != null) {
                appendLine("caretLineText: ${caretText.trimEnd()}")
            }
        }.trimEnd()

        Messages.showInfoMessage(project, msg, "提示")
    }
    


    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        
        // 只在Lua文件中启用
        val isLuaFile = file?.extension?.lowercase() == "lua"
        e.presentation.isEnabledAndVisible = editor != null && isLuaFile
    }
}
