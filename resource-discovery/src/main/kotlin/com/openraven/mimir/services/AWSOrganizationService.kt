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
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.organizations.OrganizationsClient
import software.amazon.awssdk.services.organizations.model.ChildType

private const val organizationIndexName = "AWSOrganization"

@Service
class AWSOrganizationService(
    private val elasticSearchService: ElasticSearchService,
    private val awsCredentialsHelper: AWSCredentialsHelper,
    private val json: ObjectMapper
) {
    private final val log = LoggerFactory.getLogger(javaClass.simpleName)

    private fun getChildren(organizationsClient: OrganizationsClient, parentId: String): ObjectNode =
        json.createObjectNode().apply {
            putArray("organizationalUnits").addAll(organizationsClient.listChildren { request ->
                request.parentId(parentId).childType(ChildType.ORGANIZATIONAL_UNIT)
            }.children().map { child ->
                json.convertValue<ObjectNode>(
                    organizationsClient.describeOrganizationalUnit { request ->
                        request.organizationalUnitId(child.id())
                    }.organizationalUnit().toBuilder()
                ).apply {
                    setAll<ObjectNode>(json.convertValue<ObjectNode>(child.toBuilder()))
                    setAll<ObjectNode>(
                        getChildren(organizationsClient, get("id").asText())
                    )
                }
            })

            putArray("accounts").addAll(organizationsClient.listChildren { request ->
                request.parentId(parentId).childType(ChildType.ACCOUNT)
            }.children().map { child ->
                val account = json.convertValue<ObjectNode>(
                    organizationsClient.describeAccount { request ->
                        request.accountId(child.id())
                    }.account().toBuilder()
                )

                account.setAll<ObjectNode>(json.convertValue<ObjectNode>(child.toBuilder()))
            })
        }

    fun getOrganizationDocument(): JsonNode = runCatching {
        elasticSearchService.getDocuments(organizationIndexName.toLowerCase()).first()
    }.getOrDefault(NullNode.instance)

    fun discoverOrganizationDocument(): JsonNode {
        val organization: ObjectNode =
            OrganizationsClient.builder().region(Region.AWS_GLOBAL).build().use { client ->
                json.convertValue<ObjectNode>(client.describeOrganization().organization().toBuilder())
            }.apply {
                runCatching {
                    awsCredentialsHelper.getMasterSession(get("masterAccountId").asText()).use { credentials ->
                        OrganizationsClient.builder().region(Region.AWS_GLOBAL).credentialsProvider(credentials).build()
                            .use { client ->
                                putArray("roots").addAll(client.listRoots().roots().map { root ->
                                    json.convertValue<ObjectNode>(root.toBuilder()).apply {
                                        setAll<ObjectNode>(getChildren(client, root.id()))
                                    }
                                })
                            }
                    }
                }.onFailure {
                    log.warn(
                        "Failed to assume role into account: {}, with message: {}",
                        get("masterAccountId").asText(),
                        it.message
                    )
                }

                put("awsAccountId", get("masterAccountId").asText())
                put("resourceId", get("id").asText())
                put("resourceType", organizationIndexName)
            }

        elasticSearchService.writeDocuments(listOf(organization))
        return getOrganizationDocument()
    }
}
