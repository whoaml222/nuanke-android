package com.nuanke.focus.domain

import java.net.URI

object UpdatePolicy {
    fun requireAllowedUrl(raw: String) {
        val uri = URI(raw)
        check(uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443)) { "拒绝不安全的更新地址" }
        check(uri.host?.lowercase() in setOf(
            "github.com", "api.github.com", "objects.githubusercontent.com",
            "release-assets.githubusercontent.com", "github-releases.githubusercontent.com",
        )) { "拒绝非 GitHub 发布地址" }
    }

    fun requireVersion(version: String) {
        check(Regex("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}").matches(version)) { "更新版本格式不正确" }
    }
}
