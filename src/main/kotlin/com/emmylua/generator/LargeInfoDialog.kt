package com.emmylua.generator

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.Dimension
import java.awt.Font
import javax.swing.JComponent

/**
 * 比 Messages.showInfoMessage 更大的信息弹窗（可滚动/可调整大小）。
 */
class LargeInfoDialog(
    project: Project?,
    title: String,
    message: String,
    preferredDialogSize: Dimension = Dimension(860, 520)
) : DialogWrapper(project) {

    private val textArea = JBTextArea(message).apply {
        isEditable = false
        lineWrap = false
        wrapStyleWord = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        caretPosition = 0
    }

    private val panel: JComponent = JBScrollPane(textArea).apply {
        this.preferredSize = preferredDialogSize
        this.minimumSize = Dimension(600, 360)
    }

    init {
        setTitle(title)
        setResizable(true)
        init()
    }

    override fun createCenterPanel(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent = textArea

    companion object {
        fun show(project: Project?, title: String, message: String) {
            LargeInfoDialog(project, title, message).show()
        }
    }
}
