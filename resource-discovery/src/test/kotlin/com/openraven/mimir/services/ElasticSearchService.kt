/**
 * ***********************************************************
 * Copyright, 2020, Open Raven Inc.
 * APACHE LICENSE, VERSION 2.0
 * https://www.openraven.com/legal/apache-2-license
 * *********************************************************
 */
package com.openraven.mimir.services

import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service

@Primary
@Service
@SuppressWarnings("unused")
class ElasticSearchService {
    fun isTemplatePresent() = true
    fun setupTemplate() = true
    fun ensureIndicesPresent() = true
}
