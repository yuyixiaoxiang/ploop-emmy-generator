package com.emmylua.generator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class EnumAnnotationTest {

    @Test
    fun `generate EmmyLua annotation for enum`() {
        val lua = """
            enum "EArenaBattleMode" {
                CHALLENGE = 1,
                REVIEW = 2,
            }
        """.trimIndent()

        val annotation = AnnotationGenerator.getAnnotationForEnum(enumStartLine = 0, documentText = lua)

        val expected = """
            ---@class EArenaBattleMode
            ---@field CHALLENGE number
            ---@field REVIEW number
        """.trimIndent()

        assertEquals(expected, annotation)
    }

    @Test
    fun `find enum definition when caret inside block`() {
        val lua = """
            enum "EArenaBattleMode" {
                CHALLENGE = 1,
                REVIEW = 2,
            }
        """.trimIndent()

        val (line, _) = PloopParser.findEnumDefinitionAtOrAbove(lua, currentLine = 2) ?: error("enum not found")
        assertEquals(0, line)

        val info = PloopParser.parseEnumInfo(lua, line)
        assertNotNull(info)
        assertEquals("EArenaBattleMode", info!!.enumName)
        assertEquals(listOf("CHALLENGE", "REVIEW"), info.entries.map { it.name })
    }
}
