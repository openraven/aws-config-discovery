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
import org.elasticsearch.action.bulk.BulkResponse
import org.slf4j.LoggerFactory.getLogger
import org.springframework.stereotype.Service
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.config.ConfigClient

private const val accountIndexName = "AWSAccount"
private const val regionIndexName = "AWSRegion"

@Service
class AWSConfigService(
    private val elasticSearchService: ElasticSearchService,
    private val awsCredentialsHelper: AWSCredentialsHelper,
    private val json: ObjectMapper,
    private val awsOrganizationService: AWSOrganizationService
) {
    private final val log = getLogger(javaClass.simpleName)

    fun discoverConfigServiceInfo(accountId: String?): JsonNode {
        val organization = awsOrganizationService.getOrganizationDocument()

        if (organization == NullNode.instance) {
            return organization
        }

        val masterAccountId = organization.get("masterAccountId").asText()
        val masterAccountArn = organization.get("arn").asText()

        val units = organization.get("roots").toMutableList()

        val regions = ConfigClient.serviceMetadata().regions()

        while (units.isNotEmpty()) {
            val unit = units.first()

            units.addAll(unit.get("organizationalUnits").toList())

            val accounts = unit.get("accounts").asSequence().filterIsInstance<ObjectNode>().filter { account ->
                when (accountId) {
                    null -> true
                    account.get("id").asText() -> true
                    else -> false
                }
            }.map { account ->
                account.apply {
                    put("masterAccountArn", masterAccountArn)
                    put("masterAccountId", masterAccountId)

                    awsCredentialsHelper.getAccountSession(account.get("id").asText())
                        .use { credentials ->
                            putArray("regions").addAll(regions.map { region ->
                                runCatching {
                                    json.createObjectNode().apply {
                                        put("id", region.metadata().id())
                                        put("description", region.metadata().description())
                                        getConfigForAccountRegion(region, credentials)?.let {
                                            setAll<ObjectNode>(it)
                                        }
                                    }
                                }.onFailure {
                                    log.warn(
                                        "${it.message} for account: ${account.get("id")
                                            .asText()}, masterAccount: $masterAccountId, region: ${region.id()}"
                                    )
                                }.getOrNull()
                            }.filterIsInstance<ObjectNode>())
                        }
                }
            }.toList()

            if (accounts.isNotEmpty()) {
                writeAccountDocuments(accounts)
            }

            units.remove(unit)
        }

        return getConfigServiceInfo(accountId)
    }

    fun getConfigServiceInfo(accountId: String?): JsonNode = if (accountId != null) {
        elasticSearchService.getDocuments(accountIndexName.toLowerCase(), mapOf("awsAccountId" to accountId))
    } else {
        elasticSearchService.getDocuments(accountIndexName.toLowerCase(), count = 1000)
    }

    private fun writeAccountDocuments(accounts: List<ObjectNode>): BulkResponse {
        val accountDocuments = accounts.flatMap { account ->
            val accountId = account.get("id").asText()
            account.put("awsAccountId", accountId)
            account.put("resourceId", accountId)
            account.put("resourceName", account.get("name").asText())
            account.put("resourceType", accountIndexName)

            val regions = account.get("regions").asSequence().filterIsInstance<ObjectNode>().map { region ->
                val regionId = region.get("id").asText()
                json.createObjectNode().apply {
                    put("awsAccountId", accountId)
                    put("resourceId", regionId)
                    put("resourceType", regionIndexName)
                    put("resourceName", region.get("description").asText())
                    put("arn", "arn:aws:organizations:$regionId:$accountId")
                    put("awsRegion", regionId)
                }
            }.toList()

            listOf(regions, listOf(account), listOf(json.createObjectNode().apply {
                put("awsAccountId", accountId)
                put("resourceId", "global")
                put("resourceType", regionIndexName)
                put("resourceName", "Global")
                put("arn", "arn:aws:organizations:global:$accountId")
                put("awsRegion", "global")
            })).flatten()
        }

        return elasticSearchService.writeDocuments(accountDocuments)
    }

    private fun getConfigForAccountRegion(
        region: Region,
        awsCredentialsProvider: AwsCredentialsProvider
    ): ObjectNode? = json.createObjectNode().apply {
        ConfigClient.builder().region(region).credentialsProvider(awsCredentialsProvider).build()
            .use { client ->
                client.describeConfigurationRecorders().configurationRecorders().firstOrNull()?.let {
                    set<ObjectNode>("recorder", json.convertValue(it.toBuilder()))
                }

                client.describeConfigurationRecorderStatus().configurationRecordersStatus().firstOrNull()?.let {
                    set<ObjectNode>("recorderStatus", json.convertValue<ObjectNode>(it.toBuilder()))
                }

                client.describeDeliveryChannels().deliveryChannels().firstOrNull()?.let {
                    set<ObjectNode>("deliveryChannel", json.convertValue<ObjectNode>(it.toBuilder()))
                }

                client.describeDeliveryChannelStatus().deliveryChannelsStatus().firstOrNull()?.let {
                    set<ObjectNode>("deliveryChannelStatus", json.convertValue<ObjectNode>(it.toBuilder()))
                }
            }

        if (isEmpty) {
            error("AWSConfigService information missing")
        }
    }
}
