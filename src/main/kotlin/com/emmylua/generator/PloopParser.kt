package com.emmylua.generator

import com.intellij.internal.statistic.config.StatisticsStringUtil.toLowerCase

data class LuaMethodInfo(
    val className: String?,
    val methodName: String,
    val params: List<String>,
    val isLocal: Boolean,
    val lineNumber: Int
)

data class LuaClassInfo(
    val className: String,
    val parentClass: String?
)

object PloopParser {

    // 匹配 class "ClassName" 或 class "ClassName" (function
    private val CLASS_PATTERN = Regex("""class\s+["'](\w+)["']\s*\(?\s*function""")
    
    // 匹配 inherit "ParentClass"
    private val INHERIT_PATTERN = Regex("""inherit\s+["'](\w+)["']""")
    
    // 匹配 function MethodName(self, param1, param2)
    private val METHOD_PATTERN = Regex("""^\s*(local\s+)?function\s+(\w+)\s*\(([^)]*)\)""")
    
    /**
     * 检查当前行是否是 class 定义行
     */
    fun isClassDefinitionLine(line: String): Boolean {
        return CLASS_PATTERN.containsMatchIn(line)
    }
    
    /**
     * 从 class 定义行解析类名
     */
    fun parseClassNameFromLine(line: String): String? {
        return CLASS_PATTERN.find(line)?.groupValues?.get(1)
    }
    
    /**
     * 从 class 定义行向下查找 inherit 语句，获取父类名
     */
    fun findInheritClass(documentText: String, classLineNumber: Int): String? {
        val lines = documentText.lines()
        // 在 class 行后的10行内查找 inherit
        for (i in (classLineNumber + 1) until minOf(classLineNumber + 10, lines.size)) {
            val line = lines[i].trim()
            INHERIT_PATTERN.find(line)?.let {
                return it.groupValues[1]
            }
            // 如果遇到 function 定义，停止查找
            if (line.startsWith("function ") || line.startsWith("property ")) {
                break
            }
        }
        return null
    }
    
    /**
     * 扫描 class 内的所有方法（从 class 定义行开始向下扫描）
     */
    fun findAllMethodsInClass(documentText: String, classLineNumber: Int): List<LuaMethodInfo> {
        val lines = documentText.lines()
        val methods = mutableListOf<LuaMethodInfo>()
        
        // 找到 class 的结束位置（两个连续的 end）
        var endCount = 0
        var classEndLine = lines.size
        
        for (i in (classLineNumber + 1) until lines.size) {
            val trimmedLine = lines[i].trim()
            // 检测 class 结束：end) 或单独的 end 后面跟着 end)
            if (trimmedLine == "end)" || trimmedLine.startsWith("end)")) {
                classEndLine = i
                break
            }
            // 检测 Module 结束
            if (trimmedLine == "end" && i + 1 < lines.size) {
                val nextLine = lines[i + 1].trim()
                if (nextLine == "end)" || nextLine.startsWith("end)")) {
                    classEndLine = i
                    break
                }
            }
        }
        
        // 扫描 class 内的所有方法
        for (i in (classLineNumber + 1) until classEndLine) {
            val line = lines[i]
            
            // 匹配 function 定义（非 local）
            val methodInfo = parseMethodAtLine(line, i)
            if (methodInfo != null && !methodInfo.isLocal) {
                methods.add(methodInfo)
            }
        }
        
        return methods
    }
    
    /**
     * 检查方法是否已有注释
     */
    fun hasAnnotationForMethod(documentText: String, methodLine: Int, methodName: String): Boolean {
        val lines = documentText.lines()
        
        // 检查上方最多5行
        for (i in 1..minOf(5, methodLine)) {
            val lineIndex = methodLine - i
            if (lineIndex < 0) break
            
            val line = lines[lineIndex].trim()
            
            // 如果遇到非注释行，停止
            if (!line.startsWith("---") && line.isNotEmpty() && !line.startsWith("--")) {
                break
            }
            
            // 检查是否有该方法的 @field 注释
            if (line.contains("@field") && line.contains(methodName)) {
                return true
            }
        }
        
        return false
    }
    
    // 匹配 self.MethodName = function(...)
    private val SELF_METHOD_PATTERN = Regex("""^\s*self\.(\w+)\s*=\s*function\s*\(([^)]*)\)""")

