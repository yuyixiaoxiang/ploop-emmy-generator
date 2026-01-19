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

data class LuaEnumEntry(
    val name: String,
    val valueExpr: String?
)

data class LuaEnumInfo(
    val enumName: String,
    val entries: List<LuaEnumEntry>
)

data class LuaPropertyInfo(
    val propertyName: String,
    val luaType: String,
    val fieldName: String?,
    val hasSetter: Boolean,
    val lineNumber: Int
)

object PloopParser {

    // 匹配 class "ClassName" 或 class "ClassName" (function
    private val CLASS_PATTERN = Regex("""class\s+["'](\w+)["']\s*\(?\s*function""")

    // 匹配 enum "EnumName" （允许 "{" 在同一行或下一行）
    private val ENUM_PATTERN = Regex("""^\s*enum\s+["'](\w+)["']""")

    // 匹配 property "Name"
    private val PROPERTY_PATTERN = Regex("""^\s*property\s+["']([\w_]+)["']""")

    // property 块内字段提取
    private val PROPERTY_TYPE_PATTERN = Regex("""\btype\s*=\s*([\w\.]+)""")
    private val PROPERTY_FIELD_PATTERN = Regex("""\bfield\s*=\s*["']([^"']+)["']""")
    private val PROPERTY_SET_PATTERN = Regex("""\bset\s*=\s*(true|false)""")

    // 匹配 inherit "ParentClass"
    private val INHERIT_PATTERN = Regex("""inherit\s+["'](\w+)["']""")

    // 匹配 function MethodName(self, param1, param2)
    private val METHOD_PATTERN = Regex("""^\s*(local\s+)?function\s+(\w+)\s*\(([^)]*)\)""")

    // 匹配 enum 内的条目：NAME = 1,  或 NAME,
    private val ENUM_ENTRY_PATTERN = Regex("""^\s*(\w+)\s*(?:=\s*([^,}]+))?\s*,?\s*(?:--.*)?$""")

    /**
     * 检查当前行是否是 class 定义行
     */
    fun isClassDefinitionLine(line: String): Boolean {
        return CLASS_PATTERN.containsMatchIn(line)
    }

    /**
     * 检查当前行是否是 enum 定义行
     */
    fun isEnumDefinitionLine(line: String): Boolean {
        return ENUM_PATTERN.containsMatchIn(line)
    }

    /**
     * 从 enum 定义行解析枚举名
     */
    fun parseEnumNameFromLine(line: String): String? {
        return ENUM_PATTERN.find(line)?.groupValues?.get(1)
    }

    fun isPropertyDefinitionLine(line: String): Boolean {
        return PROPERTY_PATTERN.containsMatchIn(line)
    }

    fun parsePropertyNameFromLine(line: String): String? {
        return PROPERTY_PATTERN.find(line)?.groupValues?.get(1)
    }

    /**
     * 从当前行向上查找 property 定义（用于在 property 块内任意行右键也能生成）
     */
    fun findPropertyDefinitionAtOrAbove(documentText: String, currentLine: Int, searchLimit: Int = 800): Pair<Int, String>? {
        val lines = documentText.lines()
        if (lines.isEmpty()) return null

        val start = currentLine.coerceAtMost(lines.lastIndex)
        val minLine = (start - searchLimit).coerceAtLeast(0)

        for (i in start downTo minLine) {
            val line = lines[i]
            if (!isPropertyDefinitionLine(line)) continue

            val strictEndLine = findCurlyBlockEndLine(lines, i)
            if (strictEndLine != null) {
                if (currentLine <= strictEndLine) return Pair(i, line)
                continue
            }

            // 容错：如果因为格式/缺失 '}' 导致无法用 {} 计数确定结束，
            // 则用缩进 + "是否出现下一个同级语句" 来近似判断当前行是否仍在该 property 内。
            val baseIndentLen = line.takeWhile { it.isWhitespace() }.length
            var stillInside = true
            for (j in (i + 1)..start) {
                if (j !in lines.indices) break
                val l = lines[j]
                if (l.isBlank()) continue

                val indentLen = l.takeWhile { it.isWhitespace() }.length
                if (indentLen <= baseIndentLen) {
                    val t = l.trimStart()
                    val hitsTopLevel =
                        isPropertyDefinitionLine(l) ||
                            isClassDefinitionLine(l) ||
                            isEnumDefinitionLine(l) ||
                            METHOD_PATTERN.containsMatchIn(l)

                    if (hitsTopLevel) {
                        stillInside = false
                        break
                    }

                    // 一些文件会把 end/end) 顶格写，视为块结束信号
                    if (t == "end" || t == "end)" || t.startsWith("end)")) {
                        stillInside = false
                        break
                    }
                }
            }

            if (stillInside) return Pair(i, line)
        }
        return null
    }

