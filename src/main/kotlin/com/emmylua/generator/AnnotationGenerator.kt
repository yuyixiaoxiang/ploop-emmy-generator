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

    private fun ensureViewBaseOnShowParamsDecl(document: Document, methodLine: Int) {
        val lines = document.text.lines()
        if (methodLine !in lines.indices) return

        val lineText = lines[methodLine]
        val methodInfo = PloopParser.parseMethodAtLine(lineText, methodLine) ?: return
        if (!methodInfo.methodName.equals("OnShow", ignoreCase = true)) return

        val hasParams = methodInfo.params.any { it.equals("params", ignoreCase = true) }
        if (!hasParams) return

        val classInfo = PloopParser.parseClassInfo(document.text, methodLine) ?: return
        if (classInfo.parentClass?.equals("ViewBase", ignoreCase = true) != true) return

        val className = classInfo.className
        val paramsClass = "${className}_params"

        val methodIndent = lineText.takeWhile { it.isWhitespace() }
        val baseIndentLen = methodIndent.length

        // 尝试用方法体第一条语句的缩进作为 bodyIndent；否则用 +4 空格
        var bodyIndent = methodIndent + "    "
        run {
            val max = minOf(lines.lastIndex, methodLine + 5)
            for (i in (methodLine + 1)..max) {
                val l = lines[i]
                if (l.isBlank()) continue
                val ind = l.takeWhile { it.isWhitespace() }
                if (ind.length > baseIndentLen) {
                    bodyIndent = ind
                }
                break
            }
        }

        val expected = "${bodyIndent}---@class $paramsClass"

        // 在该方法体范围内（按缩进回到 method 级别的 end 判断）查重
        for (i in (methodLine + 1) until lines.size) {
            val l = lines[i]
            val trimmed = l.trim()
            val indentLen = l.takeWhile { it.isWhitespace() }.length

            if (trimmed == expected.trim()) return

            if (indentLen <= baseIndentLen && (trimmed == "end" || trimmed.startsWith("end ") || trimmed.startsWith("end;"))) {
                break
            }
        }

        // 插入到 function 下一行（如果下一行已存在内容，则作为新的首行插入）
        val insertOffset = if (methodLine + 1 >= document.lineCount) {
            document.textLength
        } else {
            document.getLineStartOffset(methodLine + 1)
        }
        document.insertString(insertOffset, expected + "\n")
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

        // 特殊处理：inherit "ViewBase" 的 OnShow(params)
        // 1) 强制 params 的类型为 <ClassName>_params
        if (
            classInfo != null &&
            classInfo.parentClass?.equals("ViewBase", ignoreCase = true) == true &&
            methodInfo.methodName.equals("OnShow", ignoreCase = true)
        ) {
            val paramsKey = methodInfo.params.firstOrNull { it.equals("params", ignoreCase = true) }
            if (paramsKey != null) {
                mergedParamTypes[paramsKey] = "${className}_params"
            }
        }

        // 分析返回类型（如果旧注释里手动写了返回类型，优先用旧的）
        val returnType = hints.returnType
            ?: PloopParser.analyzeReturnType(documentText, currentLine)
            ?: PloopParser.inferReturnTypeFromMethodName(methodInfo.methodName)

        // ---@class ClassName
        sb.appendLine("$indent---@class $className")
        val paramsForField = methodInfo.params

        // ---@field public MethodName fun(param:type)
        val fieldParams = paramsForField.joinToString(",") { param ->
            val type = mergedParamTypes[param]
                ?: PloopParser.inferParamType(param, className, documentText, currentLine)
            "$param:$type"
        }
        val fieldReturn = if (returnType != null && returnType != "void" && returnType != "nil") ":$returnType" else ""
        val docSuffix = (docComment?.trim()?.takeIf { it.isNotBlank() } ?: hints.fieldDescription)
            ?.let { " $it" } ?: ""
        sb.appendLine("$indent---@field public ${methodInfo.methodName} fun($fieldParams)$fieldReturn$docSuffix")

        // ---@param 注释（包含 self）
        methodInfo.params.forEach { param ->
            val paramType = mergedParamTypes[param]
                ?: PloopParser.inferParamType(param, className, documentText, currentLine)
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

        // 复用旧类型：
        // - public 优先用旧注释里的类型。
        // - 若 public 旧类型缺失或为 any，则尝试用 backing field 的旧类型（更“具体”），避免刷新后 public=any、private=number 这种漂移。
        val backingName = propertyInfo.fieldName
        val oldPublicType = existingFieldTypes[propertyInfo.propertyName]
        val oldBackingType = backingName?.let { existingFieldTypes[it] }
        val publicType = when {
            oldPublicType == null -> oldBackingType ?: propertyInfo.luaType
            oldPublicType == "any" && !oldBackingType.isNullOrBlank() && oldBackingType != "any" -> oldBackingType
            else -> oldPublicType
        }

        if (docSuffix != null) {
            sb.appendLine("$indent---@field public ${propertyInfo.propertyName} $publicType$docSuffix")
        } else {
            sb.appendLine("$indent---@field public ${propertyInfo.propertyName} $publicType $auto")
        }

        // backing:
        // - 同名时优先用旧 backing 类型
        // - 若 backing 改名（旧注释里没有该 backing），则复用 publicType
        backingName?.let { backing ->
            val backingType = existingFieldTypes[backing] ?: publicType
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

        // 方法注释：不再“吸收/移动”上方的 `--- 说明`（保持原位，不合并到 @field）
        val annotation = getAnnotationForMethod(currentLineText, currentLine, documentText, existingText, null)

        val startOffset = existingRange?.first ?: document.getLineStartOffset(currentLine)
        val endOffset = existingRange?.second ?: startOffset

        // 记录方法名（写入注释后行号可能发生偏移）
        val methodName = PloopParser.parseMethodAtLine(currentLineText, currentLine)?.methodName

        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(startOffset, endOffset, annotation + "\n")

            // 特殊处理：ViewBase.OnShow(params) 插入方法体内的 params class 声明（不重复插入）
            if (methodName != null) {
                val newLine = findMethodLine(document.text.lines(), methodName, currentLine)
                if (newLine != -1) {
                    ensureViewBaseOnShowParamsDecl(document, newLine)
                }
            }
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
        fun isClassEndLine(rawLine: String, baseIndentLen: Int): Boolean {
            val indentLen = rawLine.takeWhile { it.isWhitespace() }.length
            if (indentLen > baseIndentLen) return false

            val trimmed = rawLine.trim()
            return trimmed == "end)" || trimmed.matches(Regex("""^end\)\s*--.*$"""))
        }

        fun findClassEndLine(lines: List<String>, classLineNumber: Int): Int {
            var classEndLine = lines.size
            val baseIndentLen = lines.getOrNull(classLineNumber)?.takeWhile { it.isWhitespace() }?.length ?: 0

            for (i in (classLineNumber + 1) until lines.size) {
                val trimmedLine = lines[i].trim()

                if (isClassEndLine(lines[i], baseIndentLen)) {
                    classEndLine = i
                    break
                }

                if (trimmedLine == "end" && i + 1 < lines.size && isClassEndLine(lines[i + 1], baseIndentLen)) {
                    classEndLine = i
                    break
                }
            }
            return classEndLine
        }

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
                    // property 仍支持把 `--- 说明` 合并进字段注释
                    "property" -> findLeadingTripleDashDocBlock(currentLines, anchorLine)
                    // method 不再处理 `--- 说明`
                    else -> null
                }

                val annotation = when (t.kind) {
                    "method" -> if (lineText != null) getAnnotationForMethod(lineText, actualLine, document.text, existingText, null) else ""
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

                // 特殊处理：ViewBase.OnShow(params) 插入方法体内的 params class 声明（不重复插入）
                if (t.kind == "method") {
                    val newLine = findMethodLine(document.text.lines(), t.name, actualLine)
                    if (newLine != -1) {
                        ensureViewBaseOnShowParamsDecl(document, newLine)
                    }
                }

                when (t.kind) {
                    "method" -> if (existingRange != null) refreshedMethods++ else generatedMethods++
                    "property" -> if (existingRange != null) refreshedProps++ else generatedProps++
                    "classHeader" -> if (existingRange != null) refreshedHeader++ else generatedHeader++
                }
            }

            // 如果 class 继承了基类，插入 super 的类型提示（仅当不存在时）
            val inheritInfo = PloopParser.findInheritClassWithLine(document.text, classLine)
            inheritInfo?.let { (parentName, inheritLine) ->
                val lines = document.text.lines()
                if (inheritLine in lines.indices) {
                    val indent = lines[inheritLine].takeWhile { it.isWhitespace() }
                    val typeLine = "${indent}---@type $parentName"
                    val superLine = "${indent}local super = super --'hack-code-remove'"

                    val lookEnd = minOf(lines.lastIndex, inheritLine + 5)
                    val scope = lines.subList(inheritLine + 1, lookEnd + 1)

                    var foundTypeLineIndex: Int? = null
                    for (idx in (inheritLine + 1)..lookEnd) {
                        val t = lines[idx].trim()
                        if (t.startsWith("---@type ")) {
                            foundTypeLineIndex = idx
                            break
                        }
                    }

                    val hasType = foundTypeLineIndex != null && lines[foundTypeLineIndex!!].trim() == typeLine.trim()
                    val hasSuper = scope.any { it.trim().matches(Regex("""^local\s+super\s*=\s*super(\b.*)?$""")) }

                    // 如果已有 @type 但类型不一致，先替换为新的父类类型
                    if (!hasType && foundTypeLineIndex != null) {
                        val lineStart = document.getLineStartOffset(foundTypeLineIndex!!)
                        val lineEnd = document.getLineEndOffset(foundTypeLineIndex!!)
                        document.replaceString(lineStart, lineEnd, typeLine)
                    }

                    if (!hasType || !hasSuper) {
                        val insertLines = buildList {
                            if (!hasType && foundTypeLineIndex == null) add(typeLine)
                            if (!hasSuper) add(superLine)
                        }
                        if (insertLines.isNotEmpty()) {
                            val insertOffset = document.getLineStartOffset(minOf(inheritLine + 1, document.lineCount - 1))
                            document.insertString(insertOffset, insertLines.joinToString("\n", postfix = "\n"))
                        }
                    }
                }
            }

            // 如果当前 class 没有 inherit，则移除之前生成的 super 类型提示
            if (inheritInfo == null) {
                val lines = document.text.lines()
                val classEndLine = findClassEndLine(lines, classLine).coerceAtMost(lines.lastIndex)

                var i = classLine + 1
                while (i <= classEndLine && i < lines.size) {
                    val t = lines[i].trim()
                    if (t.startsWith("---@type ")) {
                        val next = if (i + 1 < lines.size) lines[i + 1].trim() else ""
                        val isSuper = next == "local super = super --'hack-code-remove'"
                        if (isSuper) {
                            val startOffset = document.getLineStartOffset(i)
                            val endOffset = document.getLineEndOffset(i + 1) + 1
                            document.deleteString(startOffset, endOffset)
                            // 文档变化，重新同步并回退一行
                            i--
                            continue
                        }
                    }
                    i++
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
