@file:Suppress("unused")

package io.github.kyay10.kotlinlambdareturninliner.utils

import org.jetbrains.kotlin.resolve.DelegatingBindingTrace
import org.jetbrains.kotlin.resolve.MutableDiagnosticsUtils
import org.jetbrains.kotlin.resolve.diagnostics.MutableDiagnosticsWithSuppression

val DelegatingBindingTrace.mutableDiagnostics: MutableDiagnosticsWithSuppression get() = MutableDiagnosticsUtils.getMutableDiagnosticsFromTrace(this)
