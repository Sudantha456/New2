package com.app.youtube.lite.core.util

import android.util.Log
import com.app.youtube.lite.BuildConfig

/**
 * The app's logger, built so that logging costs *nothing* in a release build.
 *
 * Two mechanisms work together:
 *  • `inline` — the call site is compiled with the body inlined, so the message lambda is only
 *    invoked where the enclosing `if` permits it. A `L.d { "…" }` call in a hot path compiles to
 *    a single constant `false` comparison in release, and the lambda body disappears entirely —
 *    no string concatenation, no boxing, no autoboxing of `Int` arguments;
 *  • `BuildConfig.DEBUG` — R8 sees `enabled` as a constant and deletes the whole call.
 *
 * The result: a release APK contains no logging code at all, while debug builds get full detail.
 * Warnings and errors are *always* emitted (they are rare, and losing them in release would make
 * field failures impossible to diagnose).
 */
object L {

    /**
     * True in debug builds; R8 constant-folds this to `false` in release.
     *
     * `@PublishedApi internal` rather than `const`: `BuildConfig.DEBUG` is a Java field, which
     * Kotlin does not accept as a `const` initialiser, and the inline functions below must be able
     * to read it from their call sites.
     */
    @PublishedApi
    internal val ENABLED: Boolean = BuildConfig.DEBUG

    private const val PREFIX = "YT-Lite/"

    inline fun v(tag: String, message: () -> String) {
        if (ENABLED) Log.v(PREFIX + tag, message())
    }

    inline fun d(tag: String, message: () -> String) {
        if (ENABLED) Log.d(PREFIX + tag, message())
    }

    inline fun i(tag: String, message: () -> String) {
        if (ENABLED) Log.i(PREFIX + tag, message())
    }

    /** Always emitted: a warning that only appears in debug builds is a warning nobody reads. */
    fun w(tag: String, message: () -> String) {
        Log.w(PREFIX + tag, message())
    }

    fun e(tag: String, message: () -> String) {
        Log.e(PREFIX + tag, message())
    }

    /** Logs and swallows — for teardown paths where throwing would mask the original failure. */
    fun e(tag: String, throwable: Throwable, message: () -> String) {
        Log.e(PREFIX + tag, message(), throwable)
    }
}
