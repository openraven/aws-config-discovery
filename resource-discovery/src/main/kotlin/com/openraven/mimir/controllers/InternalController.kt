/**
 * ***********************************************************
 * Copyright, 2020, Open Raven Inc.
 * APACHE LICENSE, VERSION 2.0
 * https://www.openraven.com/legal/apache-2-license
 * *********************************************************
 */
package com.openraven.mimir.controllers

import com.openraven.mimir.services.ElasticSearchService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/internal")
class InternalController(private val elasticSearchService: ElasticSearchService) {
    @PostMapping("/setup_database")
    fun postSetupDatabase(): Boolean {
        return (elasticSearchService.deleteAWSIndices() &&
            elasticSearchService.setupTemplate() &&
            elasticSearchService.ensureIndicesPresent())
    }
}
