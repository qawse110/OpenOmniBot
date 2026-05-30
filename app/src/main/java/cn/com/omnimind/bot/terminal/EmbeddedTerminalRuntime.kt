package cn.com.omnimind.bot.terminal

import android.content.Context
import android.os.Build
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.termux.TermuxCommandBuilder
import cn.com.omnimind.bot.termux.TermuxLiveUpdate
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.provider.type.HiddenExecResult
import com.rk.terminal.runtime.AlpineRepositoryManager
import com.rk.terminal.App
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object EmbeddedTerminalRuntime {
    data class EnvironmentProgress(
        val kind: Kind,
        val message: String
    ) {
        enum class Kind {
            STATUS,
            OUTPUT,
            ERROR
        }
    }

    data class EnvironmentStatus(
        val success: Boolean,
        val initialized: Boolean,
        val basePackagesReady: Boolean,
        val message: String
    )

    data class RuntimeReadinessStatus(
        val supported: Boolean,
        val runtimeReady: Boolean,
        val basePackagesReady: Boolean,
        val missingCommands: List<String>,
        val message: String,
        val nodeReady: Boolean,
        val nodeVersion: String?,
        val nodeMinMajor: Int,
        val pnpmReady: Boolean,
        val pnpmVersion: String?
    )

    data class CommandResult(
        val success: Boolean,
        val timedOut: Boolean,
        val exitCode: Int?,
        val output: String,
        val errorMessage: String?,
        val sessionId: String,
        val rawExtras: Map<String, Any?> = emptyMap()
    )

    data class SessionStartResult(
        val sessionId: String,
        val currentDirectory: String,
        val transcript: String
    )

    data class SessionCommandResult(
        val sessionId: String,
        val completed: Boolean,
        val success: Boolean,
        val exitCode: Int?,
        val timedOut: Boolean = false,
        val output: String,
        val transcript: String,
        val currentDirectory: String,
        val errorMessage: String? = null
    )

    data class SessionReadResult(
        val sessionId: String,
        val transcript: String,
        val currentDirectory: String,
        val commandRunning: Boolean
    )

    data class BackgroundServiceLaunchResult(
        val sessionId: String,
        val started: Boolean,
        val alreadyRunning: Boolean,
        val currentDirectory: String,
        val transcript: String,
        val message: String
    )

    internal data class SessionLiveOutputUpdate(
        val visibleOutput: String,
        val outputDelta: String,
        val exitCode: Int?
    )

    private data class SessionHandle(
        val externalSessionId: String,
        val mutex: Mutex = Mutex(),
        @Volatile var activeCommandToken: String? = null
    )

    private data class BasePackageProbeResult(
        val missingCommands: List<String> = emptyList(),
        val errorMessage: String? = null,
        val nodeReady: Boolean = false,
        val nodeVersion: String? = null,
        val nodeMajor: Int? = null,
        val pnpmReady: Boolean = false,
        val pnpmVersion: String? = null
    )

    private const val PREFS_NAME = "embedded_terminal_runtime"
    private const val KEY_BASE_PACKAGE_VERSION = "base_package_version"
    private const val BASE_PACKAGE_VERSION = 2
    private const val SESSION_DONE_PREFIX = "__OMNIBOT_SESSION_DONE__"
    private const val DEFAULT_CURRENT_DIRECTORY = AgentWorkspaceManager.SHELL_ROOT_PATH
    private const val BASE_PACKAGE_READY_MARKER = "__OMNIBOT_BASE_PACKAGES_READY__"
    private const val BASE_PACKAGE_MISSING_MARKER = "__OMNIBOT_BASE_PACKAGES_MISSING__"
    private const val BASE_PACKAGE_NODE_VERSION_MARKER = "__OMNIBOT_NODE_VERSION__"
    private const val BASE_PACKAGE_PNPM_VERSION_MARKER = "__OMNIBOT_PNPM_VERSION__"
    private const val NODE_MIN_MAJOR = 22
    private val terminalEnvKeyPattern = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    private const val SESSION_HELPER_SCRIPT_NAME = "omnikot-session-lib.sh"

    private val sessionHandles = ConcurrentHashMap<String, SessionHandle>()
    private val packageInstallMutex = Mutex()
    private val requiredCliCommands = listOf(
        "bash",
        "curl",
        "fuser",
        "git",
        "node",
        "npm",
        "pkill",
        "python",
        "python3",
        "pip3",
        "rg",
        "tmux",
        "uv",
        "xz"
    )

    private val ansiEscapeRegex = Regex("""\u001B(?:\[[0-9?]*[ -/]*[@-~]|\([A-Za-z0-9])""")
    private val knownNoiseRegexes = listOf(
        Regex("""^Warning: CPU doesn't support 32-bit instructions, some software may not work\.$"""),
        Regex("""^proot warning: can't sanitize binding "/proc/self/fd/\d+": No such file or directory$""")
    )
    private val shellPromptRegex = Regex("""^[^\r\n]*[#$] $""")

    private fun buildBasePackageBootstrapCommand(): String {
        return """
            ${AlpineRepositoryManager.buildSelectedRepositorySetupCommand()}
            export PATH="${'$'}HOME/.local/bin:${'$'}PATH"
            apk update &&
            apk add --no-cache \
              bash \
              ca-certificates \
              curl \
              gcompat \
              git \
              glib \
              nodejs \
              npm \
              procps \
              psmisc \
              python3 \
              py3-pip \
              py3-virtualenv \
              ripgrep \
              tmux \
              xz && \
            ln -sf /usr/bin/python3 /usr/local/bin/python || true && \
            python3 -m pip install --upgrade pip >/dev/null 2>&1 || true && \
            python3 -m pip install --upgrade uv >/dev/null 2>&1 || true && \
            npm install -g pnpm --no-audit --no-fund >/dev/null 2>&1 || true && \
            if [ -x "${'$'}HOME/.local/bin/uv" ]; then ln -sf "${'$'}HOME/.local/bin/uv" /usr/local/bin/uv; fi && \
            if [ -x "${'$'}HOME/.local/bin/uvx" ]; then ln -sf "${'$'}HOME/.local/bin/uvx" /usr/local/bin/uvx; fi
        """.trimIndent()
    }

    fun isSupportedDevice(): Boolean {
        return Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "armeabi-v7a" }
    }

    // --- rest of the file unchanged beyond this point ---
