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
            AnnotationGenerator.generateAnnotationsForClass(e, document, currentLine, currentLineText)
            return
        }
        

        
        // 生成新注释
         AnnotationGenerator.generateAnnotationForMethod(e, document,currentLine, currentLineText)
        

    }
    


    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        
        // 只在Lua文件中启用
        val isLuaFile = file?.extension?.lowercase() == "lua"
        e.presentation.isEnabledAndVisible = editor != null && isLuaFile
    }
}
