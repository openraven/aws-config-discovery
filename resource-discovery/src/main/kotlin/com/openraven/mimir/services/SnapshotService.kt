/**
 * ***********************************************************
 * Copyright, 2020, Open Raven Inc.
 * APACHE LICENSE, VERSION 2.0
 * https://www.openraven.com/legal/apache-2-license
 * *********************************************************
 */
package com.openraven.mimir.services

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.openraven.mimir.helpers.AWSCredentialsHelper
import org.joda.time.DateTime
import org.slf4j.LoggerFactory.getLogger
import org.springframework.stereotype.Service
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.config.ConfigClient
import software.amazon.awssdk.services.s3.S3Client
import java.util.zip.GZIPInputStream

private const val indexName = "AWSConfigSnapshot"

/*
Intended use
snapshotPrefixTemplate.format(
                        accountId,
                        regionId,
                        snapshotDate.year,
                        snapshotDate.monthOfYear,
                        snapshotDate.dayOfMonth
                    )
 */
private const val snapshotPrefixTemplate = "AWSLogs/%s/Config/%s/%s/%s/%s/ConfigSnapshot"
private const val lastSuccessfulTimePath = "/deliveryChannelStatus/configSnapshotDeliveryInfo/lastSuccessfulTime"

@Service
class SnapshotService(
    private val elasticSearchService: ElasticSearchService,
    private val awsCredentialsHelper: AWSCredentialsHelper,
    private val json: ObjectMapper,
    private val awsConfigService: AWSConfigService
) {
    private final val log = getLogger(javaClass.simpleName)

    fun ingestFromSnapshot(accountId: String?, regionId: String?): JsonNode {
        val accountDocuments = awsConfigService.getConfigServiceInfo(accountId)

        val snapshots = accountDocuments.asSequence().filterIsInstance<ObjectNode>().filter { account ->
            when (accountId) {
                null -> true
                account.get("awsAccountId").asText() -> true
                else -> false
            }
        }.flatMap { account ->
            val snapshotDocuments = getSnapshotDocuments(account.get("id").asText(), regionId)

            account.get("regions").asSequence().filterIsInstance<ObjectNode>().filter { region ->
                when (regionId) {
                    null -> true
                    region.get("id").asText() -> true
                    else -> false
                }
            }.map { region ->
                region.apply {
                    put("awsAccountId", account.get("id").asText())
                    put("awsRegion", region.get("id").asText())
                    put("masterAccountId", account.get("masterAccountId").asText())
                    set<JsonNode>(
                        "lastSnapshotDocument",
                        snapshotDocuments.asSequence().filter { document ->
                            document.get("awsRegion").asText() == region.get("id").asText() &&
                                document.get("awsAccountId").asText() == account.get("id").asText()
                        }.firstOrNull()
                    )
                }
            }
        }.map { region ->
            processSnapshot(region.get("awsAccountId").asText(), region.get("awsRegion").asText(), region)
        }.filterNotNull().toList()

        if (snapshots.isNotEmpty()) {
            elasticSearchService.writeDocuments(snapshots)
        }

        return getSnapshotDocuments(accountId, regionId)
    }

    fun getSnapshotDocuments(accountId: String?, regionId: String?): JsonNode = runCatching {
        if (accountId != null && regionId != null) {
            elasticSearchService.getDocuments(
                indexName.toLowerCase(),
                mapOf("awsAccountId" to accountId, "awsRegion" to regionId)
            )
        } else if (accountId != null) {
            elasticSearchService.getDocuments(
                indexName.toLowerCase(),
                mapOf("awsAccountId" to accountId),
                1000
            )
        } else if (regionId != null) {
            elasticSearchService.getDocuments(
                indexName.toLowerCase(),
                mapOf("awsRegion" to regionId),
                1000
            )
        } else {
            elasticSearchService.getDocuments(indexName.toLowerCase(), count = 1000)
        }
    }.getOrDefault(NullNode.instance)

    fun deliverSnapshot(accountId: String?, regionId: String?): JsonNode {
        val accountDocuments = awsConfigService.getConfigServiceInfo(accountId)

        val snapshotsRequest = accountDocuments.asSequence().filterIsInstance<ObjectNode>().filter { account ->
            when (accountId) {
                null -> true
                account.get("awsAccountId").asText() -> true
                else -> false
            }
        }.flatMap { account ->
            account.get("regions").asSequence().filterIsInstance<ObjectNode>().filter { region ->
                when (regionId) {
                    null -> true
                    region.get("id").asText() -> true
                    else -> false
                }
            }.map { region ->
                json.createObjectNode().apply {
                    put("awsAccountId", account.get("awsAccountId").asText())
                    put("awsRegionId", region.get("id").asText())
                    put("masterAccountId", account.get("masterAccountId").asText())
                    put("deliveryChannelName", region.at("/deliveryChannel/name").asText())
                }
            }
        }.map { snapshotRequest ->
            requestDeliverSnapshot(
                snapshotRequest.get("awsAccountId").asText(),
                snapshotRequest.get("awsRegionId").asText(),
                snapshotRequest.get("deliveryChannelName").asText(),
                snapshotRequest.get("masterAccountId").asText()
            )
        }.filterNotNull().toList()

        return json.convertValue(snapshotsRequest)
    }

    private fun requestDeliverSnapshot(
        accountId: String,
        regionId: String,
        deliveryChannelName: String,
        masterAccountId: String? = null
    ): String? = runCatching {
        awsCredentialsHelper.getAccountSession(accountId).use { credentials ->
            ConfigClient.builder().credentialsProvider(credentials)
                .region(Region.of(regionId)).build().use { client ->
                    client.deliverConfigSnapshot { request ->
                        request.deliveryChannelName(deliveryChannelName)
                    }.configSnapshotId()
                }
        }
    }.onFailure {
        log.warn(
            "Failed to deliver snapshot for account: {}, region: {}" +
                ", deliveryChannel: {}, masterAccount: {}, with error {}",
            accountId,
            regionId,
            deliveryChannelName,
            masterAccountId,
            it.message
        )
    }.getOrNull()

    private fun processSnapshot(accountId: String, regionId: String, configData: ObjectNode): ObjectNode? =
        runCatching {
            val bucketName = configData.at("/deliveryChannel/s3BucketName").asText()
            val lastSuccessTime =
                configData.at(lastSuccessfulTimePath).asText()

            if (bucketName.isNullOrBlank() || lastSuccessTime.isNullOrBlank()) {
                error(
                    "Missing snapshot information for account: $accountId, region: $regionId," +
                        " bucketName: $bucketName, lastSuccessTime: $lastSuccessTime"
                )
            }

            val snapshotDate = DateTime.parse(lastSuccessTime)

            if (configData.get("lastSnapshotDocument").at(lastSuccessfulTimePath)
                    .asText(DateTime.now().toDateTimeISO().toString()).let { date ->
                        DateTime.parse(date) == snapshotDate
                    }
            ) {
                error(
                    "Snapshot already processed date: $snapshotDate, account: $accountId, region: $regionId"
                )
            }

            awsCredentialsHelper.getAccountSession(accountId).use { credentials ->
                S3Client.builder().credentialsProvider(credentials).region(Region.of(regionId)).build()
                    .use { client ->
                        val snapshotPrefix = snapshotPrefixTemplate.format(
                            accountId,
                            regionId,
                            snapshotDate.year,
                            snapshotDate.monthOfYear,
                            snapshotDate.dayOfMonth
                        )

                        val snapshotKey = client.listObjectsV2 { request ->
                            request.bucket(bucketName).prefix(snapshotPrefix).maxKeys(1)
                        }.contents().firstOrNull()?.key()

                        val itemsWritten =
                            client.getObject { request ->
                                request.bucket(bucketName).key(snapshotKey)
                            }.use { s3Object ->
                                GZIPInputStream(s3Object).use { gzipObject ->
                                    elasticSearchService.writeDocuments(
                                        json.readTree(gzipObject).get("configurationItems").toList()
                                            .filterIsInstance<ObjectNode>()
                                    )
                                }.items.size
                            }

                        configData.apply {
                            put("itemsWritten", itemsWritten)
                            put("resourceId", snapshotKey)
                            put("resourceType", indexName)
                            put("arn", "urn:openraven:aws:$regionId:$accountId:mimir/snapshot")
                        }
                    }
            }
        }.onFailure {
            log.warn("${it.message} occurred for account: $accountId, region: $regionId")
        }.getOrNull()
}
