package com.homelab.app.data.repository

import android.content.Context
import com.homelab.app.R
import com.homelab.app.data.remote.SocketIoPacket
import com.homelab.app.data.remote.WebSocketChannel
import com.homelab.app.data.remote.WebSocketConnector
import com.homelab.app.data.remote.access.ForwardAuthRequiredException
import com.homelab.app.data.remote.access.SignInTracker
import com.homelab.app.domain.model.ServiceInstance
import com.homelab.app.domain.model.SignInRequiredException
import com.homelab.app.domain.model.TwoFactorInvalidException
import com.homelab.app.domain.model.TwoFactorRequiredException
import com.homelab.app.util.ServiceType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

private abstract class FakeChannel : WebSocketChannel {
    private val queue = Channel<String>(Channel.UNLIMITED)
    private var broken: IOException? = null

    fun reply(text: String) {
        queue.trySend(text)
    }

    fun break_(error: IOException) {
        broken = error
        queue.close()
    }

    override suspend fun receive(): String = queue.receiveCatching().getOrNull() ?: throw broken ?: IOException("closed")
    override fun close() {
        queue.close()
    }
}

private class FakeTrueNas : WebSocketConnector {
    var logins = 0
    var opened = 0
    var acceptsKey = true
    var loginLimit = 20
    var endpoint = "/api/current"
    var refused = emptySet<String>()
    var breaking = emptySet<String>()

    override fun open(url: String, allowSelfSigned: Boolean): WebSocketChannel {
        opened++
        val reachable = url.endsWith(endpoint)
        return object : FakeChannel() {
            override fun send(text: String) {
                if (!reachable) throw IOException("404")
                val call = Json.parseToJsonElement(text) as JsonObject
                val method = (call["method"] as JsonPrimitive).content
                fun answer(result: String) = reply("""{"jsonrpc":"2.0","id":${call["id"]},$result}""")
                when {
                    method in breaking -> break_(IOException("lost"))
                    method == "auth.login_with_api_key" ->
                        if (++logins > loginLimit) answer(""""error":{"message":"Rate Limit Exceeded"}""") else answer(""""result":$acceptsKey""")
                    method in refused -> answer(""""error":{"message":"Not authorized"}""")
                    method == "pool.query" -> answer(""""result":[{"id":1,"name":"tank","status":"ONLINE","size":100,"allocated":40}]""")
                    method == "app.query" -> answer(""""result":[{"state":"RUNNING"}]""")
                    method == "system.info" -> answer(""""result":{"version":"25.10"}""")
                    else -> answer(""""result":[]""")
                }
            }
        }
    }
}

private class FakeDockge : WebSocketConnector {
    var validToken = "jwt"
    var needsCode = false
    var signsInByItself = false
    var agents = emptyMap<String, String>()
    var dropsOn: String? = null
    var failsWith: String? = null
    val calls = mutableListOf<String>()
    var url = ""

    override fun open(url: String, allowSelfSigned: Boolean): WebSocketChannel {
        this.url = url
        return object : FakeChannel() {
            init {
                reply("""0{"sid":"a"}""")
                reply("2")
            }

            private fun afterLogin() {
                val list = (listOf("") + agents.keys).joinToString(",") { """"$it":{"endpoint":"$it"}""" }
                reply("""42["agentList",{"ok":true,"agentList":{$list}}]""")
                reply("""42["agent","stackList",{"ok":true,"endpoint":"","stackList":{"b":{"name":"b","status":3,"isManagedByDockge":true},"a":{"name":"a","status":4,"isManagedByDockge":false}}}]""")
                agents.forEach { (endpoint, status) ->
                    reply("""42["agentStatus",{"endpoint":"$endpoint","status":"connecting"}]""")
                    reply("""42["agentStatus",{"endpoint":"$endpoint","status":"$status"}]""")
                    if (status == "online") reply("""42["agent","stackList",{"ok":true,"endpoint":"$endpoint","stackList":{"remote":{"name":"remote","status":3,"isManagedByDockge":true}}}]""")
                }
            }

            override fun send(text: String) {
                if (text == "3") {
                    calls += "pong"
                    return
                }
                if (text == "40") {
                    reply("""40{"sid":"b"}""")
                    reply("""42["info",{}]""")
                    if (signsInByItself) {
                        afterLogin()
                        reply("""42["autoLogin"]""")
                    }
                    return
                }
                val digits = text.drop(2).takeWhile { it.isDigit() }
                val array = Json.parseToJsonElement(text.drop(2 + digits.length)) as JsonArray
                fun string(index: Int) = (array[index] as JsonPrimitive).content
                fun ack(json: String) = reply("43$digits[$json]")
                when (string(0)) {
                    "login" -> {
                        val fields = array[1] as JsonObject
                        val code = (fields["token"] as? JsonPrimitive)?.contentOrNull
                        ack(
                            when {
                                (fields["password"] as JsonPrimitive).content != "pw" -> """{"ok":false,"msg":"authIncorrectCreds"}"""
                                needsCode && code == null -> """{"tokenRequired":true}"""
                                needsCode && code != "123456" -> """{"ok":false,"msg":"authInvalidToken"}"""
                                else -> """{"ok":true,"token":"jwt"}"""
                            }
                        )
                    }
                    "loginByToken" -> if (string(1) == validToken) {
                        afterLogin()
                        ack("""{"ok":true}""")
                    } else {
                        ack("""{"ok":false,"msg":"authInvalidToken"}""")
                    }
                    "agent" -> {
                        calls += "${string(1)}|${string(2)}|${string(3)}"
                        when {
                            string(2) == dropsOn -> break_(IOException("lost"))
                            failsWith != null -> ack("""{"ok":false,"msg":"$failsWith"}""")
                            string(2) == "serviceStatusList" -> ack("""{"ok":true,"serviceStatusList":{"web":"running","db":{"state":"exited","ports":["5432:5432"]}}}""")
                            else -> ack("""{"ok":true}""")
                        }
                    }
                }
            }
        }
    }
}

