/*
 * Copyright (C) 2022, 2025, 2026, Tencent. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2 as
 * published by the Free Software Foundation.
 */

package sun.security.provider;

/**
 * SM3 hash engine factory.
 * <p>
 * Selects the appropriate engine implementation at runtime:
 * <ul>
 *   <li>{@link SM3EngineImpl} — Pure Java implementation (always available)</li>
 *   <li>{@link SM3EngineNative} — JNI-based native implementation (legacy)</li>
 *   <li>{@link SM3EngineFFM} — FFM-based native implementation (new, default when native enabled)</li>
 * </ul>
 * <p>
 * Selection is controlled by two system properties:
 * <dl>
 *   <dt>{@code jdk.sun.enableNativeCrypto}</dt>
 *   <dd>Master switch for native crypto. Default: {@code false}</dd>
 *   <dt>{@code jdk.sun.nativeCryptoBackend}</dt>
 *   <dd>Backend choice when native crypto is enabled.
 *       Values: {@code "ffm"} (default) or {@code "jni"}</dd>
 * </dl>
 */
public final class SM3MessageDigest {

    // ---- Engine type constants ----

    /** Pure Java implementation */
    public static final String ENGINE_JAVA = "java";

    /** JNI-based native implementation (legacy) */
    public static final String ENGINE_JNI = "jni";

    /** FFM-based native implementation */
    public static final String ENGINE_FFM = "ffm";

    // The currently active engine type (cached after first initialization)
    private static volatile String activeEngineType;

    // ---- Factory method ----

    /**
     * Creates a new SM3 engine instance.
     * The engine type is determined by system properties.
     *
     * @return a new SM3Engine instance
     */
    public static SM3Engine newEngine() {
        String type = getActiveEngineType();
        return switch (type) {
            case ENGINE_JNI -> new SM3EngineNative();
            case ENGINE_FFM -> createFFMEngine();
            default -> new SM3EngineImpl();  // Java fallback
        };
    }

    /**
     * Returns the currently active engine type.
     * The result is cached after the first call.
     *
     * @return one of {@code "java"}, {@code "jni"}, or {@code "ffm"}
     */
    public static String getActiveEngineType() {
        if (activeEngineType != null) {
            return activeEngineType;
        }
        activeEngineType = determineEngineType();
        return activeEngineType;
    }

    /**
     * Determines which engine type to use based on system properties
     * and library availability.
     */
    private static String determineEngineType() {
        // Check if native crypto is enabled
        boolean nativeEnabled = Boolean.getBoolean("jdk.sun.enableNativeCrypto");

        // Also check for the OpenSSL crypto library
        boolean opensslLoaded = isOpenSSLAvailable();

        if (!nativeEnabled || !opensslLoaded) {
            return ENGINE_JAVA;
        }

        // Check which native backend to use
        String backend = System.getProperty("jdk.sun.nativeCryptoBackend", ENGINE_FFM);

        if (ENGINE_JNI.equalsIgnoreCase(backend)) {
            // Check if the JNI library is available
            if (isJNILibraryAvailable()) {
                return ENGINE_JNI;
            }
            System.err.println(
                    "WARNING: jdk.sun.nativeCryptoBackend=jni but libsuncrypto " +
                    "not available. Falling back to FFM.");
        }

        // Try FFM — if OpenSSL symbols can't be resolved, fall back to Java
        try {
            // Trigger static initializer of SM3EngineFFM to verify bindings
            new SM3EngineFFM().reset();
            return ENGINE_FFM;
        } catch (Throwable t) {
            System.err.println(
                    "WARNING: FFM SM3 engine initialization failed: " +
                    t.getMessage() + ". Falling back to Java implementation.");
            return ENGINE_JAVA;
        }
    }

    /**
     * Creates an FFM engine with fallback to Java on failure.
     */
    private static SM3Engine createFFMEngine() {
        try {
            return new SM3EngineFFM();
        } catch (Throwable t) {
            System.err.println(
                    "WARNING: Failed to create FFM SM3 engine: " +
                    t.getMessage() + ". Using Java implementation instead.");
            return new SM3EngineImpl();
        }
    }

    /**
     * Checks if OpenSSL libcrypto is loaded in the current process.
     */
    private static boolean isOpenSSLAvailable() {
        try {
            // Try to find the EVP_sm3 symbol in loaded libraries
            java.lang.foreign.SymbolLookup.loaderLookup()
                    .find("EVP_sm3");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Checks if the JNI suncrypto library is available.
     */
    private static boolean isJNILibraryAvailable() {
        try {
            System.loadLibrary("suncrypto");
            return true;
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    /**
     * Resets the cached engine type, forcing re-detection on next use.
     * Useful for testing with different system properties.
     */
    static void resetEngineType() {
        activeEngineType = null;
    }
}
