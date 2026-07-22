package io.switchlite.adapter.common.api

import java.lang.invoke.MethodHandle

/**
 * Mapping context interface for resolving method/field handles.
 */
interface IMappingContext {
    fun resolve(key: String): MethodHandle?
}