class WebSocketServicesTest {
    private val context = mockk<Context> { every { getString(any()) } returns "text" }
    private val instances = mockk<ServiceInstancesRepository>()
    private val tracker = SignInTracker()

    private suspend inline fun <reified T : Throwable> failing(work: () -> Unit): T {
        try {
            work()
        } catch (error: Throwable) {
            if (error is T) return error
            throw error
        }
        fail("Expected ${T::class.simpleName}")
        throw AssertionError()
    }

    // MARK: - TrueNAS

    private fun nas(server: WebSocketConnector): TrueNasRepository {
        coEvery { instances.getInstance("n") } returns
            ServiceInstance("n", ServiceType.TRUENAS, "N", "https://nas.example.test", apiKey = "1-key")
        return TrueNasRepository(instances, server, Json, context)
    }

    @Test
    fun `the TrueNAS dashboard logs in once`() = runTest {
        val server = FakeTrueNas()
        server.refused = setOf("iscsi.target.query", "vm.query")
        val repository = nas(server)
        repeat(3) {
            val data = repository.getDashboard("n")
            assertEquals(listOf("tank"), data.pools.map { it.name })
            assertEquals(1, data.workloads.runningApps)
        }
        assertEquals(3, server.logins)
    }

    @Test
    fun `a broken connection skips only that method`() = runTest {
        val server = FakeTrueNas()
        server.breaking = setOf("disk.query")
        val data = nas(server).getDashboard("n")
        assertEquals(1, data.pools.size)
        assertEquals(1, data.workloads.apps)
        assertEquals(2, server.logins)
    }

    @Test
    fun `rejected keys and limits are not retried`() = runTest {
        val rejected = FakeTrueNas().apply { acceptsKey = false }
        failing<Exception> { nas(rejected).getDashboard("n") }
        assertEquals(1, rejected.logins)
        val limited = FakeTrueNas().apply { loginLimit = 0 }
        assertEquals("Rate Limit Exceeded", failing<Exception> { nas(limited).getDashboard("n") }.message)
        assertEquals(1, limited.logins)
    }

    @Test
    fun `the working endpoint is remembered`() = runTest {
        val server = FakeTrueNas().apply { endpoint = "/api/v25.04" }
        val repository = nas(server)
        repository.getDashboard("n")
        assertEquals(2, server.opened)
        repository.getDashboard("n")
        assertEquals(3, server.opened)
    }

    @Test
    fun `an expired proxy session is reported once`() = runTest {
        var opened = 0
        val server = WebSocketConnector { _, _ ->
            opened++
            object : FakeChannel() {
                override fun send(text: String) = throw ForwardAuthRequiredException()
            }
        }
        failing<ForwardAuthRequiredException> { nas(server).authenticate("https://nas.example.test", "1-key") }
        assertEquals(1, opened)
    }

    @Test
    fun `alerts are readable`() {
        val ups = TrueNasAlertText.from(
            "UPS ups is on line power.<br><br>UPS Statistics: 'ups'<br><br>Statistics recovered:<br><br>1) Battery charge (percent)<br> &nbsp;&nbsp;&nbsp; battery.charge: 95<br><br>2) Battery runtime (seconds)<br> &nbsp;&nbsp; battery.runtime: 1813<br><br>"
        )
        assertEquals("UPS ups is on line power.", ups.text)
        assertEquals(listOf("Battery charge" to "95%", "Battery runtime" to "30 min 13 s"), ups.readings)
        assertEquals("A & B\n• one\n• 5 < 6", TrueNasAlertText.from("<p>A &amp; B</p><ul><li>one</li><li>5 &lt; 6</li></ul>").text)
        assertEquals("a < b and &lt; stays", TrueNasAlertText.from("a < b and &amp;lt; stays").text)
    }

    // MARK: - Dockge

    private fun dockge(server: FakeDockge, token: String = "jwt"): DockgeRepository {
        coEvery { instances.getInstance("d") } returns
            ServiceInstance("d", ServiceType.DOCKGE, "D", "https://dockge.example.test/", token = token)
        return DockgeRepository(instances, server, tracker)
    }

