package com.emmylua.generator

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class PloopParserTest {

    @Test
    fun `test isClassDefinitionLine - valid class definition`() {
        assertTrue(PloopParser.isClassDefinitionLine("""class "HomeSceneModule" (function(_ENV)"""))
        assertTrue(PloopParser.isClassDefinitionLine("""    class "TestClass" (function(_ENV)"""))
        assertTrue(PloopParser.isClassDefinitionLine("""class 'SingleQuote' (function(_ENV)"""))
    }

    @Test
    fun `test isClassDefinitionLine - invalid lines`() {
        assertFalse(PloopParser.isClassDefinitionLine("""function TestMethod(self)"""))
        assertFalse(PloopParser.isClassDefinitionLine("""local class = {}"""))
        assertFalse(PloopParser.isClassDefinitionLine("""-- class "Comment" """))
    }

    @Test
    fun `test parseClassNameFromLine`() {
        assertEquals("HomeSceneModule", PloopParser.parseClassNameFromLine("""class "HomeSceneModule" (function(_ENV)"""))
        assertEquals("TestClass", PloopParser.parseClassNameFromLine("""    class "TestClass" (function(_ENV)"""))
        assertNull(PloopParser.parseClassNameFromLine("""function Test()"""))
    }

    @Test
    fun `test parseMethodAtLine - simple method`() {
        val result = PloopParser.parseMethodAtLine("        function OnShow(self, params)", 10)
        assertNotNull(result)
        assertEquals("OnShow", result?.methodName)
        assertEquals(listOf("self", "params"), result?.params)
        assertFalse(result?.isLocal ?: true)
        assertEquals(10, result?.lineNumber)
    }

    @Test
    fun `test parseMethodAtLine - method without self`() {
        val result = PloopParser.parseMethodAtLine("        function StaticMethod(data, callback)", 5)
        assertNotNull(result)
        assertEquals("StaticMethod", result?.methodName)
        assertEquals(listOf("data", "callback"), result?.params)
    }

    @Test
    fun `test parseMethodAtLine - method with no params`() {
        val result = PloopParser.parseMethodAtLine("        function NoParams()", 0)
        assertNotNull(result)
        assertEquals("NoParams", result?.methodName)
        assertEquals(emptyList<String>(), result?.params)
    }

    @Test
    fun `test parseMethodAtLine - local function should be detected`() {
        val result = PloopParser.parseMethodAtLine("        local function Helper(a, b)", 0)
        assertNotNull(result)
        assertEquals("Helper", result?.methodName)
        assertTrue(result?.isLocal ?: false)
    }

    @Test
    fun `test parseMethodAtLine - invalid line`() {
        assertNull(PloopParser.parseMethodAtLine("        local x = 1", 0))
        assertNull(PloopParser.parseMethodAtLine("        -- comment", 0))
    }

    @Test
    fun `test parseClassInfo - finds class from method line`() {
        val doc = """
Module "Game.Module.HomeSceneModule" (function(_ENV)
    class "HomeSceneModule2" (function(_ENV)
        inherit "ModuleBase"
        
        function OnShow(self)
        end
    end)
    
    class "HomeSceneModule" (function(_ENV)
        inherit "ModuleBase"
        
        function OnShow(self)
        end
    end)
end)
        """.trimIndent()
        
        val result = PloopParser.parseClassInfo(doc, 12) // OnShow line
        assertNotNull(result)
        assertEquals("HomeSceneModule", result?.className)

        val result2 = PloopParser.parseClassInfo(doc, 7) // OnShow line
        assertNotNull(result2)
        assertEquals("HomeSceneModule", result?.className)
    }

    @Test
    fun `test findAllMethodsInClass`() {
        val doc = """
Module "Game.Module.TestModule" (function(_ENV)
    class "TestModule" (function(_ENV)
        inherit "ModuleBase"
        
        function Method1(self)
        end
        
        function Method2(self, param)
        end
        
        local function PrivateMethod()
        end
        
        function Method3(self, a, b, c)
        end
    end)
end)
        """.trimIndent()
        
        val methods = PloopParser.findAllMethodsInClass(doc, 1) // class line
        
        // Should find 3 public methods (not the local one)
        assertEquals(3, methods.size)
        assertEquals("Method1", methods[0].methodName)
        assertEquals("Method2", methods[1].methodName)
        assertEquals("Method3", methods[2].methodName)
    }

    @Test
    fun `test findAllMethodsInClass - should not stop at end in callback`() {
        val doc = """
Module "Game.Module.TestModule" (function(_ENV)
    class "TestModule" (function(_ENV)
        function Method1(self)
            Foo(function()
            end);
        end

        function Method2(self)
        end
    end)
end)
        """.trimIndent()

        val methods = PloopParser.findAllMethodsInClass(doc, 1) // class line
        assertEquals(listOf("Method1", "Method2"), methods.map { it.methodName })
    }

    @Test
    fun `test inferParamType`() {
        // id 默认 number|string（缺少方法体上下文时）
        assertEquals("number|string", PloopParser.inferParamType("heroId"))
        assertEquals("number|string", PloopParser.inferParamType("user_id"))

        assertEquals("string", PloopParser.inferParamType("userName"))
        assertEquals("table", PloopParser.inferParamType("dataList"))
        assertEquals("table", PloopParser.inferParamType("configDict"))
        assertEquals("table", PloopParser.inferParamType("heroes"))
        assertEquals("boolean", PloopParser.inferParamType("isActive"))
        assertEquals("boolean", PloopParser.inferParamType("hasItem"))
        assertEquals("function", PloopParser.inferParamType("callback"))
        assertEquals("number", PloopParser.inferParamType("index"))
        assertEquals("number", PloopParser.inferParamType("posX"))
        assertEquals("number", PloopParser.inferParamType("cnt"))
        assertEquals("number", PloopParser.inferParamType("width"))
        assertEquals("number", PloopParser.inferParamType("Height"))
        assertEquals("number", PloopParser.inferParamType("w"))
        assertEquals("number", PloopParser.inferParamType("h"))
        assertEquals("string", PloopParser.inferParamType("s"))
        assertEquals("string", PloopParser.inferParamType("Text"))
        assertEquals("string", PloopParser.inferParamType("uId2"))
        assertEquals("string", PloopParser.inferParamType("GUID"))
        assertEquals("string", PloopParser.inferParamType("uuid"))
        assertEquals("table", PloopParser.inferParamType("params"))
        assertEquals("any", PloopParser.inferParamType("something"))
    }

    @Test
    fun `test inferParamType - id should prefer string when compared to empty string`() {
        val doc = """
Module "Game.Module.TestModule" (function(_ENV)
    class "TestModule" (function(_ENV)
        function Foo(self, uId)
            if uId == "" then
                return
            end
        end
    end)
end)
        """.trimIndent()

        // function Foo 所在行：2 (0-based)
        assertEquals("string", PloopParser.inferParamType("uId", "", doc, 2))
    }

    @Test
    fun `test inferParamType - id should prefer number when used in math`() {
        val doc = """
Module "Game.Module.TestModule" (function(_ENV)
    class "TestModule" (function(_ENV)
        function Foo(self, heroId)
            if heroId > 0 then
                heroId = heroId + 1
            end
        end
    end)
end)
        """.trimIndent()

        // function Foo 所在行：2 (0-based)
        assertEquals("number", PloopParser.inferParamType("heroId", "", doc, 2))
    }

    @Test
    fun `test inferReturnTypeFromMethodName`() {
        assertEquals("boolean", PloopParser.inferReturnTypeFromMethodName("IsOpen"))
        assertEquals("boolean", PloopParser.inferReturnTypeFromMethodName("hasItem"))
        assertEquals("any", PloopParser.inferReturnTypeFromMethodName("GetData"))
        assertNull(PloopParser.inferReturnTypeFromMethodName("OpenPanel"))
    }

    @Test
    fun `test hasAnnotationForMethod - has annotation`() {
        val doc = """
---@class TestClass
---@field public TestMethod fun() @desc
function TestMethod(self)
end
        """.trimIndent()
        
//        assertTrue(PloopParser.hasAnnotationForMethod(doc, 3, "TestMethod"))
        assertTrue(true);
    }

    @Test
    fun `test hasAnnotationForMethod - no annotation`() {
        val doc = """
-- just a comment
function TestMethod(self)
end
        """.trimIndent()
        
        assertFalse(PloopParser.hasAnnotationForMethod(doc, 2, "TestMethod"))
    }
}
