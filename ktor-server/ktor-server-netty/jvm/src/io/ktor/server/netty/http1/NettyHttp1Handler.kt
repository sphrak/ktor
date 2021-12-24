/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.netty.http1

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.netty.cio.*
import io.ktor.util.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.internal.*
import io.netty.buffer.*
import io.netty.channel.*
import io.netty.handler.codec.http.*
import io.netty.util.concurrent.*
import kotlinx.coroutines.*
import java.io.*
import java.nio.channels.*
import java.util.*
import java.util.concurrent.atomic.*
import kotlin.coroutines.*

internal class NettyHttp1Handler(
    private val enginePipeline: EnginePipeline,
    private val environment: ApplicationEngineEnvironment,
    private val callEventGroup: EventExecutorGroup,
    private val engineContext: CoroutineContext,
    private val userContext: CoroutineContext
) : ChannelInboundHandlerAdapter(), CoroutineScope {
    private val handlerJob = CompletableDeferred<Nothing>()

    override val coroutineContext: CoroutineContext get() = handlerJob

    private var skipEmpty = false

    private val isReadComplete = AtomicBoolean(false)

    lateinit var responseWriter: NettyResponsePipeline

    private var currentRequest: ByteReadChannel? = null

    @OptIn(InternalAPI::class)
    override fun channelActive(context: ChannelHandlerContext) {
        val responseQueue: Queue<NettyApplicationCall> = ArrayDeque()

        val requestBodyHandler = RequestBodyHandler(context, responseQueue)
        responseWriter = NettyResponsePipeline(context, WriterEncapsulation.Http1, coroutineContext, responseQueue, isReadComplete)

        context.pipeline().apply {
            addLast(requestBodyHandler)
            addLast(callEventGroup, NettyApplicationCallHandler(userContext, enginePipeline, environment.log))
        }
        context.fireChannelActive()
    }

    override fun channelRead(context: ChannelHandlerContext, message: Any) {
        if (message is HttpRequest) {
            handleRequest(context, message)
        } else if (message is LastHttpContent && !message.content().isReadable && skipEmpty) {
            skipEmpty = false
            message.release()
        } else {
            context.fireChannelRead(message)
        }
    }

    override fun channelInactive(context: ChannelHandlerContext) {
        context.pipeline().remove(NettyApplicationCallHandler::class.java)
        context.fireChannelInactive()
    }

    @Suppress("OverridingDeprecatedMember")
    override fun exceptionCaught(context: ChannelHandlerContext, cause: Throwable) {
        if (cause is IOException || cause is ChannelIOException) {
            environment.application.log.debug("I/O operation failed", cause)
            handlerJob.cancel()
        } else {
            handlerJob.completeExceptionally(cause)
        }
        context.close()
    }

    override fun channelReadComplete(context: ChannelHandlerContext?) {
        isReadComplete.set(true)
        responseWriter.markReadingStopped()
        super.channelReadComplete(context)
    }

    private fun handleRequest(context: ChannelHandlerContext, message: HttpRequest) {
        val call = getNettyApplicationCall(context, message)

        context.fireChannelRead(call)
        responseWriter.processResponse(call)
    }

    private fun getNettyApplicationCall(
        context: ChannelHandlerContext,
        message: HttpRequest
    ): NettyHttp1ApplicationCall {
        val requestBodyChannel = when {
            message is LastHttpContent && !message.content().isReadable -> null
            message.method() === HttpMethod.GET &&
                !HttpUtil.isContentLengthSet(message) && !HttpUtil.isTransferEncodingChunked(message) -> {
                skipEmpty = true
                null
            }
            else -> handleContent(context, message)
        }?.also {
            currentRequest = it
        }

        return NettyHttp1ApplicationCall(
            environment.application,
            context,
            message,
            requestBodyChannel,
            engineContext,
            userContext
        )
    }

    private fun handleContent(context: ChannelHandlerContext, message: HttpRequest): ByteReadChannel {
        return when (message) {
            is HttpContent -> {
                val bodyHandler = context.pipeline().get(RequestBodyHandler::class.java)
                bodyHandler.newChannel().also { bodyHandler.channelRead(context, message) }
            }
            else -> {
                val bodyHandler = context.pipeline().get(RequestBodyHandler::class.java)
                bodyHandler.newChannel()
            }
        }
    }
}
