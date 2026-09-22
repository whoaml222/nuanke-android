package com.nuanke.focus.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.nuanke.focus.BuildConfig
import com.nuanke.focus.domain.Versioning
import com.nuanke.focus.domain.UpdatePolicy
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

data class ReleaseInfo(
    val version: String,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val sha256Url: String,
)

sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data class Available(val release: ReleaseInfo) : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

sealed interface DownloadResult {
    data class Ready(val apk: File) : DownloadResult
    data class Error(val message: String) : DownloadResult
}

class ManualUpdater(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .followRedirects(true).followSslRedirects(false)
        .callTimeout(3, java.util.concurrent.TimeUnit.MINUTES)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            // Validate every redirect before sending its HTTP request, not only the final response.
            requireAllowedUrl(chain.request().url.toString())
            chain.proceed(chain.request())
        }.build()
    private val json = Json { ignoreUnknownKeys = true }

    /** This is intentionally called only from the explicit Settings-screen button handler. */
    suspend fun checkLatest(): UpdateCheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = "https://api.github.com/repos/${BuildConfig.UPDATE_REPOSITORY}/releases/latest"
            val body = getText(endpoint, "application/vnd.github+json")
            val root = json.parseToJsonElement(body).jsonObject
            val tag = root.string("tag_name")
            UpdatePolicy.requireVersion(tag.removePrefix("v"))
            val assets = root["assets"]?.jsonArray ?: JsonArray(emptyList())
            val apk = assets.findAsset { it.endsWith(".apk", ignoreCase = true) }
                ?: error("最新发布中没有 APK 文件")
            val sha = assets.findAsset { it.endsWith(".apk.sha256", ignoreCase = true) || it.endsWith(".sha256", ignoreCase = true) }
                ?: error("最新发布中没有 SHA-256 校验文件")
            val release = ReleaseInfo(
                version = tag.removePrefix("v"),
                title = root.string("name").ifBlank { tag },
                notes = root.string("body"),
                apkUrl = apk.second,
                sha256Url = sha.second,
            )
            if (Versioning.isNewer(release.version, BuildConfig.VERSION_NAME)) {
                UpdateCheckResult.Available(release)
            } else {
                UpdateCheckResult.UpToDate
            }
        }.getOrElse {
            if (it is CancellationException) throw it
            UpdateCheckResult.Error(it.message ?: "检查更新失败")
        }
    }

    suspend fun downloadAndVerify(release: ReleaseInfo): DownloadResult = withContext(Dispatchers.IO) {
        var partial: File? = null
        runCatching {
            UpdatePolicy.requireVersion(release.version)
            val updateDir = File(context.filesDir, "updates")
            check(updateDir.isDirectory || updateDir.mkdirs()) { "无法创建更新目录，请检查剩余空间" }
            // No remote path components; separate downloads cannot overwrite one another.
            val output = File.createTempFile("nuanke-", ".apk", updateDir).also { partial = it }
            val expected = HASH_PATTERN.find(getText(release.sha256Url, "text/plain"))
                ?.value
                ?.lowercase()
                ?: error("SHA-256 文件格式不正确")
            download(release.apkUrl, output)
            currentCoroutineContext().ensureActive()
            val actual = sha256(output)
            check(actual == expected) { "下载文件的 SHA-256 不匹配" }
            val candidate = apkCertificateDigests(output, release.version)
            check(candidate.isNotEmpty() && candidate == installedCertificateDigests()) {
                "更新包签名与当前安装的暖刻不一致"
            }
            DownloadResult.Ready(output)
        }.getOrElse {
            partial?.delete()
            if (it is CancellationException) throw it
            DownloadResult.Error(it.message ?: "下载或校验失败")
        }
    }

    fun installIntent(apk: File): Intent {
        if (!context.packageManager.canRequestPackageInstalls()) {
            return Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${context.packageName}".toUri(),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun getText(url: String, accept: String): String {
        requireAllowedUrl(url)
        val request = Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("User-Agent", "Nuanke/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "GitHub 返回 ${response.code}" }
            requireAllowedUrl(response.request.url.toString())
            val body = response.body ?: error("GitHub 返回空内容")
            return body.byteStream().use { input ->
                val bytes = input.readBytesLimited(4 * 1024 * 1024)
                bytes.toString(Charsets.UTF_8)
            }
        }
    }

    private suspend fun download(url: String, output: File) {
        requireAllowedUrl(url)
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "Nuanke/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "下载失败：${response.code}" }
            requireAllowedUrl(response.request.url.toString())
            val body = response.body ?: error("下载内容为空")
            output.outputStream().use { sink -> body.byteStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    check(total <= 128L * 1024 * 1024) { "更新包超出大小限制" }
                    sink.write(buffer, 0, read)
                }
            } }
        }
    }

    private fun requireAllowedUrl(raw: String) = UpdatePolicy.requireAllowedUrl(raw)

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            check(output.size() + read <= limit) { "更新信息超出大小限制" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    @Suppress("DEPRECATION")
    private fun installedCertificateDigests(): Set<String> {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val signingInfo = info.signingInfo ?: error("当前安装包没有签名")
        return signingInfo.apkContentsSigners.mapTo(mutableSetOf()) { sha256(it.toByteArray()) }
    }

    @Suppress("DEPRECATION")
    private fun apkCertificateDigests(apk: File, expectedVersion: String): Set<String> {
        val info = context.packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES,
        ) ?: error("无法读取更新包信息")
        check(info.packageName == context.packageName) { "更新包不是暖刻" }
        check(info.longVersionCode > BuildConfig.VERSION_CODE && info.versionName == expectedVersion) { "更新包版本不符或不是升级版本" }
        val signingInfo = info.signingInfo ?: error("更新包没有签名")
        return signingInfo.apkContentsSigners.mapTo(mutableSetOf()) { sha256(it.toByteArray()) }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.content.orEmpty()

    private fun JsonArray.findAsset(predicate: (String) -> Boolean): Pair<String, String>? =
        mapNotNull { element ->
            val asset = element.jsonObject
            val name = asset.string("name")
            val url = asset.string("browser_download_url")
            if (name.isNotBlank() && url.isNotBlank()) name to url else null
        }.firstOrNull { predicate(it.first) }

    companion object {
        private val HASH_PATTERN = Regex("(?i)\\b[0-9a-f]{64}\\b")
    }
}
