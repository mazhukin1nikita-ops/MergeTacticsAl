package com.mergetacticsai.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object Logger {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines
    fun log(msg: String) { _lines.value = (listOf(msg) + _lines.value).take(200) }
    fun clear() { _lines.value = emptyList() }
}
