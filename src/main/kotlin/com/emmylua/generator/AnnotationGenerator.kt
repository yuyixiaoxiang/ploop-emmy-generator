package com.emmylua.generator

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile

object AnnotationGenerator {

    private data class ExistingMethodTypeHints(
        val paramTypes: Map<String, String> = emptyMap(),
        val returnType: String? = null,
        val fieldDescription: String? = null
    )

    private fun parseExistingMethodTypeHints(annotationText: String?, methodName: String): ExistingMethodTypeHints {
        if (annotationText.isNullOrBlank()) return ExistingMethodTypeHints()

        val paramTypes = mutableMapOf<String, String>()
        var returnType: String? = null
        var fieldDescription: String? = null
        var matchedFieldName = false

        for (raw in annotationText.lines()) {
            val line = raw.trim()

            if (line.startsWith("---@param")) {
                val rest = line.removePrefix("---@param").trim()
                val parts = rest.split(Regex("\\s+"), limit = 3)
                if (parts.size >= 2) {
                    paramTypes[parts[0]] = parts[1]
                }
            }

            if (returnType == null && line.startsWith("---@return")) {
                val rest = line.removePrefix("---@return").trim()
                val type = rest.split(Regex("\\s+"), limit = 2).firstOrNull()
                if (!type.isNullOrBlank()) returnType = type
            }

            // ---@field public MethodName fun(a:type,b:type):ret
            if (line.contains("@field") && line.contains("fun(")) {
                val funStart = line.indexOf("fun(")
                val close = line.indexOf(')', startIndex = funStart)
                if (funStart >= 0 && close > funStart) {
                    val nameMatched = line.contains(methodName)
                    if (nameMatched || !matchedFieldName) {
                        val inside = line.substring(funStart + 4, close)
                        inside.split(',')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .forEach { p ->
                                val idx = p.indexOf(':')
                                if (idx > 0 && idx < p.length - 1) {
                                    val name = p.substring(0, idx).trim()
                                    val type = p.substring(idx + 1).trim()
                                    if (name.isNotEmpty() && type.isNotEmpty()) {
                                        paramTypes.putIfAbsent(name, type)
                                    }
                                }
                            }
                    }
                    if (nameMatched) matchedFieldName = true
                }

                if (returnType == null) {
                    val after = line.substringAfter(")", missingDelimiterValue = "")
                    if (after.startsWith(":")) {
                        val t = after.removePrefix(":").trim().split(Regex("\\s+"), limit = 2).firstOrNull()
                        if (!t.isNullOrBlank()) returnType = t
                    }
                }

                if (fieldDescription == null) {
                    val after = line.substringAfter(")", missingDelimiterValue = "")
                    val desc = after.substringAfter(' ', missingDelimiterValue = "").trim()
                    if (desc.isNotBlank() && desc != "_auto_annotation_") fieldDescription = desc
                }
            }
        }

        return ExistingMethodTypeHints(paramTypes = paramTypes, returnType = returnType, fieldDescription = fieldDescription)
    }

    private fun parseExistingFieldTypes(annotationText: String?): Map<String, String> {
        if (annotationText.isNullOrBlank()) return emptyMap()

        val map = mutableMapOf<String, String>()
        for (raw in annotationText.lines()) {
            val line = raw.trim()
            if (!line.startsWith("---@field")) continue

            val rest = line.removePrefix("---@field").trim()
            val parts = rest.split(Regex("\\s+"))
            if (parts.size < 2) continue

            val (nameIdx, typeIdx) = when (parts[0].lowercase()) {
                "public", "private", "protected" -> 1 to 2
                else -> 0 to 1
            }
            if (typeIdx >= parts.size) continue

            val name = parts[nameIdx]
            val type = parts[typeIdx]
            map[name] = type
        }
        return map
    }

    private fun parseExistingFieldDescriptions(annotationText: String?): Map<String, String> {
        if (annotationText.isNullOrBlank()) return emptyMap()

        val map = mutableMapOf<String, String>()
        for (raw in annotationText.lines()) {
            val line = raw.trim()
            if (!line.startsWith("---@field")) continue

            val rest = line.removePrefix("---@field").trim()
            val parts = rest.split(Regex("\\s+"))
            if (parts.size < 2) continue

            val (nameIdx, typeIdx) = when (parts[0].lowercase()) {
                "public", "private", "protected" -> 1 to 2
                else -> 0 to 1
            }
            if (typeIdx >= parts.size) continue

            val name = parts[nameIdx]
            val descStartIdx = typeIdx + 1
            val desc = if (descStartIdx < parts.size) {
                parts.subList(descStartIdx, parts.size).joinToString(" ").trim()
            } else ""
            if (desc.isNotBlank() && desc != "_auto_annotation_") map[name] = desc
        }
        return map
    }

