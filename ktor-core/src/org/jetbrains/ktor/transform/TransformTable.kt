package org.jetbrains.ktor.transform

import org.jetbrains.ktor.util.*
import java.util.*
import java.util.concurrent.atomic.*
import java.util.concurrent.locks.*
import kotlin.concurrent.*

class TransformTable<TContext : Any>(val parent: TransformTable<TContext>? = null) {
    private val topParent: TransformTable<TContext> = parent?.topParent ?: parent ?: this
    private val handlersCounter: AtomicInteger = parent?.handlersCounter ?: AtomicInteger()

    private val superTypesCacheLock = ReentrantReadWriteLock()
    private val superTypesCache = HashMap<Class<*>, Array<Class<*>>>()

    private val handlersLock = ReentrantReadWriteLock()
    private val handlers = HashMap<Class<*>, MutableList<Handler<TContext, *>>>()

    private val handlersCacheLock = ReentrantReadWriteLock()
    private val handlersCache = HashMap<Class<*>, List<Handler<TContext, *>>>()

    inline fun <reified T : Any> register(noinline handler: suspend TContext.(T) -> Any) {
        register({ true }, handler)
    }

    inline fun <reified T : Any> register(noinline predicate: TContext.(T) -> Boolean, noinline handler: suspend TContext.(T) -> Any) {
        register(T::class.javaObjectType, predicate, handler)
    }

    fun <T : Any> register(type: Class<T>, predicate: TContext.(T) -> Boolean, handler: suspend TContext.(T) -> Any) {
        topParent.superTypes(type)
        addHandler(type, Handler(handlersCounter.getAndIncrement(), predicate, handler))

        handlersCacheLock.write {
            handlersCache.keys.filter { type.isAssignableFrom(it) }.forEach {
                handlersCache.remove(it)
            }
        }
    }

    fun <T : Any> handlers(type: Class<out T>): List<Handler<TContext, T>> {
        val cached = handlersCacheLock.read { handlersCache[type] }
        val partialResult = if (cached == null) {
            val collected = collectHandlers(type)

            handlersCacheLock.write {
                handlersCache[type] = collected
            }

            collected
        } else {
            @Suppress("UNCHECKED_CAST")
            cached as List<Handler<TContext, T>>
        }

        return if (parent != null) {
            val parentResult = parent.handlers(type)
            when {
                parentResult.isEmpty() -> partialResult
                partialResult.isEmpty() -> parentResult
                else -> partialResult + parentResult
            }
        } else
            partialResult
    }

    suspend fun transform(ctx: TContext, obj: Any): Any {
        val visited: TransformTable.HandlersSet<TContext> = newHandlersSet()
        var value: Any = obj
        var handlers = handlers(obj::class.java)

        nextValue@ while (true) {
            for (i in 0..handlers.lastIndex) {
                val handler = handlers[i]

                if (handler in visited || !handler.predicate(ctx, value))
                    continue

                val result = handler.handler(ctx, value)
                if (result === value)
                    continue

                visited.add(handler)
                if (result::class.java !== value::class.java) {
                    handlers = handlers(result::class.java)
                }
                value = result
                continue@nextValue
            }
            break
        }

        return value
    }

    class Handler<in C : Any, in T> internal constructor(val id: Int, val predicate: C.(T) -> Boolean, val handler: suspend C.(T) -> Any) {
        override fun toString() = handler.toString()
    }

    fun newHandlersSet() = HandlersSet<TContext>()

    class HandlersSet<out C : Any> {
        private val bitSet = BitSet()

        fun add(element: Handler<C, *>): Boolean {
            if (bitSet[element.id]) {
                return false
            }

            bitSet[element.id] = true
            return true
        }

        fun remove(element: Handler<C, *>): Boolean {
            if (bitSet[element.id]) {
                bitSet[element.id] = false
                return true
            }

            return false
        }

        operator fun contains(element: Handler<C, *>) = bitSet[element.id]
    }

    private fun <T : Any> collectHandlers(type: Class<out T>): List<Handler<TContext, T>> {
        val result = ArrayList<Handler<TContext, T>>(2)
        val superTypes = topParent.superTypes(type)

        handlersLock.read {
            for (superType in superTypes) {
                val hh = handlers[superType]
                if (hh != null && hh.isNotEmpty()) {
                    @Suppress("UNCHECKED_CAST")
                    result.addAll(hh as List<Handler<TContext, T>>)
                }
            }
        }

        return if (result.isEmpty()) emptyList() else result
    }

    private fun addHandler(type: Class<*>, handler: Handler<TContext, *>) {
        handlersLock.write {
            handlers.getOrPut(type) { ArrayList(2) }.add(handler)
        }
    }

    private fun superTypes(type: Class<*>): Array<Class<*>> = superTypesCacheLock.read {
        superTypesCache[type] ?: superTypesCacheLock.write {
            buildSuperTypes(type).also { superTypesCache[type] = it }
        }
    }

    private fun buildSuperTypes(type: Class<*>) = type.findAllSupertypes().asReversed().toTypedArray()
}

