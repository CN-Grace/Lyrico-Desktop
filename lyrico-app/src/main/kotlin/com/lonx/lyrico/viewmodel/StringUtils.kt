package com.lonx.lyrico.viewmodel


/**
 * 比较两个字符串是否相等，将 null 和空字符串视为相等
 */
fun String?.isEqualIgnoringBlank(other: String?): Boolean {
    val thisBlank = this.isNullOrBlank()
    val otherBlank = other.isNullOrBlank()
    return if (thisBlank && otherBlank) {
        true
    } else if (!thisBlank && !otherBlank) {
        this == other
    } else {
        false
    }
}