    private data class DocBlock(
        val text: String,
        val startLine: Int,
        val endLine: Int
    )

    /**
     * 查找紧贴在 anchorLine 上方的说明注释块：形如 `--- xxx`（但不是 `---@`/`---#`）。
     *
     * 规则：
     * - 会跳过空行与已生成的注释行（---@ / ---#），因此允许 `--- 说明` 位于注释块之上。
     * - 支持多行 `---` 说明，按原顺序拼接为一行（用空格分隔）。
     * - endLine 会包含说明行下方紧邻的空行（直到 anchorLine 之前），用于替换时顺便删掉多余空行。
     */
    private fun findLeadingTripleDashDocBlock(lines: List<String>, anchorLine: Int, lookback: Int = 30): DocBlock? {
        if (anchorLine <= 0 || lines.isEmpty()) return null

        val minLine = (anchorLine - lookback).coerceAtLeast(0)
        val docLines = mutableListOf<Pair<Int, String>>()

        var i = anchorLine - 1
        while (i >= minLine) {
            val raw = lines[i]
            val t = raw.trimStart()

            if (t.isEmpty()) {
                i--
                continue
            }

            if (t.startsWith("---@") || t.startsWith("---#")) {
                i--
                continue
            }

            if (t.startsWith("---") && !t.startsWith("---@") && !t.startsWith("---#")) {
                var text = t.removePrefix("---").trim()
                while (text.endsWith(";") || text.endsWith("；")) {
                    text = text.dropLast(1).trimEnd()
                }
                if (text.isNotBlank()) {
                    docLines.add(i to text)
                }
                i--
                continue
            }

            break
        }

        if (docLines.isEmpty()) return null

        val sorted = docLines.sortedBy { it.first }
        val startLine = sorted.first().first
        var endLine = sorted.last().first

        while (endLine + 1 < anchorLine && lines[endLine + 1].trim().isEmpty()) {
            endLine++
        }

        val mergedText = sorted.joinToString(" ") { it.second }.trim()
        if (mergedText.isBlank()) return null

        return DocBlock(text = mergedText, startLine = startLine, endLine = endLine)
    }

    fun getAnnotationForMethod(
        currentLineText: String,
        currentLine: Int,
        documentText: String,
        existingAnnotationText: String? = null,
        docComment: String? = null
    ): String {
        // 解析当前行的方法信息
        val methodInfo = PloopParser.parseMethodAtLine(currentLineText, currentLine)

        if (methodInfo == null) {
            return ""
        }

        val classInfo = PloopParser.parseClassInfo(documentText, currentLine)

        val hints = parseExistingMethodTypeHints(existingAnnotationText, methodInfo.methodName)

        // 获取缩进
        val indent = currentLineText.takeWhile { it.isWhitespace() }

        val sb = StringBuilder()
        val className = classInfo?.className ?: "UnknownClass"

        // self 的类型必须跟随当前类名刷新（不继承旧注释里的 self 类型）
        val mergedParamTypes = hints.paramTypes.toMutableMap()
        if (methodInfo.params.contains("self")) {
            mergedParamTypes["self"] = className
        }

        // 分析返回类型（如果旧注释里手动写了返回类型，优先用旧的）
        val returnType = hints.returnType ?: PloopParser.analyzeReturnType(documentText, currentLine)

        // ---@class ClassName
        sb.appendLine("$indent---@class $className")
        val paramsForField = methodInfo.params

        // ---@field public MethodName fun(param:type)
        val fieldParams = paramsForField.joinToString(",") { param ->
            val type = mergedParamTypes[param] ?: PloopParser.inferParamType(param, className)
            "$param:$type"
        }
        val fieldReturn = if (returnType != null && returnType != "void" && returnType != "nil") ":$returnType" else ""
        val docSuffix = (docComment?.trim()?.takeIf { it.isNotBlank() } ?: hints.fieldDescription)
            ?.let { " $it" } ?: ""
        sb.appendLine("$indent---@field public ${methodInfo.methodName} fun($fieldParams)$fieldReturn$docSuffix")

        // ---@param 注释（包含 self）
        methodInfo.params.forEach { param ->
            val paramType = mergedParamTypes[param] ?: PloopParser.inferParamType(param, className)
            sb.appendLine("$indent---@param $param $paramType")
        }


        val annotation = sb.toString().trimEnd('\n')
        return annotation
    }

