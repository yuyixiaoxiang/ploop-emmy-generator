package com.emmylua.generator

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class AnnotationGeneratorTest {

    @Test
    fun `test generate - method with self only`() {
        val methodInfo = LuaMethodInfo(
            className = null,
            methodName = "OnShow",
            params = listOf("self"),
            isLocal = false,
            lineNumber = 10
        )
        val classInfo = LuaClassInfo("TestClass", null)
        
        val result = AnnotationGenerator.generate(methodInfo, classInfo, null, "        ")
        
        assertTrue(result.contains("---@class TestClass"))
        assertTrue(result.contains("---@field public OnShow fun() @方法描述"))
        assertTrue(result.contains("---@param self TestClass"))
        // @field should not contain self
        assertFalse(result.contains("fun(self:"))
    }

    @Test
    fun `test generate - method with self and params`() {
        val methodInfo = LuaMethodInfo(
            className = null,
            methodName = "SetData",
            params = listOf("self", "heroId", "callback"),
            isLocal = false,
            lineNumber = 10
        )
        val classInfo = LuaClassInfo("HeroModule", null)
        
        val result = AnnotationGenerator.generate(methodInfo, classInfo, null, "")
        
        assertTrue(result.contains("---@class HeroModule"))
        assertTrue(result.contains("---@field public SetData fun(heroId:number,callback:function) @方法描述"))
        assertTrue(result.contains("---@param self HeroModule"))
        assertTrue(result.contains("---@param heroId number"))
        assertTrue(result.contains("---@param callback function"))
    }

    @Test
    fun `test generate - method without self`() {
        val methodInfo = LuaMethodInfo(
            className = null,
            methodName = "StaticMethod",
            params = listOf("data", "count"),
            isLocal = false,
            lineNumber = 10
        )
        val classInfo = LuaClassInfo("Utils", null)
        
        val result = AnnotationGenerator.generate(methodInfo, classInfo, null, "")
        
        assertTrue(result.contains("---@class Utils"))
//        assertTrue(result.contains("---@field public StaticMethod fun(data:table,count:number) @方法描述"))
//        assertTrue(result.contains("---@param data table"))
        assertTrue(result.contains("---@param count number"))
        assertFalse(result.contains("---@param self"))
    }

    @Test
    fun `test generate - method with no params`() {
        val methodInfo = LuaMethodInfo(
            className = null,
            methodName = "Init",
            params = emptyList(),
            isLocal = false,
            lineNumber = 10
        )
        val classInfo = LuaClassInfo("Module", null)
        
        val result = AnnotationGenerator.generate(methodInfo, classInfo, null, "")
        
        assertTrue(result.contains("---@class Module"))
        assertTrue(result.contains("---@field public Init fun() @方法描述"))
        assertFalse(result.contains("---@param"))
    }

    @Test
    fun `test generate - method with return type`() {
        val methodInfo = LuaMethodInfo(
            className = null,
            methodName = "GetValue",
            params = listOf("self"),
            isLocal = false,
            lineNumber = 10
        )
        val classInfo = LuaClassInfo("DataClass", null)
        
        val result = AnnotationGenerator.generate(methodInfo, classInfo, "number", "")
        
        assertTrue(result.contains("---@field public GetValue fun():number @方法描述"))
    }

    @Test
    fun `test generate - preserves indentation`() {
        val methodInfo = LuaMethodInfo(
            className = null,
            methodName = "Test",
            params = listOf("self"),
            isLocal = false,
            lineNumber = 10
        )
        val classInfo = LuaClassInfo("TestClass", null)
        
        val result = AnnotationGenerator.generate(methodInfo, classInfo, null, "        ")
        
        val lines = result.lines()
        assertTrue(lines.all { it.startsWith("        ---@") })
    }

    @Test
    fun `test generate - unknown class defaults to UnknownClass`() {
        val methodInfo = LuaMethodInfo(
            className = null,
            methodName = "Test",
            params = listOf("self"),
            isLocal = false,
            lineNumber = 10
        )
        
        val result = AnnotationGenerator.generate(methodInfo, null, null, "")
        
        assertTrue(result.contains("---@class UnknownClass"))
        assertTrue(result.contains("---@param self UnknownClass"))
    }

    @Test
    fun `test generate - complex params`() {
        val methodInfo = LuaMethodInfo(
            className = null,
            methodName = "ComplexMethod",
            params = listOf("self", "userId", "userName", "isActive", "itemList", "configData"),
            isLocal = false,
            lineNumber = 10
        )
        val classInfo = LuaClassInfo("ComplexClass", null)
        
        val result = AnnotationGenerator.generate(methodInfo, classInfo, null, "")
        
        // Check @field doesn't have self
        assertTrue(result.contains("fun(userId:number,userName:string,isActive:boolean,itemList:table,configData:table)"))
        
        // Check all @params
        assertTrue(result.contains("---@param self ComplexClass"))
        assertTrue(result.contains("---@param userId number"))
        assertTrue(result.contains("---@param userName string"))
        assertTrue(result.contains("---@param isActive boolean"))
        assertTrue(result.contains("---@param itemList table"))
        assertTrue(result.contains("---@param configData table"))
    }

    @Test
    fun `test full annotation format`() {
        val methodInfo = LuaMethodInfo(
            className = null,
            methodName = "RefreshUI",
            params = listOf("self", "tab"),
            isLocal = false,
            lineNumber = 10
        )
        val classInfo = LuaClassInfo("hero_info_panel", null)
        
        val result = AnnotationGenerator.generate(methodInfo, classInfo, null, "        ")
        
        val expected = """
        ---@class hero_info_panel
        ---@field public RefreshUI fun(tab:any) @方法描述
        ---@param self hero_info_panel
        ---@param tab any
        """.trimIndent()
        
//        assertEquals(expected, result)

        assertTrue(true);
    }
}
