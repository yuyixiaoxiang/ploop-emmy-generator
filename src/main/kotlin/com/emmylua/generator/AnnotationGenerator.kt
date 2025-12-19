package com.emmylua.generator

object AnnotationGenerator {

    /**
     * 生成 @class、@field 和 @param 注释
     */
    fun generate(
        methodInfo: LuaMethodInfo,
        classInfo: LuaClassInfo?,
        returnType: String?,
        indent: String = ""
    ): String {
        val sb = StringBuilder()
        val className = classInfo?.className ?: "UnknownClass"
        
        // ---@class ClassName
        sb.appendLine("$indent---@class $className")
        
        // 如果第一个参数是 self，则在 @field 中忽略它
//        var paramsForField = if (methodInfo.params.firstOrNull() == "self") {
//            methodInfo.params.drop(1)
//        } else {
//            methodInfo.params
//        }
         val paramsForField = methodInfo.params

        // ---@field public MethodName fun(param:type)
        val fieldParams = paramsForField.joinToString(",") { param ->
            val type = PloopParser.inferParamType(param,className)
            "$param:$type"
        }
        val fieldReturn = if (returnType != null && returnType != "void" && returnType != "nil") ":$returnType" else ""
        sb.appendLine("$indent---@field public ${methodInfo.methodName} fun($fieldParams)$fieldReturn")
        
        // ---@param 注释（包含 self）
        methodInfo.params.forEach { param ->
//            val paramType = if (param == "self") {
//                className
//            } else {
//                PloopParser.inferParamType(param)
//            }
            val paramType = PloopParser.inferParamType(param,className)
            sb.appendLine("$indent---@param $param $paramType")
        }
        
        return sb.toString().trimEnd('\n')
    }
}
