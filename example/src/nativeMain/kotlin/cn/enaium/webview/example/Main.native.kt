/*
 * Copyright (c) 2026 Enaium
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.enaium.webview.example

import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import platform.posix.pthread_create
import platform.posix.pthread_tVar
import platform.posix.usleep

fun main() {
    println("Running on Kotlin/Native")
    runExample()
}

/**
 * kotlin.concurrent.thread was removed from the Kotlin/Native stdlib in
 * Kotlin 2.4, so a bare pthread is used (works on macOS, Linux and MinGW).
 */
actual fun schedule(millis: Long, block: () -> Unit) {
    val ref = StableRef.create(TimedRunnable(millis, block))
    val thread = nativeHeap.alloc<pthread_tVar>()
    val rc = pthread_create(thread.ptr, null, entryPoint, ref.asCPointer())
    check(rc == 0) { "pthread_create failed with code $rc" }
}

private class TimedRunnable(private val millis: Long, private val block: () -> Unit) {
    fun run() {
        // usleep accepts at most one second per call on most platforms.
        var remainingMicros = millis * 1_000L
        val chunk = 1_000_000L
        while (remainingMicros > 0L) {
            val step = minOf(remainingMicros, chunk)
            usleep(step.toUInt())
            remainingMicros -= step
        }
        block()
    }
}

private val entryPoint: CPointer<CFunction<(CPointer<out CPointed>?) -> CPointer<out CPointed>?>> =
    staticCFunction { arg: CPointer<out CPointed>? ->
        val ref = arg!!.asStableRef<TimedRunnable>()
        ref.get().run()
        ref.dispose()
        null
    }