    fun getAnnotationForEnum(enumStartLine: Int, documentText: String): String {
        val lines = documentText.lines()
        if (enumStartLine !in lines.indices) return ""

        val enumInfo = PloopParser.parseEnumInfo(documentText, enumStartLine) ?: return ""
        if (enumInfo.entries.isEmpty()) return ""

        val enumLineText = lines[enumStartLine]
        val indent = enumLineText.takeWhile { it.isWhitespace() }

        val sb = StringBuilder()
        sb.appendLine("$indent---@class ${enumInfo.enumName}")

        // 生成字段类型：优先根据 value 推断，否则默认 number（PLoop 常见用法）
        enumInfo.entries.forEach { entry ->
            val type = entry.valueExpr?.let { PloopParser.inferTypeFromExpression(it) } ?: "number"
            sb.appendLine("$indent---@field ${entry.name} $type")
        }

        return sb.toString().trimEnd('\n')
    }

    fun generateAnnotationForEnum(e: AnActionEvent, document: Document, enumStartLine: Int) {
        val project = e.project ?: return
        val documentText = document.text

        val annotation = getAnnotationForEnum(enumStartLine, documentText)
        if (annotation.isBlank()) {
            Messages.showInfoMessage(project, "未能解析到枚举定义或枚举项。", "提示")
            return
        }

        val existingRange = findExistingAnnotationRange(document, enumStartLine)
        WriteCommandAction.runWriteCommandAction(project) {
            if (existingRange != null) {
                document.replaceString(existingRange.first, existingRange.second, annotation + "\n")
            } else {
                val lineStartOffset = document.getLineStartOffset(enumStartLine)
                document.insertString(lineStartOffset, annotation + "\n")
            }
        }
    }

    fun getAnnotationForProperty(
        propertyStartLine: Int,
        documentText: String,
        existingAnnotationText: String? = null,
        docComment: String? = null
    ): String {
        val lines = documentText.lines()
        if (propertyStartLine !in lines.indices) return ""

        val propertyInfo = PloopParser.parsePropertyInfo(documentText, propertyStartLine) ?: return ""
        val existingFieldTypes = parseExistingFieldTypes(existingAnnotationText)
        val existingFieldDesc = parseExistingFieldDescriptions(existingAnnotationText)
        val propertyLineText = lines[propertyStartLine]
        val indent = propertyLineText.takeWhile { it.isWhitespace() }

        val className = PloopParser.parseClassInfo(documentText, propertyStartLine)?.className ?: "UnknownClass"

        val auto = "_auto_annotation_"
        val sb = StringBuilder()
        sb.appendLine("$indent---@class $className $auto")

        val docSuffix = (docComment?.trim()?.takeIf { it.isNotBlank() }
            ?: existingFieldDesc[propertyInfo.propertyName]
            ?: propertyInfo.fieldName?.let { existingFieldDesc[it] })
            ?.let { " $it" }

        val publicType = existingFieldTypes[propertyInfo.propertyName] ?: propertyInfo.luaType
        if (docSuffix != null) {
            sb.appendLine("$indent---@field public ${propertyInfo.propertyName} $publicType$docSuffix")
        } else {
            sb.appendLine("$indent---@field public ${propertyInfo.propertyName} $publicType $auto")
        }

        propertyInfo.fieldName?.let { backing ->
            val backingType = existingFieldTypes[backing] ?: propertyInfo.luaType
            if (docSuffix != null) {
                sb.appendLine("$indent---@field private $backing $backingType$docSuffix")
            } else {
                sb.appendLine("$indent---@field private $backing $backingType $auto")
            }
        }

        return sb.toString().trimEnd('\n')
    }