    private val stack = DockgeStack("", "b", DockgeStatus.RUNNING, true)
    private val url = "https://dockge.example.test"

    @Test
    fun `socket packets are read`() {
        assertEquals(SocketIoPacket.Ping, SocketIoPacket.parse("2"))
        assertEquals(SocketIoPacket.Connected, SocketIoPacket.parse("""40{"sid":"x"}"""))
        assertEquals(SocketIoPacket.Refused("no"), SocketIoPacket.parse("""44{"message":"no"}"""))
        assertEquals(SocketIoPacket.Disconnected, SocketIoPacket.parse("41"))
        assertEquals(SocketIoPacket.Ack(12, listOf(JsonPrimitive("y"))), SocketIoPacket.parse("""4312["y"]"""))
        assertEquals(SocketIoPacket.Event("x", listOf(JsonPrimitive("y"))), SocketIoPacket.parse("""427["x","y"]"""))
        listOf("", "4", "42", "42nonsense", "43[]", "42/other,[\"x\"]", "451-[\"x\"]", "é").forEach {
            assertEquals(it, SocketIoPacket.Other, SocketIoPacket.parse(it))
        }
        assertEquals("""423["a","q\""]""", SocketIoPacket.event("a", listOf(JsonPrimitive("q\"")), 3))
    }

    @Test
    fun `stacks are listed with agents`() = runTest {
        val server = FakeDockge().apply { agents = mapOf("one.example.test" to "online", "two.example.test" to "offline") }
        val overview = dockge(server).getOverview("d")
        assertEquals(listOf("a", "b", "remote"), overview.stacks.map { it.name })
        assertEquals(listOf(false, true, true), overview.stacks.map { it.isManaged })
        assertEquals(listOf("two.example.test"), overview.offlineAgents)
        assertEquals("wss://dockge.example.test/socket.io/?EIO=4&transport=websocket", server.url)
        assertTrue("pong" in server.calls)
    }

    @Test
    fun `dockge login with and without a code`() = runTest {
        val server = FakeDockge()
        val repository = dockge(server)
        assertEquals("jwt", repository.login(url, null, "me", "pw", false))
        failing<DockgeException> { repository.login(url, null, "me", "wrong", false) }
        server.needsCode = true
        failing<TwoFactorRequiredException> { repository.login(url, null, "me", "pw", false) }
        failing<TwoFactorInvalidException> { repository.login(url, null, "me", "pw", false, "000000") }
        assertEquals("jwt", repository.login(url, null, "me", "pw", false, "123456"))
    }

    @Test
    fun `a rejected dockge session asks for sign-in`() = runTest {
        failing<SignInRequiredException> { dockge(FakeDockge(), "old").getOverview("d") }
        assertTrue(tracker.isRejected("d", "old"))
    }

    @Test
    fun `dockge with its login turned off`() = runTest {
        val server = FakeDockge().apply { signsInByItself = true }
        assertEquals(2, dockge(server, "").getOverview("d").stacks.size)
        failing<DockgeLoginRequiredException> { dockge(FakeDockge(), "").getOverview("d") }
    }

    @Test
    fun `services in both forms`() = runTest {
        val services = dockge(FakeDockge()).getServices("d", stack)
        assertEquals(listOf("db", "web"), services.map { it.name })
        assertEquals(listOf(false, true), services.map { it.isRunning })
        assertEquals(listOf("5432:5432"), services.first().ports)
    }

    @Test
    fun `actions reach the right stack`() = runTest {
        val server = FakeDockge().apply { agents = mapOf("one.example.test" to "online") }
        val repository = dockge(server)
        DockgeAction.entries.forEach { repository.perform("d", it, stack) }
        repository.perform("d", DockgeAction.RESTART, DockgeStack("one.example.test", "remote", DockgeStatus.RUNNING, true))
        assertEquals(
            listOf("|startStack|b", "|stopStack|b", "|restartStack|b", "|updateStack|b", "one.example.test|restartStack|remote"),
            server.calls.filter { it != "pong" }
        )
    }

    @Test
    fun `foreign stacks and offline agents are not touched`() = runTest {
        val server = FakeDockge().apply { agents = mapOf("two.example.test" to "offline") }
        val repository = dockge(server)
        failing<DockgeException> { repository.perform("d", DockgeAction.STOP, stack.copy(isManaged = false)) }
        failing<DockgeException> { repository.perform("d", DockgeAction.STOP, stack.copy(endpoint = "two.example.test")) }
        assertFalse(server.calls.any { it != "pong" })
    }

    @Test
    fun `a lost connection is not a failed action`() = runTest {
        val server = FakeDockge().apply { dropsOn = "updateStack" }
        assertEquals(R.string.dockge_unconfirmed, failing<DockgeException> { dockge(server).perform("d", DockgeAction.UPDATE, stack) }.messageRes)
        assertEquals(1, server.calls.count { it != "pong" })
        val failed = FakeDockge().apply { failsWith = "Failed to restart" }
        assertEquals("Failed to restart", failing<DockgeException> { dockge(failed).perform("d", DockgeAction.RESTART, stack) }.detail)
    }
}
