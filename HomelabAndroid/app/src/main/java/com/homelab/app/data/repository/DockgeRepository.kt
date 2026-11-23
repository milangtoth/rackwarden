package com.homelab.app.data.repository

import com.homelab.app.R
import com.homelab.app.data.remote.SocketIoConnection
import com.homelab.app.data.remote.SocketIoPacket
import com.homelab.app.data.remote.WebSocketConnector
import com.homelab.app.data.remote.access.SignInTracker
import com.homelab.app.domain.model.ServiceInstance
import com.homelab.app.domain.model.SignInRequiredException
import com.homelab.app.domain.model.TwoFactorChallenge
import com.homelab.app.domain.model.TwoFactorInvalidException
import com.homelab.app.domain.model.TwoFactorRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

enum class DockgeStatus { UNKNOWN, DRAFT, CREATED, RUNNING, EXITED }

data class DockgeStack(
    /** Empty for the server itself, otherwise the host of one of its agents. */
    val endpoint: String,
    val name: String,
    val status: DockgeStatus,
    /** Stacks that were not made in Dockge can only be shown. */
    val isManaged: Boolean
) {
    val id: String get() = "$endpoint|$name"
}

data class DockgeService(val name: String, val state: String, val ports: List<String>) {
    val isRunning: Boolean get() = state.lowercase() in setOf("running", "healthy")
}

data class DockgeOverview(val stacks: List<DockgeStack>, val offlineAgents: List<String>) {
    val runningCount: Int get() = stacks.count { it.status == DockgeStatus.RUNNING }
    val exitedCount: Int get() = stacks.count { it.status == DockgeStatus.EXITED }
    val endpoints: List<String> get() = stacks.map { it.endpoint }.distinct().sorted()
}

enum class DockgeAction(val event: String) {
    START("startStack"), STOP("stopStack"), RESTART("restartStack"), UPDATE("updateStack")
}

/** A failure with a text of its own, as a string resource or from the server. */
class DockgeException(val messageRes: Int?, val detail: String? = null) : Exception(detail)

/** Dockge asked for a login that was not given. */
class DockgeLoginRequiredException : Exception("Dockge needs a login")

