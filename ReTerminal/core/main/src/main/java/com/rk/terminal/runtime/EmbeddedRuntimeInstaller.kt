package com.rk.terminal.runtime

import android.content.Context
import com.rk.libcommons.localBinDir
import com.rk.libcommons.localDir
import com.rk.libcommons.localLibDir
import com.rk.terminal.ui.screens.terminal.stat
import com.rk.terminal.ui.screens.terminal.vmstat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object EmbeddedRuntimeInstaller {
    private data class RuntimeAssetSpec(
        val outputName: String,
        val assetCandidates: List<String>
    )

    data class InstallStatus(
        val success: Boolean,
        val installed: Boolean,
        val message: String
    )

    private const val ASSET_ROOT = "embedded-terminal-runtime"
    private val runtimeAssets = listOf(
        RuntimeAssetSpec(
            outputName = "proot",
            assetCandidates = listOf("proot")
        ),
        RuntimeAssetSpec(
            outputName = "libtalloc.so.2",
            assetCandidates = listOf("libtalloc_arm.so", "libtalloc.so.2")
        ),
        RuntimeAssetSpec(
            outputName = "alpine.tar.gz",
            assetCandidates = listOf("alpine.tar.gz", "alpine.tar")
        )
    )

    suspend fun ensureRuntimeInstalled(
        context: Context,
        onProgress: suspend (String) -> Unit = {}
    ): InstallStatus = withContext(Dispatchers.IO) {
        try {
            onProgress("正在校验 Alpine 终端运行资源")
            val resolvedAssets = runtimeAssets.associateWith { spec ->
                spec.assetCandidates.firstOrNull { assetName ->
                    runCatching {
                        context.assets.open("$ASSET_ROOT/$assetName").close()
                    }.isSuccess
                }
            }
            val missingAssets = resolvedAssets.filterValues { it == null }.keys
            if (missingAssets.isNotEmpty()) {
                return@withContext InstallStatus(
                    success = false,
                    installed = false,
                    message = "缺少内置 Alpine 运行资源，请重新安装包含终端资源的构建。"
                )
            }

            onProgress("正在安装 Alpine 终端运行资源")
            runtimeAssets.forEach { spec ->
                val assetName = resolvedAssets.getValue(spec)
                    ?: error("Missing runtime asset mapping for ${spec.outputName}")
                val target = File(context.filesDir, spec.outputName)
                if (!target.exists() || target.length() == 0L) {
                    context.assets.open("$ASSET_ROOT/$assetName").use { input ->
                        target.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                target.setReadable(true, false)
                if (spec.outputName == "proot") {
                    target.setExecutable(true, false)
                }
            }

            // ReTerminal init-host.sh expects these runtime helpers to exist under $PREFIX/local.
            localDir().mkdirs()
            localBinDir().mkdirs()
            localLibDir().mkdirs()
            File(localDir(), "stat").writeText(stat)
            File(localDir(), "vmstat").writeText(vmstat)

            InstallStatus(
                success = true,
                installed = true,
                message = "Alpine 终端运行资源已就绪。"
            )
        } catch (error: Exception) {
            InstallStatus(
                success = false,
                installed = false,
                message = error.message ?: "安装 Alpine 终端运行资源失败。"
            )
        }
    }
}