    fun parsePropertyInfo(documentText: String, propertyStartLine: Int): LuaPropertyInfo? {
        val lines = documentText.lines()
        if (propertyStartLine !in lines.indices) return null

        val propertyName = parsePropertyNameFromLine(lines[propertyStartLine]) ?: return null

        val strictEndLine = findCurlyBlockEndLine(lines, propertyStartLine)
        val endLine = strictEndLine ?: run {
            // 容错：找不到 '}' 时，只向下取一小段文本用于解析 field/type/set
            val baseIndentLen = lines[propertyStartLine].takeWhile { it.isWhitespace() }.length
            val max = minOf(lines.lastIndex, propertyStartLine + 80)
            var last = propertyStartLine
            for (i in (propertyStartLine + 1)..max) {
                val l = lines[i]
                val t = l.trimStart()
                if (t.contains('}')) {
                    last = i
                    break
                }

                val indentLen = l.takeWhile { it.isWhitespace() }.length
                if (indentLen <= baseIndentLen && (isPropertyDefinitionLine(l) || isClassDefinitionLine(l) || isEnumDefinitionLine(l) || METHOD_PATTERN.containsMatchIn(l))) {
                    break
                }
                last = i
            }
            last
        }

        val blockText = lines.subList(propertyStartLine, endLine + 1).joinToString("\n")

        val typeToken = PROPERTY_TYPE_PATTERN.find(blockText)?.groupValues?.getOrNull(1)
        val luaType = inferLuaTypeFromPloopTypeToken(typeToken)

        val fieldName = PROPERTY_FIELD_PATTERN.find(blockText)?.groupValues?.getOrNull(1)
        val hasSetter = PROPERTY_SET_PATTERN.find(blockText)
            ?.groupValues?.getOrNull(1)
            ?.lowercase()
            ?.let { it == "true" }
            ?: true

        return LuaPropertyInfo(
            propertyName = propertyName,
            luaType = luaType,
            fieldName = fieldName,
            hasSetter = hasSetter,
            lineNumber = propertyStartLine
        )
    }

    fun inferLuaTypeFromPloopTypeToken(typeToken: String?): String {
        if (typeToken.isNullOrBlank()) return "any"
        val t = typeToken.trim().removeSuffix(",")
        val lower = t.lowercase()

        return when (lower) {
            "number", "system.number", "integer", "system.integer", "system.int32", "system.int64" -> "number"
            "string", "system.string" -> "string"
            "boolean", "system.boolean" -> "boolean"
            "table", "system.table" -> "table"
            "function", "system.function" -> "function"
            "any", "system.any" -> "any"
            else -> t.substringAfterLast('.')
        }
    }

    /**
     * 从当前行向上查找 enum 定义（用于在枚举块内任意行右键也能生成）
     */
    fun findEnumDefinitionAtOrAbove(documentText: String, currentLine: Int, searchLimit: Int = 80): Pair<Int, String>? {
        val lines = documentText.lines()
        if (lines.isEmpty()) return null

        val start = currentLine.coerceAtMost(lines.lastIndex)
        val minLine = (start - searchLimit).coerceAtLeast(0)

        for (i in start downTo minLine) {
            val line = lines[i]
            if (!isEnumDefinitionLine(line)) continue

            // 计算该 enum 的结束行，确认 currentLine 是否在枚举块内
            val endLine = findCurlyBlockEndLine(lines, i)
            if (endLine != null && currentLine <= endLine) {
                return Pair(i, line)
            }
        }
        return null
    }

