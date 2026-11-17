package com.cardpersonality.honor.hook.api

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.reflect.Member

/**
 * Round-22: internal hook API (API-102 only).
 *
 * The module is now a pure libxposed API 102 module (META-INF/xposed metadata); the old legacy
 * `de.robv.android.xposed` shim and `assets/xposed_init` entry are gone. This package is the
 * in-module equivalent of the small legacy surface the engines use (HookBridge/HookHelpers/
 * MethodHook/MethodHookParam/MethodReplacement), implemented directly on libxposed API 102.
 */
object HookBridge {
    private const val TAG = "CardPersonality"

    @Volatile
    var module: XposedModule? = null
        private set

    @Volatile
    var processName: String = ""
        private set

    fun attach(value: XposedModule) {
        module = value
    }

    fun setProcessName(value: String?) {
        processName = value ?: ""
    }

    fun isAttached(): Boolean = module != null

    /** Hooks [member] with [callback] via module.hook(executable).intercept(...). */
    fun hookMethod(member: Member, callback: MethodHook): MethodHook.Unhook? {
        val m = module ?: return null
        if (member !is Executable) return null
        return try {
            val handle = m.hook(member).intercept { chain -> intercept(callback, chain) }
            callback.createUnhook(member, handle)
        } catch (t: Throwable) {
            log("hookMethod failed $member :: $t")
            null
        }
    }

    fun log(text: String) {
        try {
            module?.log(Log.INFO, TAG, text) ?: Log.i(TAG, text)
        } catch (_: Throwable) {
        }
    }

    fun log(t: Throwable) {
        log(Log.getStackTraceString(t))
    }

    /** Chain → MethodHookParam: before/after hooks, setResult early-return, result/exception. */
    fun intercept(callback: MethodHook, chain: XposedInterface.Chain): Any? {
        val param = MethodHook.MethodHookParam()
        param.method = chain.executable
        param.thisObject = chain.thisObject
        param.args = chain.args.toTypedArray()

        if (callback is MethodReplacement) {
            return callback.invokeReplacement(param)
        }

        try {
            callback.callBefore(param)
        } catch (t: Throwable) {
            log("before hook error: $t")
        }
        if (!param.returnEarly) {
            try {
                param.result = chain.proceed(param.args)
                param.returnEarly = false
            } catch (t: Throwable) {
                param.throwable = t
                param.returnEarly = true
            }
        }
        try {
            callback.callAfter(param)
        } catch (t: Throwable) {
            log("after hook error: $t")
        }
        param.throwable?.let { throw it }
        return param.result
    }
}

/**
 * Legacy-like method hook callback: override [beforeHookedMethod] / [afterHookedMethod];
 * [MethodHookParam.setResult] in before skips the original call.
 */
open class MethodHook {
    open fun beforeHookedMethod(param: MethodHookParam?) {}

    open fun afterHookedMethod(param: MethodHookParam?) {}

    fun callBefore(param: MethodHookParam) = beforeHookedMethod(param)

    fun callAfter(param: MethodHookParam) = afterHookedMethod(param)

    fun createUnhook(member: Member, handle: Any?): Unhook = Unhook(member, handle)

    class MethodHookParam {
        var method: Member? = null
        var thisObject: Any? = null
        var args: Array<Any?> = emptyArray()
        @JvmField var result: Any? = null
        @JvmField var throwable: Throwable? = null
        var returnEarly: Boolean = false

        fun getResult(): Any? = result

        fun setResult(value: Any?) {
            result = value
            throwable = null
            returnEarly = true
        }

        fun getThrowable(): Throwable? = throwable

        fun hasThrowable(): Boolean = throwable != null

        fun setThrowable(t: Throwable?) {
            throwable = t
        }
    }

    class Unhook(private val member: Member?, private val handle: Any?) {
        fun unhook() {
            try {
                (handle as? XposedInterface.HookHandle)?.unhook()
            } catch (_: Throwable) {
            }
        }

        fun getMember(): Member? = member
    }
}

/** Replacement hook (legacy XC_MethodReplacement equivalent). */
abstract class MethodReplacement : MethodHook() {
    protected abstract fun replaceHookedMethod(param: MethodHookParam?): Any?

    fun invokeReplacement(param: MethodHookParam?): Any? = replaceHookedMethod(param)
}

/** Small helper surface used by the engines (API-102 only). */
object HookHelpers {
    fun findAndHookMethod(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypesAndCallback: Any?,
    ): MethodHook.Unhook? {
        if (parameterTypesAndCallback.isEmpty()) return null
        val callback = parameterTypesAndCallback.last() as? MethodHook ?: return null
        val types = parameterTypesAndCallback.dropLast(1).map { resolveType(it) }.toTypedArray()
        var cls: Class<*>? = clazz
        while (cls != null) {
            try {
                val method = cls.getDeclaredMethod(methodName, *types)
                method.isAccessible = true
                return HookBridge.hookMethod(method, callback)
            } catch (_: Throwable) {
                cls = cls.superclass
            }
        }
        return null
    }

    fun findAndHookMethod(
        className: String,
        classLoader: ClassLoader,
        methodName: String,
        vararg parameterTypesAndCallback: Any?,
    ): MethodHook.Unhook? {
        return try {
            findAndHookMethod(Class.forName(className, false, classLoader), methodName, *parameterTypesAndCallback)
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveType(value: Any?): Class<*> = when (value) {
        is Class<*> -> value
        is String -> when (value) {
            "int" -> Int::class.javaPrimitiveType!!
            "long" -> Long::class.javaPrimitiveType!!
            "boolean" -> Boolean::class.javaPrimitiveType!!
            "float" -> Float::class.javaPrimitiveType!!
            "double" -> Double::class.javaPrimitiveType!!
            "byte" -> Byte::class.javaPrimitiveType!!
            "char" -> Char::class.javaPrimitiveType!!
            "short" -> Short::class.javaPrimitiveType!!
            "void" -> Void.TYPE
            else -> Class.forName(value)
        }
        else -> Any::class.java
    }
}
