package com.emmylua.generator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PreserveTypesTest {

    @Test
    fun `method refresh preserves manual param and return types`() {
        val doc = """
            class "ArenaModule" (function(_ENV)

            ---@class ArenaModule
            ---@field public OnShow fun(self:OldClass,params:MyParams):MyRet 收到心跳回应
            ---@param self OldClass
            ---@param params MyParams
            ---@return MyRet
            function OnShow(self, params)
                return 1
            end

            end)
        """.trimIndent()

        val lines = doc.lines()
        val methodLine = lines.indexOfFirst { it.contains("function OnShow") }
        val existing = lines.subList(2, 7).joinToString("\n")

        val annotation = AnnotationGenerator.getAnnotationForMethod(
            currentLineText = lines[methodLine],
            currentLine = methodLine,
            documentText = doc,
            existingAnnotationText = existing
        )

        val expected = """
            ---@class ArenaModule
            ---@field public OnShow fun(self:ArenaModule,params:MyParams):MyRet 收到心跳回应
            ---@param self ArenaModule
            ---@param params MyParams
        """.trimIndent()

        assertEquals(expected, annotation)
    }

    @Test
    fun `property refresh preserves manual field types`() {
        val doc = """
            class "ArenaModule" (function(_ENV)

            ---@class ArenaModule _auto_annotation_
            ---@field public FreeTimes int _auto_annotation_
            ---@field private __freeTimes int _auto_annotation_
            property "FreeTimes" { field = "__freeTimes", type = System.Integer, default = 0 }

            end)
        """.trimIndent()

        val lines = doc.lines()
        val propLine = lines.indexOfFirst { it.contains("property \"FreeTimes\"") }
        val existing = lines.subList(2, 5).joinToString("\n")

        val annotation = AnnotationGenerator.getAnnotationForProperty(
            propertyStartLine = propLine,
            documentText = doc,
            existingAnnotationText = existing
        )

        val expected = """
            ---@class ArenaModule _auto_annotation_
            ---@field public FreeTimes int _auto_annotation_
            ---@field private __freeTimes int _auto_annotation_
        """.trimIndent()

        assertEquals(expected, annotation)
    }
}
