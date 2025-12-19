package com.emmylua.generator

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ex.ClipboardUtil
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.StringSelection

/**
 * 获取指定lua文件的require 路径
 */
class LuaRequirePathAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val document = editor.document
        val caretModel = editor.caretModel
        
        val currentLine = caretModel.logicalPosition.line
        val lineStartOffset = document.getLineStartOffset(currentLine)
        val lineEndOffset = document.getLineEndOffset(currentLine)
        val currentLineText = document.getText(TextRange(lineStartOffset, lineEndOffset))
        
        print(currentLineText);
//        val clipboard: Clipboard = Toolkit.getDefaultToolkit().systemClipboard
//        clipboard.setContents(currentLineText);
        val selection = StringSelection(currentLineText);
        val clipboard: Clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(selection, null)
    }
    


    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        
        // 只在Lua文件中启用
        val isLuaFile = file?.extension?.lowercase() == "lua"
        e.presentation.isEnabledAndVisible = editor != null && isLuaFile
    }
}
