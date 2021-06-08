package org.jetbrains.kotlin.resolve;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.resolve.diagnostics.MutableDiagnosticsWithSuppression;

public class MutableDiagnosticsUtils {
    public static MutableDiagnosticsWithSuppression getMutableDiagnosticsFromTrace(@NotNull DelegatingBindingTrace trace) {
        return trace.getMutableDiagnostics();
    }
}
