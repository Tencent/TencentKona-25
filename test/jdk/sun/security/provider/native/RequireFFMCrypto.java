/*
 * Copyright (C) 2026, Tencent. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2 as
 * published by the Free Software Foundation.
 */

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;

/**
 * JUnit 5 conditional annotation: tests are only executed when
 * the FFM-based SM3 engine ({@code SM3EngineFFM}) is available.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(RequireFFMCrypto.FFMCryptoCondition.class)
public @interface RequireFFMCrypto {

    class FFMCryptoCondition implements ExecutionCondition {

        private static final boolean FFM_AVAILABLE = checkFFMAvailable();

        private static boolean checkFFMAvailable() {
            try {
                Class<?> ffmEngineClass = Class.forName(
                        "sun.security.provider.SM3EngineFFM");
                Method isAvailable = ffmEngineClass.getDeclaredMethod(
                        "isAvailable");
                return (boolean) isAvailable.invoke(null);
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(
                ExtensionContext context) {
            if (FFM_AVAILABLE) {
                return ConditionEvaluationResult.enabled(
                        "SM3 FFM engine is available");
            }
            return ConditionEvaluationResult.disabled(
                    "SM3 FFM engine is not available");
        }
    }
}
