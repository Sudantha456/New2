package com.app.youtube.lite.auth

import android.webkit.CookieManager
import androidx.compose.runtime.Immutable
import com.app.youtube.lite.core.net.LiteCookieJar
import com.app.youtube.lite.core.util.L
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/** What the rest of the app needs to know about the session. */
@Immutable
sealed interface AuthState {
    /** Signed out: InnerTube still works, just anonymously. */
    data object Anonymous : AuthState

    @Immutable
    data class LoggedIn(val cookies: SessionCookies) : AuthState
}

/**
 * Owns the YouTube session for the whole process.
 *
 * Responsibilities:
 *  • single source of truth for the session state ([state]),
 *  • persistence through [SecureStore] (keystore-encrypted),
 *  • request signing: `SAPISIDHASH` headers and `Cookie:` headers,
 *  • installation of the cookies into the shared [LiteCookieJar] so that *every* OkHttp
 *    request — including those made deep inside NewPipeExtractor — is authenticated.
 *
 * The SAPISIDHASH header is regenerated per request because YouTube time-boxes it; nothing
 * about the session is cached in a way that can go stale.
 */
class AuthManager(
    private val store: SecureStore,
    private val cookieJar: LiteCookieJar,
    private val json: Json = Json,
) {

    private val _state = MutableStateFlow<AuthState>(AuthState.Anonymous)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    init {
        restore()
    }

    val isLoggedIn: Boolean
        get() = _state.value is AuthState.LoggedIn

    val cookies: SessionCookies?
        get() = (_state.value as? AuthState.LoggedIn)?.cookies

    /** Re-reads the persisted session (called once at construction). */
    fun restore() {
        val raw = store.getString(SecureStore.KEY_SESSION) ?: return
        val decoded = runCatching { json.decodeFromString<SessionCookies>(raw) }.getOrNull()
        if (decoded == null || !decoded.isComplete) {
            L.w("Auth") { "persisted session missing or incomplete — clearing" }
            store.put(SecureStore.KEY_SESSION, null)
            return
        }
        install(decoded.copy(visitorData = store.getString(SecureStore.KEY_VISITOR_DATA) ?: decoded.visitorData))
        L.i("Auth") { "restored session for SAPISID …${decoded.sapisid.takeLast(4)}" }
    }

    /** Called by the login flow once all six cookies have been captured. */
    fun save(cookies: SessionCookies) {
        if (!cookies.isComplete) {
            L.w("Auth") { "refusing to store an incomplete session" }
            return
        }
        store.put(SecureStore.KEY_SESSION, json.encodeToString(cookies))
        install(cookies)
        L.i("Auth") { "session stored (SAPISID …${cookies.sapisid.takeLast(4)})" }
    }

    /** Remembers a visitor id learned from InnerTube so it survives process death. */
    fun updateVisitorData(visitorData: String?) {
        if (visitorData.isNullOrBlank()) return
        store.put(SecureStore.KEY_VISITOR_DATA, visitorData)
        val current = cookies ?: return
        if (current.visitorData != visitorData) install(current.copy(visitorData = visitorData))
    }

    fun visitorData(): String? = cookies?.visitorData ?: store.getString(SecureStore.KEY_VISITOR_DATA)

    /**
     * Clears the session everywhere: encrypted store, cookie jar, and the WebView cookie
     * database (so the in-app login screen does not silently resume a stale account).
     */
    fun logout() {
        store.clear()
        cookieJar.setSessionCookies(emptyList())
        runCatching {
            val manager = CookieManager.getInstance()
            manager.removeAllCookies(null)
            manager.flush()
        }.onFailure { L.w("Auth") { "CookieManager cleanup failed: $it" } }
        _state.value = AuthState.Anonymous
        L.i("Auth") { "logged out" }
    }

    /** `Authorization:` header for an authenticated InnerTube call, or `null` when signed out. */
    fun authorizationHeader(origin: String = SapisidHash.YOUTUBE_ORIGIN): String? {
        val sapisid = cookies?.sapisid ?: return null
        if (sapisid.isEmpty()) return null
        return SapisidHash.authorizationHeader(sapisid, origin)
    }

    /** `Cookie:` header for API/media requests, or `null` when signed out. */
    fun cookieHeader(): String? = cookies?.cookieHeader()

    private fun install(cookies: SessionCookies) {
        cookieJar.setSessionCookies(cookies.toOkHttpCookies())
        _state.value = AuthState.LoggedIn(cookies)
    }
}
