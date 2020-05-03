/**
 * ***********************************************************
 * Copyright, 2020, Open Raven Inc.
 * APACHE LICENSE, VERSION 2.0
 * https://www.openraven.com/legal/apache-2-license
 * *********************************************************
 */
package com.openraven.mimir.controllers

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import com.openraven.mimir.services.AWSConfigService
import com.openraven.mimir.services.AWSOrganizationService
import com.openraven.mimir.services.SnapshotService
import org.slf4j.LoggerFactory.getLogger
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/mimir")
class ResourceDiscoveryController(
    private val awsConfigService: AWSConfigService,
    private val awsOrganizationService: AWSOrganizationService,
    private val snapshotService: SnapshotService
) {
    private final val log = getLogger(javaClass.simpleName)

    private fun logError(exception: Throwable) {
        log.error(exception.message, exception)
    }

    @GetMapping("/organization_info")
    fun getOrganizationInfo(): JsonNode =
        runCatching { awsOrganizationService.getOrganizationDocument() }
            .onFailure {
                logError(it)
            }.getOrDefault(NullNode.instance)

    @PostMapping("/organization_info")
    fun postOrganizationInfo(): JsonNode =
        runCatching { awsOrganizationService.discoverOrganizationDocument() }
            .onFailure {
                logError(it)
            }.getOrDefault(NullNode.instance)

    @GetMapping("/config_for_account")
    fun getConfigForAccount(
        @RequestParam(required = false) accountId: String?,
        @RequestParam(required = false) regionId: String?
    ): JsonNode =
        runCatching { awsConfigService.getConfigServiceInfo(accountId) }
            .onFailure {
                logError(it)
            }.getOrDefault(NullNode.instance)

    @PostMapping("/config_for_account")
    fun postConfigForAccount(
        @RequestParam(required = false) accountId: String?,
        @RequestParam(required = false) regionId: String?
    ): JsonNode =
        runCatching { awsConfigService.discoverConfigServiceInfo(accountId) }
            .onFailure {
                logError(it)
            }.getOrDefault(NullNode.instance)

    @GetMapping("/ingest_from_snapshot")
    fun getIngestFromSnapshot(
        @RequestParam(required = false) accountId: String?,
        @RequestParam(required = false) regionId: String?
    ): JsonNode =
        runCatching { snapshotService.getSnapshotDocuments(accountId, regionId) }
            .onFailure {
                logError(it)
            }.getOrDefault(NullNode.instance)

    @PostMapping("/ingest_from_snapshot")
    fun postIngestFromSnapshot(
        @RequestParam(required = false) accountId: String?,
        @RequestParam(required = false) regionId: String?
    ): JsonNode =
        runCatching { snapshotService.ingestFromSnapshot(accountId, regionId) }
            .onFailure {
                logError(it)
            }.getOrDefault(NullNode.instance)

    @PostMapping("/deliver_snapshot")
    fun postDeliverSnapshot(
        @RequestParam(required = false) accountId: String?,
        @RequestParam(required = false) regionId: String?
    ): JsonNode =
        runCatching { snapshotService.deliverSnapshot(accountId, regionId) }
            .onFailure {
                logError(it)
            }.getOrDefault(NullNode.instance)
}
