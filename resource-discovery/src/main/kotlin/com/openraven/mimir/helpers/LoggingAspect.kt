/**
 * ***********************************************************
 * Copyright, 2020, Open Raven Inc.
 * APACHE LICENSE, VERSION 2.0
 * https://www.openraven.com/legal/apache-2-license
 * *********************************************************
 */
package com.openraven.mimir.helpers

import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.Pointcut
import org.slf4j.LoggerFactory.getLogger
import org.springframework.stereotype.Component
import java.util.Arrays

@Aspect
@Component
class LoggingAspect {
    @Suppress("Unused")
    @Pointcut("within(com.openraven.mimir.services..*) || within(com.openraven.mimir.controllers..*)")
    fun dataDiscoveryPointCut() {
    }

    @Suppress("Unused")
    @Pointcut("within(com.openraven.mimir.helpers..*) || within(com.openraven.mimir.properties..*)")
    fun helperDiscoveryPointCut() {
    }

    @Around("dataDiscoveryPointCut()")
    @Throws(Throwable::class)
    fun logInfoAround(joinPoint: ProceedingJoinPoint): Any {
        val log = getLogger(joinPoint.signature.declaringTypeName)

        if (log.isInfoEnabled) {
            log.info(
                "Enter: {}() with argument[s] = {}", joinPoint.signature.name, Arrays.toString(joinPoint.args)
            )
        }
        return try {
            val result: Any = joinPoint.proceed() ?: ""
            if (log.isInfoEnabled) {
                log.info("Exit: {}() with result = {}", joinPoint.signature.name, result)
            }
            result
        } catch (e: IllegalArgumentException) {
            log.error("Illegal argument: {} in {}()", Arrays.toString(joinPoint.args), joinPoint.signature.name)
            throw e
        }
    }

    @Around("helperDiscoveryPointCut()")
    @Throws(Throwable::class)
    fun logDebugAround(joinPoint: ProceedingJoinPoint): Any {
        val log = getLogger(joinPoint.signature.declaringTypeName)

        if (log.isDebugEnabled) {
            log.debug(
                "Enter: {}() with argument[s] = {}", joinPoint.signature.name, Arrays.toString(joinPoint.args)
            )
        }
        return try {
            val result: Any = joinPoint.proceed() ?: ""
            if (log.isDebugEnabled) {
                log.debug("Exit: {}() with result = $result", joinPoint.signature.name)
            }
            result
        } catch (e: IllegalArgumentException) {
            log.error("Illegal argument: {} in {}()", Arrays.toString(joinPoint.args), joinPoint.signature.name)
            throw e
        }
    }
}
