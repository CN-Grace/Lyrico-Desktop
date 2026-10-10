package com.lonx.lyrico.utils

sealed interface RenameToken

data class TextToken(
    val text: String
) : RenameToken

data class FieldToken(
    val field: TagField
) : RenameToken
