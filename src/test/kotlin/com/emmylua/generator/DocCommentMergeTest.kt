package com.emmylua.generator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DocCommentMergeTest {

    @Test
    fun `property doc comment becomes field description and removes auto marker`() {
        val lua = """
            class "ArenaModule" (function(_ENV)
            property "MaxShowMenuCnt" { type = System.Integer, default = 0 }
            end)
        """.trimIndent()

        val annotation = AnnotationGenerator.getAnnotationForProperty(
            propertyStartLine = 1,
            documentText = lua,
            docComment = "最大显示头项数量"
        )

        val expected = """
            ---@class ArenaModule _auto_annotation_
            ---@field public MaxShowMenuCnt number 最大显示头项数量
        """.trimIndent()

        assertEquals(expected, annotation)
    }

    @Test
    fun `method doc comment becomes field description`() {
        val lua = """
            class "ArenaModule" (function(_ENV)
            function OnShow(self, params)
            end
            end)
        """.trimIndent()

        val lines = lua.lines()
        val methodLine = lines.indexOfFirst { it.contains("function OnShow") }

        val annotation = AnnotationGenerator.getAnnotationForMethod(
            currentLineText = lines[methodLine],
            currentLine = methodLine,
            documentText = lua,
            docComment = "显示界面"
        )

        val expected = """
            ---@class ArenaModule
            ---@field public OnShow fun(self:ArenaModule,params:table) 显示界面
            ---@param self ArenaModule
            ---@param params table
        """.trimIndent()

        assertEquals(expected, annotation)
    }
}
