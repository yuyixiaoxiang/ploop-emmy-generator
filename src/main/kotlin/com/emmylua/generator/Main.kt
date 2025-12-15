package com.emmylua.generator

import java.io.File

fun main(args: Array<String>) {
    println("===========================================")
    println("EmmyLua Annotation Generator for PLoop")
    println("===========================================")
    println()
    return;
    
    if (args.isEmpty()) {
        // 演示模式
        runDemo()
    } else {
        // 处理文件
        val filePath = args[0]
        processFile(filePath)
    }
}

fun runDemo() {
    println("演示模式 - 测试注释生成")
    println()
    
    val sampleCode = """
Module "Game.Module.HomeSceneModule" (function(_ENV)
    class "HomeSceneModule" (function(_ENV)
        inherit "ModuleBase"
        
        property "Buildings" { field = "__buildings", type = System.Table }
        
        function OnInit(self)
            -- 初始化
        end
        
        function OnShow(self, params)
            -- 显示
        end
        
        function GetBuildingState(self, uid)
            local bView = self.Buildings[uid]
            if bView then
                return bView.InUnlockProcess
            end
            return false
        end
        
        function RefreshUI(self, heroId, callback)
            -- 刷新UI
        end
        
        local function PrivateHelper()
            -- 私有方法，不生成注释
        end
    end)
end)
    """.trimIndent()
    
    println("输入代码:")
    println("-------------------------------------------")
    println(sampleCode)
    println("-------------------------------------------")
    println()
    
    // 测试 class 检测
    val lines = sampleCode.lines()
    var classLine = -1
    for (i in lines.indices) {
        if (PloopParser.isClassDefinitionLine(lines[i])) {
            classLine = i
            println("找到 class 定义在第 ${i + 1} 行: ${lines[i].trim()}")
            val className = PloopParser.parseClassNameFromLine(lines[i])
            println("类名: $className")
            break
        }
    }
    
    println()
    
    // 扫描所有方法
    if (classLine >= 0) {
        val methods = PloopParser.findAllMethodsInClass(sampleCode, classLine)
        println("找到 ${methods.size} 个公共方法:")
        methods.forEach { method ->
            println("  - ${method.methodName}(${method.params.joinToString(", ")}) at line ${method.lineNumber + 1}")
        }
        
        println()
        println("生成的注释:")
        println("-------------------------------------------")
        
        val classInfo = LuaClassInfo(PloopParser.parseClassNameFromLine(lines[classLine]) ?: "Unknown", null)
        
        methods.forEach { method ->
            val indent = "        "
            val returnType = PloopParser.analyzeReturnType(sampleCode, method.lineNumber)
            val annotation = AnnotationGenerator.generate(method, classInfo, returnType, indent)
            println(annotation)
            println("${indent}function ${method.methodName}(${method.params.joinToString(", ")})")
            println()
        }
        println("-------------------------------------------")
    }
}

fun processFile(filePath: String) {
    val file = File(filePath)
    if (!file.exists()) {
        println("错误: 文件不存在 - $filePath")
        return
    }
    
    if (!file.extension.equals("lua", ignoreCase = true)) {
        println("警告: 不是 .lua 文件")
    }
    
    val content = file.readText()
    println("处理文件: $filePath")
    println("文件大小: ${content.length} 字符")
    println()
    
    // 查找所有 class 定义
    val lines = content.lines()
    var totalMethods = 0
    
    for (i in lines.indices) {
        if (PloopParser.isClassDefinitionLine(lines[i])) {
            val className = PloopParser.parseClassNameFromLine(lines[i])
            println("找到类: $className (第 ${i + 1} 行)")
            
            val methods = PloopParser.findAllMethodsInClass(content, i)
            println("  方法数量: ${methods.size}")
            methods.forEach { method ->
                val hasAnnotation = PloopParser.hasAnnotationForMethod(content, method.lineNumber, method.methodName)
                val status = if (hasAnnotation) "✓ 已有注释" else "✗ 缺少注释"
                println("    - ${method.methodName} [$status]")
            }
            totalMethods += methods.size
            println()
        }
    }
    
    println("总计: $totalMethods 个方法")
}
