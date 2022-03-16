package com.example.socketlib.socket

/**
 * socket 协议解析顺序
 */
@Target(
    AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER
)
@Retention(AnnotationRetention.RUNTIME)
annotation class ParseIndex(val index: Int, val isCRC: Boolean = false)
