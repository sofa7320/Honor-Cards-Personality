package com.cardpersonality.honor.hook

import com.cardpersonality.honor.hook.api.MethodHook

/** Shared helper: install a hook and log/track it, never throwing. */
internal fun hookLogged(
    label: String,
    block: () -> MethodHook.Unhook?,
): MethodHook.Unhook? {
    return try {
        val h = block()
        if (h != null) HLog.i("hooked $label") else HLog.w("hook target not found: $label")
        h
    } catch (t: Throwable) {
        HLog.e("hook failed: $label", t)
        null
    }
}

/** Convenience wrapper for an after-call hook with full fail-open protection. */
internal fun safeAfter(
    label: String,
    body: (MethodHook.MethodHookParam) -> Unit,
): MethodHook = object : MethodHook() {
    override fun afterHookedMethod(param: MethodHookParam?) {
        if (param == null) return
        try {
            body(param)
        } catch (t: Throwable) {
            HLog.e("$label after-hook error", t)
        }
    }
}

/**
 * Finds a method by predicate inside a wallet class, walking superclasses too
 * (some app adapters inherit bind methods instead of declaring them).
 */
internal fun findWalletMethod(
    cl: ClassLoader,
    className: String,
    predicate: (java.lang.reflect.Method) -> Boolean,
): java.lang.reflect.Method? {
    return try {
        var cls: Class<*>? = Class.forName(className, false, cl)
        var depth = 0
        while (cls != null && depth < 8) {
            cls.declaredMethods.firstOrNull(predicate)?.let { method ->
                method.isAccessible = true
                HLog.i("found method on ${cls!!.name}: ${method.name}(${method.parameterTypes.joinToString(",") { it.name }})")
                return method
            }
            cls = cls.superclass
            depth++
        }
        null
    } catch (t: Throwable) {
        HLog.w("class lookup failed: $className (${t.javaClass.simpleName}: ${t.message})")
        null
    }
}
