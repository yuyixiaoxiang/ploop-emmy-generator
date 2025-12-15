package com.emmylua.generator

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.ui.Messages

class EmmyLuaAnnotationAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val document = editor.document
        val caretModel = editor.caretModel
        
        val currentLine = caretModel.logicalPosition.line
        val lineStartOffset = document.getLineStartOffset(currentLine)
        val lineEndOffset = document.getLineEndOffset(currentLine)
        val currentLineText = document.getText(com.intellij.openapi.util.TextRange(lineStartOffset, lineEndOffset))
        
        // 检查是否是 class 定义行
        if (PloopParser.isClassDefinitionLine(currentLineText)) {
            generateAnnotationsForClass(e, document, currentLine, currentLineText)
            return
        }
        
        // 解析当前行的方法信息
        val methodInfo = PloopParser.parseMethodAtLine(currentLineText, currentLine)
        
        if (methodInfo == null) {
            Messages.showWarningDialog(
                project,
                "当前行不是有效的Lua方法或class定义。\n请将光标放在 'function MethodName(...)' 或 'class \"Name\"' 行上。",
                "无法生成注释"
            )
            return
        }
        
        // 解析class信息
        val documentText = document.text
        val classInfo = PloopParser.parseClassInfo(documentText, currentLine)
        
        // 分析返回类型
        val returnType = PloopParser.analyzeReturnType(documentText, currentLine)
        
        // 获取缩进
        val indent = currentLineText.takeWhile { it.isWhitespace() }
        
        // 生成新注释
        val annotation = AnnotationGenerator.generate(methodInfo, classInfo, returnType, indent)
        
        // 检查是否已存在注释，如果存在则删除旧注释
        val existingRange = findExistingAnnotationRange(document, currentLine)
        
        WriteCommandAction.runWriteCommandAction(project) {
            if (existingRange != null) {
                // 删除旧注释，插入新注释
                document.replaceString(existingRange.first, existingRange.second, annotation + "\n")
            } else {
                // 直接插入新注释
                document.insertString(lineStartOffset, annotation + "\n")
            }
        }
    }
    
    /**
     * 为 class 内的所有方法生成/刷新注释
     */
    private fun generateAnnotationsForClass(e: AnActionEvent, document: Document, classLine: Int, classLineText: String) {
        val project = e.project ?: return
        
        // 解析类名
        val className = PloopParser.parseClassNameFromLine(classLineText) ?: return
        val classInfo = LuaClassInfo(className, null)
        
        // 找到所有方法
        val methods = PloopParser.findAllMethodsInClass(document.text, classLine)
        
        if (methods.isEmpty()) {
            Messages.showInfoMessage(project, "未找到任何方法。", "提示")
            return
        }
        
        var generatedCount = 0
        var refreshedCount = 0
        
        // 从后往前处理（避免行号偏移问题）
        WriteCommandAction.runWriteCommandAction(project) {
            for (method in methods.sortedByDescending { it.lineNumber }) {
                // 每次循环重新获取当前文档状态，因为前面的操作可能改变了行号
                val currentLines = document.text.lines()
                
                // 找到该方法当前的实际行号（通过方法名匹配）
                val actualMethodLine = findMethodLine(currentLines, method.methodName, method.lineNumber)
                if (actualMethodLine == -1) continue
                
                val methodLineText = currentLines.getOrNull(actualMethodLine) ?: continue
                val indent = methodLineText.takeWhile { it.isWhitespace() }
                
                val returnType = PloopParser.analyzeReturnType(document.text, actualMethodLine)
                val annotation = AnnotationGenerator.generate(method, classInfo, returnType, indent)
                
                // 检查是否已有注释
                val existingRange = findExistingAnnotationRange(document, actualMethodLine)
                
                if (existingRange != null) {
                    // 刷新已有注释
                    document.replaceString(existingRange.first, existingRange.second, annotation + "\n")
                    refreshedCount++
                } else {
                    // 插入新注释
                    val insertOffset = document.getLineStartOffset(actualMethodLine)
                    document.insertString(insertOffset, annotation + "\n")
                    generatedCount++
                }
            }
        }
        
        val msg = when {
            generatedCount > 0 && refreshedCount > 0 -> "新增 $generatedCount 个，刷新 $refreshedCount 个方法注释。"
            generatedCount > 0 -> "已为 $generatedCount 个方法生成注释。"
            refreshedCount > 0 -> "已刷新 $refreshedCount 个方法注释。"
            else -> "没有方法需要处理。"
        }
        Messages.showInfoMessage(project, msg, "完成")
    }
    
    /**
     * 根据方法名找到实际行号
     */
    private fun findMethodLine(lines: List<String>, methodName: String, hintLine: Int): Int {
        // 先检查提示行
        if (hintLine < lines.size && lines[hintLine].contains("function") && lines[hintLine].contains(methodName)) {
            return hintLine
        }
        // 在提示行附近搜索
        for (offset in 0..20) {
            val checkLine = hintLine + offset
            if (checkLine < lines.size && lines[checkLine].contains("function") && lines[checkLine].contains(methodName)) {
                return checkLine
            }
        }
        return -1
    }
    
    /**
     * 查找已存在的注释范围（返回起始和结束offset）
     */
    private fun findExistingAnnotationRange(document: Document, methodLine: Int): Pair<Int, Int>? {
        val lines = document.text.lines()
        
        var annotationEndLine = -1
        var annotationStartLine = -1
        
        // 从方法行向上查找注释块
        for (i in (methodLine - 1) downTo maxOf(0, methodLine - 10)) {
            val line = lines[i].trim()
            
            // 如果是注释行
            if (line.startsWith("---@") || line.startsWith("---#")) {
                if (annotationEndLine == -1) {
                    annotationEndLine = i
                }
                annotationStartLine = i
            }
            // 如果是普通注释 (-- 但不是 ---@)
            else if (line.startsWith("--") && !line.startsWith("---")) {
                // 跳过普通注释，继续向上找
                continue
            }
            // 如果遇到空行，继续向上查找
            else if (line.isEmpty()) {
                // 如果已经找到了注释块，遇到空行就停止
                if (annotationEndLine != -1) {
                    break
                }
                // 否则继续向上
                continue
            }
            // 遇到非注释代码行，停止
            else {
                break
            }
        }
        
        // 如果没找到注释
        if (annotationStartLine == -1 || annotationEndLine == -1) {
            return null
        }
        
        // 检查是否包含 @class 或 @field（确认是我们的注释）
        var hasOurAnnotation = false
        for (i in annotationStartLine..annotationEndLine) {
            val line = lines[i]
            if (line.contains("@class") || line.contains("@field")) {
                hasOurAnnotation = true
                break
            }
        }
        
        if (!hasOurAnnotation) {
            return null
        }
        
        val startOffset = document.getLineStartOffset(annotationStartLine)
        val endOffset = document.getLineEndOffset(annotationEndLine) + 1 // +1 包含换行符
        
        return Pair(startOffset, endOffset)
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        
        // 只在Lua文件中启用
        val isLuaFile = file?.extension?.lowercase() == "lua"
        e.presentation.isEnabledAndVisible = editor != null && isLuaFile
    }
}