/** Dockge only speaks Socket.IO. Its events are internal; these are from 1.5.0. */
@Singleton
class DockgeRepository @Inject constructor(
    private val instances: ServiceInstancesRepository,
    private val connector: WebSocketConnector,
    private val signInTracker: SignInTracker
) {
    private data class Server(
        val id: String?, val bases: List<String>, val token: String, val allowSelfSigned: Boolean
    )

    private fun server(url: String, fallbackUrl: String?, token: String, allowSelfSigned: Boolean, id: String? = null) =
        Server(id, listOfNotNull(url, fallbackUrl).map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.distinct(), token, allowSelfSigned)

    private suspend fun server(instanceId: String): Server {
        val instance: ServiceInstance = instances.getInstance(instanceId)
            ?: throw IllegalArgumentException("Dockge instance not found")
        return server(instance.url, instance.fallbackUrl, instance.token, instance.allowSelfSigned, instance.id)
    }

    // MARK: - Login

    /** Returns a session token. The password itself is not needed again. */
    suspend fun login(
        url: String, fallbackUrl: String?, username: String, password: String, allowSelfSigned: Boolean, code: String? = null
    ): String {
        val connection = connect(server(url, fallbackUrl, "", allowSelfSigned))
        try {
            val fields = buildJsonObject {
                put("username", username)
                put("password", password)
                code?.let { put("token", it) }
            }
            val reply = timed { connection.call("login", listOf(fields), CALL_TIMEOUT_MS) } as? JsonObject
            if (reply?.boolean("tokenRequired") == true) {
                throw TwoFactorRequiredException(TwoFactorChallenge(url, TwoFactorChallenge.Kind.Authenticator("")))
            }
            val token = reply?.text("token")
            if (reply?.boolean("ok") == true && !token.isNullOrEmpty()) return token
            throw when (val message = reply?.text("msg")) {
                "authInvalidToken" -> TwoFactorInvalidException()
                "authIncorrectCreds", null -> DockgeException(R.string.error_invalid_credentials)
                else -> DockgeException(null, message)
            }
        } finally {
            connection.close()
        }
    }

    /** Checks a session before it is saved; an empty token works when Dockge's own login is off. */
    suspend fun validate(url: String, fallbackUrl: String?, token: String, allowSelfSigned: Boolean) {
        overview(server(url, fallbackUrl, token, allowSelfSigned))
    }

    // MARK: - Reading

    suspend fun getOverview(instanceId: String): DockgeOverview = overview(server(instanceId))

    private suspend fun overview(server: Server): DockgeOverview {
        val connection = signedIn(server)
        try {
            val lists = mutableMapOf<String, List<DockgeStack>>()
            var agents: Set<String>? = null
            val offline = mutableSetOf<String>()

            fun handle(event: SocketIoPacket.Event) {
                val first = event.args.firstOrNull()
                when (event.name) {
                    "agentList" -> agents = ((first as? JsonObject)?.get("agentList") as? JsonObject)
                        ?.keys.orEmpty().filter { it.isNotEmpty() }.toSet()
                    "agentStatus" -> (first as? JsonObject)?.let { status ->
                        val endpoint = status.text("endpoint") ?: return
                        if (status.text("status") == "offline") offline += endpoint else offline -= endpoint
                    }
                    "agent" -> {
                        if ((first as? JsonPrimitive)?.contentOrNull != "stackList") return
                        val list = event.args.getOrNull(1) as? JsonObject ?: return
                        val endpoint = list.text("endpoint").orEmpty()
                        lists[endpoint] = stacks(list, endpoint) ?: return
                    }
                }
            }

            try {
                withTimeout(LIST_TIMEOUT_MS) {
                    while (lists[""] == null || agents == null) handle(connection.nextEvent())
                }
            } catch (error: TimeoutCancellationException) {
                // Servers without agents may never send a list of them.
                if (lists[""] == null) throw SocketTimeoutException()
            }
            // Agents that stay silent must not hold up the rest for long.
            withTimeoutOrNull(AGENT_TIMEOUT_MS) {
                while (agents.orEmpty().any { it !in lists && it !in offline }) handle(connection.nextEvent())
            }
            return DockgeOverview(
                lists.values.flatten().sortedWith(compareBy({ it.endpoint }, { it.name })),
                agents.orEmpty().filter { it !in lists }.sorted()
            )
        } finally {
            connection.close()
        }
    }

    private fun stacks(list: JsonObject, endpoint: String): List<DockgeStack>? =
        (list["stackList"] as? JsonObject)?.values?.mapNotNull { it as? JsonObject }?.mapNotNull { stack ->
            DockgeStack(
                endpoint = endpoint,
                name = stack.text("name") ?: return@mapNotNull null,
                status = DockgeStatus.entries.getOrElse((stack["status"] as? JsonPrimitive)?.intOrNull ?: 0) { DockgeStatus.UNKNOWN },
                isManaged = stack.boolean("isManagedByDockge") ?: false
            )
        }

    suspend fun getServices(instanceId: String, stack: DockgeStack): List<DockgeService> {
        val connection = signedIn(server(instanceId))
        try {
            waitForAgent(stack.endpoint, connection)
            val reply = timed { connection.call("agent", arguments(stack, "serviceStatusList"), CALL_TIMEOUT_MS) } as? JsonObject
            if (reply?.boolean("ok") != true) throw DockgeException(null, reply?.text("msg"))
            return (reply["serviceStatusList"] as? JsonObject).orEmpty().map { (name, value) ->
                // A plain state, or a state with its ports, depending on the version.
                val details = value as? JsonObject
                DockgeService(
                    name,
                    details?.text("state") ?: (value as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    (details?.get("ports") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                )
            }.sortedBy { it.name }
        } finally {
            connection.close()
        }
    }

    // MARK: - Actions

    /** Returns once Docker has finished, which can take minutes for an update. */
    suspend fun perform(instanceId: String, action: DockgeAction, stack: DockgeStack) {
        if (!stack.isManaged) throw DockgeException(R.string.dockge_not_managed)
        val connection = signedIn(server(instanceId))
        try {
            waitForAgent(stack.endpoint, connection)
            val reply = try {
                timed { connection.call("agent", arguments(stack, action.event), ACTION_TIMEOUT_MS) } as? JsonObject
            } catch (_: IOException) {
                // The server carries on without us, so this is not a failure of the action.
                throw DockgeException(R.string.dockge_unconfirmed)
            }
            if (reply?.boolean("ok") != true) throw DockgeException(null, reply?.text("msg"))
        } finally {
            connection.close()
        }
    }

    private fun arguments(stack: DockgeStack, event: String): List<JsonElement> =
        listOf(JsonPrimitive(stack.endpoint), JsonPrimitive(event), JsonPrimitive(stack.name))

    /** The server connects to its agents only after we sign in, and never answers for an offline one. */
    private suspend fun waitForAgent(endpoint: String, connection: SocketIoConnection) {
        if (endpoint.isEmpty()) return
        val online = withTimeoutOrNull(CALL_TIMEOUT_MS) {
            var answer: Boolean? = null
            while (answer == null) {
                val status = connection.nextEvent().takeIf { it.name == "agentStatus" }?.args?.firstOrNull() as? JsonObject
                if (status?.text("endpoint") == endpoint) {
                    answer = when (status.text("status")) {
                        "online" -> true
                        "offline" -> false
                        else -> null
                    }
                }
            }
            answer
        }
        if (online != true) throw DockgeException(R.string.dockge_agent_offline, endpoint)
    }

    // MARK: - Connecting

    private suspend fun signedIn(server: Server): SocketIoConnection {
        val connection = connect(server)
        try {
            if (server.token.isEmpty()) {
                waitForAutoLogin(connection)
            } else {
                val reply = timed { connection.call("loginByToken", listOf(JsonPrimitive(server.token)), CALL_TIMEOUT_MS) }
                if ((reply as? JsonObject)?.boolean("ok") != true) {
                    server.id?.let { signInTracker.markRejected(it, server.token) }
                    throw SignInRequiredException()
                }
            }
            return connection
        } catch (error: Exception) {
            connection.close()
            throw error
        }
    }

    /** With its own login turned off, Dockge signs everyone in by itself. */
    private suspend fun waitForAutoLogin(connection: SocketIoConnection) {
        val skipped = mutableListOf<SocketIoPacket.Event>()
        val signedIn = withTimeoutOrNull(AUTO_LOGIN_TIMEOUT_MS) {
            while (true) {
                val event = connection.nextEvent()
                if (event.name == "setup") throw DockgeException(R.string.dockge_needs_setup)
                if (event.name == "autoLogin") break
                skipped += event
            }
            true
        }
        if (signedIn != true) throw DockgeLoginRequiredException()
        // The stack list can arrive before the sign-in is announced.
        connection.putBack(skipped)
    }

    private suspend fun connect(server: Server): SocketIoConnection {
        var firstError: Exception? = null
        for (base in server.bases) {
            val connection = SocketIoConnection(connector.open(socketUrl(base), server.allowSelfSigned))
            try {
                withTimeout(CALL_TIMEOUT_MS) { connection.connect() }
                return connection
            } catch (error: Exception) {
                connection.close()
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (firstError == null) firstError = if (error is TimeoutCancellationException) SocketTimeoutException() else error
            }
        }
        throw firstError ?: IOException("No address")
    }

    /** A timeout of ours is a network problem, not a cancelled screen. */
    private suspend fun <T> timed(work: suspend () -> T): T = try {
        work()
    } catch (_: TimeoutCancellationException) {
        throw SocketTimeoutException()
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    companion object {
        private const val CALL_TIMEOUT_MS = 12_000L
        private const val LIST_TIMEOUT_MS = 15_000L
        private const val AGENT_TIMEOUT_MS = 8_000L
        private const val AUTO_LOGIN_TIMEOUT_MS = 6_000L
        private const val ACTION_TIMEOUT_MS = 900_000L

        fun socketUrl(base: String): String {
            val secure = !base.startsWith("http://", ignoreCase = true)
            return (if (secure) "wss://" else "ws://") + base.substringAfter("://").trimEnd('/') +
                "/socket.io/?EIO=4&transport=websocket"
        }
    }
}
