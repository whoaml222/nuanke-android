package com.nuanke.focus.diary

import android.app.KeyguardManager
import android.content.Intent
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import com.nuanke.focus.NuankeApplication
import com.nuanke.focus.ui.theme.NuankeTheme

/** The entire diary lives behind a system authentication gate, not just its editor. */
class DiaryActivity : ComponentActivity() {
    internal var unlocked by mutableStateOf(false)
        private set
    internal var authBusy by mutableStateOf(false)
        private set
    internal var authMessage by mutableStateOf("")
        private set
    internal var editorId by mutableStateOf<String?>(null)
    internal var photoRequest by mutableStateOf<Pair<String, List<android.net.Uri>>?>(null)
    internal var documentRequest by mutableStateOf<Pair<Boolean, android.net.Uri>?>(null)
    private var photoTarget: String? = null
    private var generation = 0
    private var cancellation: CancellationSignal? = null
    private var credentialPending = false

    private val credentials = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        authBusy = false
        if (credentialPending && result.resultCode == RESULT_OK && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            unlocked = true
            authMessage = ""
        } else authMessage = "随记仍然锁定，可以重新验证。"
        credentialPending = false
    }
    private val photos = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(DiaryEntry.MAX_PHOTOS)) { uris ->
        val target = photoTarget
        photoTarget = null
        if (target != null && uris.isNotEmpty()) photoRequest = target to uris
    }
    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) documentRequest = true to uri
    }
    private val importDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) documentRequest = false to uri
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        editorId = savedInstanceState?.getString("diaryId")
        photoTarget = savedInstanceState?.getString("photoTarget")
        val app = application as NuankeApplication
        setContent { NuankeTheme { DiaryRoot(this, app.diary, app.store) } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // No plaintext content, password, bitmap or unlocked flag enters Android saved state.
        outState.putString("diaryId", editorId)
        outState.putString("photoTarget", photoTarget)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        com.nuanke.focus.diagnostics.ServiceDiagnostics.activityVisible(this, false)
        lock()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        com.nuanke.focus.diagnostics.ServiceDiagnostics.activityVisible(this, true)
    }

    internal fun lock() {
        unlocked = false
        generation++
        cancellation?.cancel()
        cancellation = null
        if (!credentialPending) authBusy = false
    }

    internal fun authenticate() {
        if (authBusy || unlocked) return
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure) {
            authMessage = "请先在手机设置中设置锁屏密码，再使用随记。暖刻不会读取你的密码或指纹。"
            return
        }
        authBusy = true
        authMessage = ""
        val ticket = ++generation
        val builder = BiometricPrompt.Builder(this).setTitle("打开暖刻随记").setSubtitle("用指纹或手机锁屏凭据验证")
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else if (Build.VERSION.SDK_INT >= 29) {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        } else {
            builder.setNegativeButton("使用锁屏密码", mainExecutor) { _, _ -> credentialFallback() }
        }
        cancellation = CancellationSignal()
        try {
            builder.build().authenticate(cancellation!!, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (ticket != generation || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
                    authBusy = false
                    unlocked = true
                    authMessage = ""
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (ticket != generation) return
                    authBusy = false
                    if (Build.VERSION.SDK_INT == 28 && errorCode in setOf(1, 7, 9, 11, 12)) credentialFallback()
                    else authMessage = "验证未完成，内容仍然锁定。"
                }
                override fun onAuthenticationFailed() { authMessage = "未识别，请重试或使用锁屏密码。" }
            })
        } catch (_: Exception) {
            authBusy = false
            credentialFallback()
        }
    }

    @Suppress("DEPRECATION")
    private fun credentialFallback() {
        generation++
        cancellation?.cancel()
        val intent = getSystemService(KeyguardManager::class.java)
            .createConfirmDeviceCredentialIntent("打开暖刻随记", "验证手机锁屏凭据")
        if (intent == null) {
            authBusy = false
            authMessage = "请先设置手机锁屏密码。"
            return
        }
        credentialPending = true
        authBusy = true
        try { credentials.launch(intent) } catch (_: Exception) {
            credentialPending = false; authBusy = false; authMessage = "无法打开系统验证，请稍后重试。"
        }
    }

    internal fun openSecuritySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
            .onFailure { authMessage = "请手动到手机设置中设置锁屏密码。" }
    }
    internal fun choosePhotos(id: String) {
        photoTarget = id
        runCatching { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            .onFailure { photoTarget = null; authMessage = "无法打开照片选择器。" }
    }
    internal fun chooseBackup(export: Boolean) {
        runCatching {
            if (export) exportDocument.launch("暖刻随记-${java.time.LocalDate.now()}.nkdiary")
            else importDocument.launch(arrayOf("application/octet-stream", "application/zip", "*/*"))
        }.onFailure { authMessage = "无法打开文件选择器。" }
    }
    internal fun leave(destination: String) {
        lock()
        setResult(RESULT_OK, Intent().putExtra("destination", destination))
        finish()
    }
}
