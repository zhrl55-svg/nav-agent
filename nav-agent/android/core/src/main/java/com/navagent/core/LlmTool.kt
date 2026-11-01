package com.navagent.core

/**
 * 模型可调用的工具（Function Calling）。
 *
 * 为什么走工具调用而不是关键词匹配：
 * 「明天冷不冷」「张掖天气咋样」「用不用带伞」说的是一件事，规则穷举不完；
 * 交给模型自己判断要不要查、查什么城市，一句 prompt 的事。
 *
 * 纪律：工具只读、无副作用、同步阻塞（调用方保证在子线程）。
 * 失败也必须返回一句人能读懂的话，不要把异常抛给模型。
 */
interface LlmTool {

    /** 模型看到的函数名。英文小写下划线，别用中文 —— 有些模型会对非 ASCII 函数名发疯 */
    val name: String

    /** 模型看到的能力说明。写清楚"什么时候该用"，比写"这个函数干什么"更有用 */
    val description: String

    /** 参数 JSON Schema。直接拼成字符串塞进请求体 */
    fun parametersJson(): String

    /**
     * 执行。调用方保证在子线程。
     * @param argumentsJson 模型给的参数，可能为空串或残缺 JSON，实现方要自己容错
     * @return 给模型看的结果文本
     */
    fun execute(argumentsJson: String): String
}
