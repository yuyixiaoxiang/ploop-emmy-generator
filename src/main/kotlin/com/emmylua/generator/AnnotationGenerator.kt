package com.emmylua.generator

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.ui.Messages

object AnnotationGenerator {


    fun getAnnotationForMethod(currentLineText: String, currentLine: Int, documentText: String): String {
        // 解析当前行的方法信息
        val methodInfo = PloopParser.parseMethodAtLine(currentLineText, currentLine)

        if (methodInfo == null) {
            return ""
        }

        val classInfo = PloopParser.parseClassInfo(documentText, currentLine)

        // 分析返回类型
        val returnType = PloopParser.analyzeReturnType(documentText, currentLine)

        // 获取缩进
        val indent = currentLineText.takeWhile { it.isWhitespace() }

        val sb = StringBuilder()
        val className = classInfo?.className ?: "UnknownClass"

        // ---@class ClassName
        sb.appendLine("$indent---@class $className")
        val paramsForField = methodInfo.params

        // ---@field public MethodName fun(param:type)
        val fieldParams = paramsForField.joinToString(",") { param ->
            val type = PloopParser.inferParamType(param, className)
            "$param:$type"
        }
        val fieldReturn = if (returnType != null && returnType != "void" && returnType != "nil") ":$returnType" else ""
        sb.appendLine("$indent---@field public ${methodInfo.methodName} fun($fieldParams)$fieldReturn")

        // ---@param 注释（包含 self）
        methodInfo.params.forEach { param ->
            val paramType = PloopParser.inferParamType(param, className)
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

    fun getAnnotationForProperty(propertyStartLine: Int, documentText: String): String {
        val lines = documentText.lines()
        if (propertyStartLine !in lines.indices) return ""

        val propertyInfo = PloopParser.parsePropertyInfo(documentText, propertyStartLine) ?: return ""
        val propertyLineText = lines[propertyStartLine]
        val indent = propertyLineText.takeWhile { it.isWhitespace() }

        val className = PloopParser.parseClassInfo(documentText, propertyStartLine)?.className ?: "UnknownClass"

        val auto = "_auto_annotation_"
        val sb = StringBuilder()
        sb.appendLine("$indent---@class $className $auto")
        sb.appendLine("$indent---@field public ${propertyInfo.propertyName} ${propertyInfo.luaType} $auto")
        propertyInfo.fieldName?.let { backing ->
            sb.appendLine("$indent---@field private $backing ${propertyInfo.luaType} $auto")
        }

        return sb.toString().trimEnd('\n')
    }

    fun generateAnnotationForProperty(e: AnActionEvent, document: Document, propertyStartLine: Int) {
        val project = e.project ?: return
        val documentText = document.text

        val annotation = getAnnotationForProperty(propertyStartLine, documentText)
        if (annotation.isBlank()) {
            Messages.showInfoMessage(project, "未能解析到属性定义。", "提示")
            return
        }

        val existingRange = findExistingAnnotationRange(document, propertyStartLine)
        WriteCommandAction.runWriteCommandAction(project) {
            if (existingRange != null) {
                document.replaceString(existingRange.first, existingRange.second, annotation + "\n")
            } else {
                val lineStartOffset = document.getLineStartOffset(propertyStartLine)
                document.insertString(lineStartOffset, annotation + "\n")
            }
        }
    }


    /**
     * 生成 为方法正成注释
     */
    fun generateAnnotationForMethod(
        e: AnActionEvent, document: Document, currentLine: Int, currentLineText: String
    ) {
        // 解析class信息
        val documentText = document.text

        val annotation = getAnnotationForMethod(currentLineText, currentLine, documentText)
        // 检查是否已存在注释，如果存在则删除旧注释
        val existingRange = findExistingAnnotationRange(document, currentLine)

        WriteCommandAction.runWriteCommandAction(e.project) {
            if (existingRange != null) {
                // 删除旧注释，插入新注释
                document.replaceString(existingRange.first, existingRange.second, annotation + "\n")
            } else {
                val lineStartOffset = document.getLineStartOffset(currentLine)
                // 直接插入新注释
                document.insertString(lineStartOffset, annotation + "\n")
            }
        }
    }

    /**
     * 为 class 内的所有方法生成/刷新注释
     */
    fun generateAnnotationsForClass(
        e: AnActionEvent, document: Document, classLine: Int, classLineText: String
    ) {
        val project = e.project ?: return

        // 解析类名
        val className = PloopParser.parseClassNameFromLine(classLineText) ?: return
        LuaClassInfo(className, null)

        // 找到所有方法 / 属性
        val methods = PloopParser.findAllMethodsInClass(document.text, classLine)
        val properties = PloopParser.findAllPropertiesInClass(document.text, classLine)

        if (methods.isEmpty() && properties.isEmpty()) {
            Messages.showInfoMessage(project, "未找到任何方法或属性。", "提示")
            return
        }

        var generatedMethods = 0
        var refreshedMethods = 0
        var generatedProps = 0
        var refreshedProps = 0

        data class Target(val kind: String, val name: String, val lineNumber: Int)
        val targets = mutableListOf<Target>()
        methods.forEach { targets.add(Target("method", it.methodName, it.lineNumber)) }
        properties.forEach { targets.add(Target("property", it.propertyName, it.lineNumber)) }

        // 从后往前处理（避免行号偏移问题）
        WriteCommandAction.runWriteCommandAction(project) {
            for (t in targets.sortedByDescending { it.lineNumber }) {
                val currentLines = document.text.lines()

                val actualLine = when (t.kind) {
                    "method" -> findMethodLine(currentLines, t.name, t.lineNumber)
                    "property" -> findPropertyLine(currentLines, t.name, t.lineNumber)
                    else -> -1
                }
                if (actualLine == -1) continue

                val methodLineText = currentLines.getOrNull(actualLine)
                val annotation = when (t.kind) {
                    "method" -> if (methodLineText != null) {
                        getAnnotationForMethod(methodLineText, actualLine, document.text)
                    } else {
                        ""
                    }
                    "property" -> getAnnotationForProperty(actualLine, document.text)
                    else -> ""
                }
                if (annotation.isBlank()) continue

                val existingRange = findExistingAnnotationRange(document, actualLine)
                if (existingRange != null) {
                    document.replaceString(existingRange.first, existingRange.second, annotation + "\n")
                    when (t.kind) {
                        "method" -> refreshedMethods++
                        "property" -> refreshedProps++
                    }
                } else {
                    val insertOffset = document.getLineStartOffset(actualLine)
                    document.insertString(insertOffset, annotation + "\n")
                    when (t.kind) {
                        "method" -> generatedMethods++
                        "property" -> generatedProps++
                    }
                }
            }
        }

        val msg = buildString {
            if (generatedProps > 0 || refreshedProps > 0) {
                append("属性: 新增 $generatedProps，刷新 $refreshedProps。 ")
            }
            if (generatedMethods > 0 || refreshedMethods > 0) {
                append("方法: 新增 $generatedMethods，刷新 $refreshedMethods。")
            }
            if (isBlank()) {
                append("没有方法/属性需要处理。")
            }
        }
        Messages.showInfoMessage(project, msg.trim(), "完成")
    }

    /**
     * 根据方法名找到实际行号
     */
    fun findMethodLine(lines: List<String>, methodName: String, hintLine: Int): Int {
        // 先检查提示行
        if (hintLine < lines.size && lines[hintLine].contains("function") && lines[hintLine].contains(methodName)) {
            return hintLine
        }
        // 在提示行附近搜索
        for (offset in 0..20) {
            val checkLine = hintLine + offset
            if (checkLine < lines.size && lines[checkLine].contains("function") && lines[checkLine].contains(methodName)) {
                return checkLine
            }
        }
        return -1
    }

    fun findPropertyLine(lines: List<String>, propertyName: String, hintLine: Int): Int {
        val pattern = Regex("""\bproperty\s+[\"']$propertyName[\"']""")

        if (hintLine < lines.size && pattern.containsMatchIn(lines[hintLine])) {
            return hintLine
        }

        // 插入注释会导致行号向下偏移，优先向下找
        for (offset in 0..80) {
            val checkLine = hintLine + offset
            if (checkLine < lines.size && pattern.containsMatchIn(lines[checkLine])) {
                return checkLine
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
