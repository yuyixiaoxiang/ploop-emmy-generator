package com.emmylua.generator

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.util.Key

/**
 * Rider/IDEA 的右键菜单默认不会移动光标(caret)。
 * 这个监听器把“最后一次鼠标所在行”保存到 editor userData，供右键 Action 使用。
 */
class EditorMouseLineTracker : StartupActivity {

    override fun runActivity(project: Project) {
        ensureInstalled(project)
    }

    companion object {
        val LAST_MOUSE_LINE_KEY: Key<Int> = Key.create("emmylua.generator.lastMouseLine")
        private val INSTALLED_KEY: Key<Boolean> = Key.create("emmylua.generator.mouseLineTrackerInstalled")

        fun ensureInstalled(project: Project) {
            if (project.getUserData(INSTALLED_KEY) == true) return
            project.putUserData(INSTALLED_KEY, true)

            val multicaster = EditorFactory.getInstance().eventMulticaster

            fun update(e: EditorMouseEvent) {
                // 只记录编辑区（避免 gutter / 退出 editor 时把 line 覆盖成无意义的值）
                if (e.area != EditorMouseEventArea.EDITING_AREA) return

                val editor = e.editor
                val point = e.mouseEvent.point
                val logical = editor.xyToLogicalPosition(point)
                val line = logical.line
                if (line >= 0) {
                    editor.putUserData(LAST_MOUSE_LINE_KEY, line)
                }
            }

            multicaster.addEditorMouseListener(object : EditorMouseListener {
                override fun mousePressed(event: EditorMouseEvent) = update(event)
            }, project)

            multicaster.addEditorMouseMotionListener(object : EditorMouseMotionListener {
                override fun mouseMoved(event: EditorMouseEvent) = update(event)
                override fun mouseDragged(event: EditorMouseEvent) = update(event)
            }, project)
        }
    }
}
