package com.emmylua.generator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class PropertyAnnotationTest {

    @Test
    fun `generate annotation for simple property`() {
        val lua = """
            class "ArenaModule" (function(_ENV)
            property "Real" { type = Number, default = 0 }
            end)
        """.trimIndent()

        val propLine = 1
        val annotation = AnnotationGenerator.getAnnotationForProperty(propLine, lua)

        val expected = """
            ---@class ArenaModule _auto_annotation_
            ---@field public Real number _auto_annotation_
        """.trimIndent()

        assertEquals(expected, annotation)
    }

    @Test
    fun `generate annotation for property with backing field`() {
        val lua = """
            class "ArenaModule" (function(_ENV)
            --- comment
            property "FreeTimes" { field = "__freeTimes", type = System.Integer, default = 0, set = false, get = function(self)
                return self.__freeTimes
            end }
            end)
        """.trimIndent()

        val propLine = 2
        val annotation = AnnotationGenerator.getAnnotationForProperty(propLine, lua)

        val expected = """
            ---@class ArenaModule _auto_annotation_
            ---@field public FreeTimes number _auto_annotation_
            ---@field private __freeTimes number _auto_annotation_
        """.trimIndent()

        assertEquals(expected, annotation)
    }

    @Test
    fun `property type System_Table becomes table`() {
        val lua = """
            class "ArenaModule" (function(_ENV)
            property "Views" { field = "__views", type = System.Table, default = {}, set = false }
            end)
        """.trimIndent()

        val annotation = AnnotationGenerator.getAnnotationForProperty(propertyStartLine = 1, documentText = lua)
        val expected = """
            ---@class ArenaModule _auto_annotation_
            ---@field public Views table _auto_annotation_
            ---@field private __views table _auto_annotation_
        """.trimIndent()

        assertEquals(expected, annotation)
    }

    @Test
    fun `find property definition when caret inside block`() {
        val lua = """
            property "FreeTimes" { field = "__freeTimes", type = System.Integer, default = 0, set = false, get = function(self)
                return self.__freeTimes
            end }
        """.trimIndent()

        val (line, _) = PloopParser.findPropertyDefinitionAtOrAbove(lua, currentLine = 1) ?: error("property not found")
        assertEquals(0, line)

        val info = PloopParser.parsePropertyInfo(lua, line)
        assertNotNull(info)
        assertEquals("FreeTimes", info!!.propertyName)
        assertEquals("number", info.luaType)
        assertEquals("__freeTimes", info.fieldName)
        assertEquals(false, info.hasSetter)
    }

    @Test
    fun `find property definition when caret deep inside long block`() {
        val body = (1..300).joinToString("\n") { "-- line $it" }
        val lua = """
            property "ArenaNum" { field = "__arenaNum", type = System.Integer, default = 0, set = false, get = function(self)
            $body
                return self.__arenaNum
            end }
        """.trimIndent()

        // caret near the end of the long body
        val caretLine = lua.lines().lastIndex - 2
        val (line, _) = PloopParser.findPropertyDefinitionAtOrAbove(lua, currentLine = caretLine) ?: error("property not found")
        assertEquals(0, line)
    }
}
