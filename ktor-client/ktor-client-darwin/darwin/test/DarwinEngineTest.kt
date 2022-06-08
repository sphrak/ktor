import io.ktor.client.*
import io.ktor.client.engine.darwin.*
import io.ktor.client.engine.darwin.internal.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.tests.utils.*
import io.ktor.http.*
import kotlinx.coroutines.*
import platform.Foundation.*
import platform.Foundation.NSHTTPCookieStorage.Companion.sharedHTTPCookieStorage
import kotlin.test.*

/*
 * Copyright 2014-2022 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

class DarwinEngineTest {

    @Test
    fun testRequestInRunBlocking() = runBlocking {
        val client = HttpClient(Darwin)

        try {
            withTimeout(1000) {
                val response = client.get(TEST_SERVER)
                assertEquals("Hello, world!", response.bodyAsText())
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun testQueryWithCyrillic() = runBlocking {
        val client = HttpClient(Darwin)

        try {
            withTimeout(1000) {
                val response = client.get("$TEST_SERVER/echo_query?привет")
                assertEquals("привет=[]", response.bodyAsText())
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun testQueryWithMultipleParams() = runBlocking {
        val client = HttpClient(Darwin)

        try {
            withTimeout(1000) {
                val response = client.get("$TEST_SERVER/echo_query?asd=qwe&asd=123&qwe&zxc=vbn")
                assertEquals("asd=[qwe, 123], qwe=[], zxc=[vbn]", response.bodyAsText())
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun testNSUrlSanitize() {
        assertEquals(
            "http://127.0.0.1/echo_query?%D0%BF%D1%80%D0%B8%D0%B2%D0%B5%D1%82",
            stringToNSUrlString("http://127.0.0.1/echo_query?привет")
        )

        assertEquals(
            "http://%D0%BF%D1%80%D0%B8%D0%B2%D0%B5%D1%82.%D0%BF%D1%80%D0%B8%D0%B2%D0%B5%D1%82/",
            stringToNSUrlString("http://привет.привет/")
        )
    }

    @Test
    fun testCookieIsNotPersistedByDefault() = runBlocking {
        val client = HttpClient(Darwin)
        try {
            client.get("$TEST_SERVER/cookies")
            val result = client.get("$TEST_SERVER/cookies/dump")
                .bodyAsText()

            assertEquals("Cookies: ", result)
        } finally {
            client.close()
        }
    }

    @Test
    fun testCookiePersistedWithSessionStore() = runBlocking {
        val client = HttpClient(Darwin) {
            engine {
                configureSession {
                    setHTTPCookieStorage(sharedHTTPCookieStorage)
                }
            }
        }

        try {
            client.get("$TEST_SERVER/cookies")
            val result = client.get("$TEST_SERVER/cookies/dump")
                .bodyAsText()

            assertEquals("Cookies: hello-cookie=my%2Dawesome%2Dvalue", result)
        } finally {
            client.close()
        }
    }

    @Test
    fun testOverrideDefaultSession(): Unit = runBlocking {
        val client = HttpClient(Darwin) {
            engine {
                usePreconfiguredSession(MySession())
            }
        }

        var failedCause: Throwable? = null

        try {
            client.get(TEST_SERVER)
        } catch (cause: IllegalStateException) {
            failedCause = cause
        } finally {
            client.close()
        }

        assertEquals("It works", failedCause?.message)
    }

    private fun stringToNSUrlString(value: String): String {
        return Url(value).toNSUrl().absoluteString!!
    }
}

class MySession : NSURLSession() {
    override fun dataTaskWithRequest(request: NSURLRequest): NSURLSessionDataTask {
        error("It works")
    }
}