    /**
     * 解析 enum 信息（需要传入 enum 定义行号）
     */
    fun parseEnumInfo(documentText: String, enumStartLine: Int): LuaEnumInfo? {
        val lines = documentText.lines()
        if (enumStartLine !in lines.indices) return null

        val enumName = parseEnumNameFromLine(lines[enumStartLine]) ?: return null
        val entries = mutableListOf<LuaEnumEntry>()

        val endLine = findCurlyBlockEndLine(lines, enumStartLine) ?: return null
        for (i in (enumStartLine + 1) until endLine) {
            val raw = lines[i]
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("--")) continue

            ENUM_ENTRY_PATTERN.find(raw)?.let { match ->
                val name = match.groupValues[1]
                val valueExpr = match.groupValues.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }

                // 过滤掉可能误匹配的 "end" / "}" 等
                if (name == "end") return@let
                if (name == "}" || name == "{") return@let

                entries.add(LuaEnumEntry(name, valueExpr))
            }
        }

        return LuaEnumInfo(enumName, entries)
    }

    private fun findCurlyBlockEndLine(lines: List<String>, startLine: Int): Int? {
        var depth = 0
        var started = false
        for (i in startLine until lines.size) {
            val line = lines[i]
            val openCount = line.count { it == '{' }
            val closeCount = line.count { it == '}' }
            if (openCount > 0) started = true
            if (!started) continue
            depth += openCount - closeCount
            if (started && depth <= 0) {
                return i
            }
        }
        return null
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

        val classEndLine = findClassEndLine(lines, classLineNumber)

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

    fun findAllPropertiesInClass(documentText: String, classLineNumber: Int): List<LuaPropertyInfo> {
        val lines = documentText.lines()
        val properties = mutableListOf<LuaPropertyInfo>()

        val classEndLine = findClassEndLine(lines, classLineNumber)

        var i = classLineNumber + 1
        while (i < classEndLine && i < lines.size) {
            val line = lines[i]
            if (isPropertyDefinitionLine(line)) {
                parsePropertyInfo(documentText, i)?.let { properties.add(it) }

                // 跳过整个 property 块，避免块内内容干扰扫描
                val endLine = findCurlyBlockEndLine(lines, i) ?: i
                i = endLine + 1
                continue
            }
            i++
        }

        return properties
    }

    private fun findClassEndLine(lines: List<String>, classLineNumber: Int): Int {
        var classEndLine = lines.size

        for (i in (classLineNumber + 1) until lines.size) {
            val trimmedLine = lines[i].trim()
            // 检测 class 结束：end) 或 end)xxx
            if (trimmedLine == "end)" || trimmedLine.startsWith("end)")) {
                classEndLine = i
                break
            }
            // 检测 Module 结束：end + 下一行 end)
            if (trimmedLine == "end" && i + 1 < lines.size) {
                val nextLine = lines[i + 1].trim()
                if (nextLine == "end)" || nextLine.startsWith("end)")) {
                    classEndLine = i
                    break
                }
            }
        }

        return classEndLine
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
     * 从文档内容中解析class信息 检测class和 基类
     * @param currentLine Int 从0开始
     */
    fun parseClassInfo(documentText: String, currentLine: Int): LuaClassInfo? {
        val lines = documentText.lines()
        var className: String? = null
        var parentClass: String? = null
        var parentClassLine = 0
        // 从当前行向上查找class定义
        for (i in currentLine downTo 0) {
            if (i >= lines.size) continue
            val line = lines[i]
            
            // 查找class定义
            if (className == null) {
                CLASS_PATTERN.find(line)?.let {
                    className = it.groupValues[1]
                    parentClassLine = i
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

        //再次查找一次基类
        if(className != null && parentClass == null) {
            for(i in parentClassLine.until(lines.size)) {
                val line = lines[i]
                if (!line.trim().startsWith("end)")){
                    INHERIT_PATTERN.find(line)?.let {
                        parentClass = it.groupValues[1]
                    }
                }
                if(parentClass != null) break
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
    fun inferTypeFromExpression(expr: String): String {
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