    fun generateAnnotationForProperty(e: AnActionEvent, document: Document, propertyStartLine: Int) {
        val project = e.project ?: return
        val documentText = document.text

        val existingRange = findExistingAnnotationRange(document, propertyStartLine)
        val existingText = existingRange?.let { document.getText(TextRange(it.first, it.second)) }

        val anchorLine = existingRange?.let { document.getLineNumber(it.first) } ?: propertyStartLine
        val docBlock = findLeadingTripleDashDocBlock(documentText.lines(), anchorLine)

        val annotation = getAnnotationForProperty(propertyStartLine, documentText, existingText, docBlock?.text)
        if (annotation.isBlank()) {
            Messages.showInfoMessage(project, "未能解析到属性定义。", "提示")
            return
        }

        var startOffset = existingRange?.first ?: document.getLineStartOffset(propertyStartLine)
        var endOffset = existingRange?.second ?: startOffset

        docBlock?.let {
            val docStartOffset = document.getLineStartOffset(it.startLine)
            val docEndOffset = document.getLineEndOffset(it.endLine) + 1
            if (docEndOffset <= startOffset) {
                startOffset = docStartOffset
                if (existingRange == null) {
                    endOffset = docEndOffset
                }
            }
        }

        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(startOffset, endOffset, annotation + "\n")
        }
    }


    /**
     * 生成 为方法正成注释
     */
    fun generateAnnotationForMethod(
        e: AnActionEvent, document: Document, currentLine: Int, currentLineText: String
    ) {
        val project = e.project ?: return
        val documentText = document.text

        val existingRange = findExistingAnnotationRange(document, currentLine)
        val existingText = existingRange?.let { document.getText(TextRange(it.first, it.second)) }

        val anchorLine = existingRange?.let { document.getLineNumber(it.first) } ?: currentLine
        val docBlock = findLeadingTripleDashDocBlock(documentText.lines(), anchorLine)

        val annotation = getAnnotationForMethod(currentLineText, currentLine, documentText, existingText, docBlock?.text)

        var startOffset = existingRange?.first ?: document.getLineStartOffset(currentLine)
        var endOffset = existingRange?.second ?: startOffset

        docBlock?.let {
            val docStartOffset = document.getLineStartOffset(it.startLine)
            val docEndOffset = document.getLineEndOffset(it.endLine) + 1
            if (docEndOffset <= startOffset) {
                startOffset = docStartOffset
                if (existingRange == null) {
                    endOffset = docEndOffset
                }
            }
        }

        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(startOffset, endOffset, annotation + "\n")
        }
    }

    fun getAnnotationForClassHeader(
        classLine: Int,
        classLineText: String,
        documentText: String,
        virtualFile: VirtualFile?
    ): String {
        val className = PloopParser.parseClassNameFromLine(classLineText) ?: return ""
        val indent = classLineText.takeWhile { it.isWhitespace() }

        val parent = PloopParser.findInheritClass(documentText, classLine)
        val fileBase = virtualFile?.nameWithoutExtension
        val isSameOrPartial = fileBase != null && (fileBase == className || fileBase.startsWith("${className}_"))
        val pathSegments = virtualFile?.path
            ?.replace('\\', '/')
            ?.split('/')
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        fun hasSeg(seg: String) = pathSegments.any { it.equals(seg, ignoreCase = true) }

        val rootClass: String?
        val rootField: String?

        when {
            isSameOrPartial && className.endsWith("Module") && hasSeg("GameModule") -> {
                rootClass = "GameModule"
                rootField = className.removeSuffix("Module").ifBlank { className }
            }

            isSameOrPartial && className.endsWith("Data") && hasSeg("GameData") -> {
                rootClass = "GameData"
                rootField = className.removeSuffix("Data").ifBlank { className }
            }

            else -> {
                rootClass = null
                rootField = null
            }
        }

        val auto = "_auto_annotation_"
        val sb = StringBuilder()

        if (rootClass != null && rootField != null) {
            sb.appendLine("$indent---@class $rootClass $auto")
            sb.appendLine("$indent---@field $rootField $className $auto")
        }

        val extends = parent?.let { " : $it" } ?: ""
        sb.appendLine("$indent---@class $className$extends $auto")

        return sb.toString().trimEnd('\n')
    }

    fun generateAnnotationForClassHeader(
        e: AnActionEvent,
        document: Document,
        classLine: Int,
        classLineText: String,
        virtualFile: VirtualFile?
    ) {
        val project = e.project ?: return
        val annotation = getAnnotationForClassHeader(classLine, classLineText, document.text, virtualFile)
        if (annotation.isBlank()) return

        val existingRange = findExistingAnnotationRange(document, classLine)
        WriteCommandAction.runWriteCommandAction(project) {
            if (existingRange != null) {
                document.replaceString(existingRange.first, existingRange.second, annotation + "\n")
            } else {
                val lineStartOffset = document.getLineStartOffset(classLine)
                document.insertString(lineStartOffset, annotation + "\n")
            }
        }
    }

    /**
     * 为 class 内的所有方法/属性生成/刷新注释，并在 class 行生成头部注释
     */
    fun generateAnnotationsForClass(
        e: AnActionEvent,
        document: Document,
        classLine: Int,
        classLineText: String,
        virtualFile: VirtualFile?
    ) {
        val project = e.project ?: return

        // 解析类名
        val className = PloopParser.parseClassNameFromLine(classLineText) ?: return
        LuaClassInfo(className, null)

        // 找到所有方法 / 属性
        val methods = PloopParser.findAllMethodsInClass(document.text, classLine)
        val properties = PloopParser.findAllPropertiesInClass(document.text, classLine)

        // class header 也算一种输出，所以不能因为 methods/props 为空就直接 return

        var generatedMethods = 0
        var refreshedMethods = 0
        var generatedProps = 0
        var refreshedProps = 0
        var refreshedHeader = 0
        var generatedHeader = 0

        data class Target(val kind: String, val name: String, val lineNumber: Int)
        val targets = mutableListOf<Target>()
        methods.forEach { targets.add(Target("method", it.methodName, it.lineNumber)) }
        properties.forEach { targets.add(Target("property", it.propertyName, it.lineNumber)) }
        targets.add(Target("classHeader", className, classLine))

        fun findClassLine(lines: List<String>, name: String, hintLine: Int): Int {
            val pattern = Regex("""\bclass\s+[\"']$name[\"']""")
            if (hintLine in lines.indices && pattern.containsMatchIn(lines[hintLine])) return hintLine

            // 同时向上/向下搜索，兼容插入/删除导致的行号偏移
            for (offset in 1..80) {
                val down = hintLine + offset
                if (down in lines.indices && pattern.containsMatchIn(lines[down])) return down

                val up = hintLine - offset
                if (up in lines.indices && pattern.containsMatchIn(lines[up])) return up
            }
            return -1
        }

        // 从后往前处理（避免行号偏移问题）
        WriteCommandAction.runWriteCommandAction(project) {
            for (t in targets.sortedByDescending { it.lineNumber }) {
                val currentLines = document.text.lines()

                val actualLine = when (t.kind) {
                    "method" -> findMethodLine(currentLines, t.name, t.lineNumber)
                    "property" -> findPropertyLine(currentLines, t.name, t.lineNumber)
                    "classHeader" -> findClassLine(currentLines, t.name, t.lineNumber)
                    else -> -1
                }
                if (actualLine == -1) continue

                val lineText = currentLines.getOrNull(actualLine)
                val existingRange = findExistingAnnotationRange(document, actualLine)
                val existingText = existingRange?.let { document.getText(TextRange(it.first, it.second)) }

                val anchorLine = existingRange?.let { document.getLineNumber(it.first) } ?: actualLine
                val docBlock = when (t.kind) {
                    "method", "property" -> findLeadingTripleDashDocBlock(currentLines, anchorLine)
                    else -> null
                }

                val annotation = when (t.kind) {
                    "method" -> if (lineText != null) getAnnotationForMethod(lineText, actualLine, document.text, existingText, docBlock?.text) else ""
                    "property" -> getAnnotationForProperty(actualLine, document.text, existingText, docBlock?.text)
                    "classHeader" -> if (lineText != null) getAnnotationForClassHeader(actualLine, lineText, document.text, virtualFile) else ""
                    else -> ""
                }
                if (annotation.isBlank()) continue

                var startOffset = existingRange?.first ?: document.getLineStartOffset(actualLine)
                var endOffset = existingRange?.second ?: startOffset

                docBlock?.let {
                    val docStartOffset = document.getLineStartOffset(it.startLine)
                    val docEndOffset = document.getLineEndOffset(it.endLine) + 1
                    if (docEndOffset <= startOffset) {
                        startOffset = docStartOffset
                        if (existingRange == null) {
                            endOffset = docEndOffset
                        }
                    }
                }

                document.replaceString(startOffset, endOffset, annotation + "\n")
                when (t.kind) {
                    "method" -> if (existingRange != null) refreshedMethods++ else generatedMethods++
                    "property" -> if (existingRange != null) refreshedProps++ else generatedProps++
                    "classHeader" -> if (existingRange != null) refreshedHeader++ else generatedHeader++
                }
            }
        }

        val msg = buildString {
            if (generatedHeader > 0 || refreshedHeader > 0) {
                append("类注释: 新增 $generatedHeader，刷新 $refreshedHeader。 ")
            }
            if (generatedProps > 0 || refreshedProps > 0) {
                append("属性: 新增 $generatedProps，刷新 $refreshedProps。 ")
            }
            if (generatedMethods > 0 || refreshedMethods > 0) {
                append("方法: 新增 $generatedMethods，刷新 $refreshedMethods。")
            }
            if (isBlank()) {
                append("没有需要处理的内容。")
            }
        }
        Messages.showInfoMessage(project, msg.trim(), "完成")
    }

    /**
     * 根据方法名找到实际行号
     */
    fun findMethodLine(lines: List<String>, methodName: String, hintLine: Int): Int {
        fun matches(i: Int): Boolean {
            if (i !in lines.indices) return false
            val l = lines[i]
            return l.contains("function") && l.contains(methodName)
        }

        if (matches(hintLine)) return hintLine

        // 同时向上/向下搜索，兼容插入/删除导致的行号偏移
        for (offset in 1..80) {
            val down = hintLine + offset
            if (matches(down)) return down

            val up = hintLine - offset
            if (matches(up)) return up
        }
        return -1
    }

    fun findPropertyLine(lines: List<String>, propertyName: String, hintLine: Int): Int {
        val pattern = Regex("""\bproperty\s+[\"']$propertyName[\"']""")

        if (hintLine in lines.indices && pattern.containsMatchIn(lines[hintLine])) {
            return hintLine
        }

        // 同时向上/向下搜索，兼容插入/删除导致的行号偏移
        for (offset in 1..80) {
            val down = hintLine + offset
            if (down in lines.indices && pattern.containsMatchIn(lines[down])) {
                return down
            }

            val up = hintLine - offset
            if (up in lines.indices && pattern.containsMatchIn(lines[up])) {
                return up
            }
        }
        return -1
    }

    /**
     * 查找已存在的注释范围（返回起始和结束offset）
     */
    private fun findExistingAnnotationRange(document: Document, methodLine: Int): Pair<Int, Int>? {
        val lines = document.text.lines()

        var annotationEndLine = -1
        var annotationStartLine = -1

        // 从方法行向上查找注释块（枚举/方法参数可能很多，预留更大的扫描范围）
        for (i in (methodLine - 1) downTo maxOf(0, methodLine - 200)) {
            val line = lines[i].trim()

            // 如果是注释行
            if (line.startsWith("---@") || line.startsWith("---#")) {
                if (annotationEndLine == -1) {
                    annotationEndLine = i
                }
                annotationStartLine = i
            }
            // 如果是普通注释 (-- 但不是 ---@)
            else if (line.startsWith("--") && !line.startsWith("---")) {
                // 如果是“注释掉的代码行”，当作边界（避免把上一个 property 的注释误认为当前的）
                val codeLike = Regex("""^--\s*(property|function|class|enum|module|end)\b""", RegexOption.IGNORE_CASE)
                if (codeLike.containsMatchIn(line)) {
                    break
                }

                // 否则跳过普通注释，继续向上找（允许保留说明性注释）
                continue
            }
            // 如果遇到空行，继续向上查找
            else if (line.isEmpty()) {
                // 如果已经找到了注释块，遇到空行就停止
                if (annotationEndLine != -1) {
                    break
                }
                // 否则继续向上
                continue
            }
            // 遇到非注释代码行，停止
            else {
                break
            }
        }

        // 如果没找到注释
        if (annotationStartLine == -1 || annotationEndLine == -1) {
            return null
        }

        // 检查是否包含 @class 或 @field（确认是我们的注释）
        var hasOurAnnotation = false
        for (i in annotationStartLine..annotationEndLine) {
            val line = lines[i]
            if (line.contains("@class") || line.contains("@field")) {
                hasOurAnnotation = true
                break
            }
        }

        if (!hasOurAnnotation) {
            return null
        }

        val startOffset = document.getLineStartOffset(annotationStartLine)
        val endOffset = document.getLineEndOffset(annotationEndLine) + 1 // +1 包含换行符

        return Pair(startOffset, endOffset)
    }
}