    /**
     * 从文档内容中解析class信息
     */
    fun parseClassInfo(documentText: String, currentLine: Int): LuaClassInfo? {
        val lines = documentText.lines()
        var className: String? = null
        var parentClass: String? = null
        
        // 从当前行向上查找class定义
        for (i in currentLine downTo 0) {
            if (i >= lines.size) continue
            val line = lines[i]
            
            // 查找class定义
            if (className == null) {
                CLASS_PATTERN.find(line)?.let {
                    className = it.groupValues[1]
                }
            }
            
            // 查找inherit定义
            if (parentClass == null) {
                INHERIT_PATTERN.find(line)?.let {
                    parentClass = it.groupValues[1]
                }
            }
            
            // 如果找到了class定义，就可以停止了
            if (className != null) {
                break
            }
        }
        
        return className?.let { LuaClassInfo(it, parentClass) }
    }

    /**
     * 解析当前行的方法信息
     */
    fun parseMethodAtLine(line: String, lineNumber: Int): LuaMethodInfo? {
        // 尝试匹配普通方法定义
        METHOD_PATTERN.find(line)?.let { match ->
            val isLocal = match.groupValues[1].isNotEmpty()
            val methodName = match.groupValues[2]
            val paramsStr = match.groupValues[3]
            val params = parseParams(paramsStr)
            
            return LuaMethodInfo(
                className = null,
                methodName = methodName,
                params = params,
                isLocal = isLocal,
                lineNumber = lineNumber
            )
        }
        
        // 尝试匹配 self.method = function 形式
        SELF_METHOD_PATTERN.find(line)?.let { match ->
            val methodName = match.groupValues[1]
            val paramsStr = match.groupValues[2]
            val params = parseParams(paramsStr)
            
            return LuaMethodInfo(
                className = null,
                methodName = methodName,
                params = params,
                isLocal = false,
                lineNumber = lineNumber
            )
        }
        
        return null
    }

    /**
     * 解析参数列表
     */
    private fun parseParams(paramsStr: String): List<String> {
        if (paramsStr.isBlank()) return emptyList()
        
        return paramsStr.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * 分析方法体，尝试推断返回类型
     */
    fun analyzeReturnType(documentText: String, methodStartLine: Int): String? {
        val lines = documentText.lines()
        var braceCount = 0
        var foundStart = false
        
        for (i in methodStartLine until lines.size) {
            val line = lines[i]
            
            // 检测是否有return语句
            if (line.contains("return ")) {
                val returnMatch = Regex("""return\s+(.+)""").find(line)
                returnMatch?.let {
                    val returnExpr = it.groupValues[1].trim().removeSuffix(";")
                    return inferTypeFromExpression(returnExpr)
                }
            }
            
            // 简单的括号计数来判断方法结束
            braceCount += line.count { it == '(' } - line.count { it == ')' }
            
            // 检测end关键字
            if (line.trim() == "end" || line.trim().startsWith("end ") || line.trim().startsWith("end;")) {
                break
            }
        }
        
        return null
    }

    /**
     * 从表达式推断类型
     */
    private fun inferTypeFromExpression(expr: String): String {
        return when {
            expr == "true" || expr == "false" -> "boolean"
            expr == "nil" -> "nil"
            expr.startsWith("\"") || expr.startsWith("'") -> "string"
            expr.matches(Regex("""-?\d+(\.\d+)?""")) -> "number"
            expr.startsWith("{") -> "table"
            expr.startsWith("self") -> "self"
            else -> "any"
        }
    }

    /**
     * 推断参数类型（基于命名约定）
     */
    fun inferParamType(paramName: String,className: String = ""): String {
        val paramsStr = toLowerCase(paramName)
        return when {
            paramsStr == "self" -> className // 特殊标记，后续替换为类名
            paramsStr.endsWith("id") -> "number"
            paramsStr.endsWith("name") -> "string"
            paramsStr.endsWith("list")-> "table"
            paramsStr.endsWith("dict") -> "table"
            paramsStr.endsWith("cfg")  -> "table"
            paramsStr.endsWith("data")  -> "table"
            paramsStr.startsWith("is") || paramsStr.startsWith("has") || paramsStr.startsWith("can") -> "boolean"
            paramsStr == "index" || paramsStr == "idx" || paramsStr == "count" || paramsStr == "num" -> "number"
            paramsStr == "callback" || paramsStr == "cb" || paramsStr == "func" || paramsStr.endsWith("callback")  -> "function"
            paramsStr.contains("params")  || paramsStr.contains("args") ||paramsStr.contains("options")  -> "table"
            paramsStr.endsWith("event") -> "string|number"
            else -> "any"
        }
    }
}
