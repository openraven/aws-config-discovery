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
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.openraven.mimir.helpers.getEncodedNamedUUID
import com.openraven.mimir.properties.MimirProperties
import org.apache.http.HttpHost
import org.apache.http.auth.AuthScope
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.impl.client.BasicCredentialsProvider
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest
import org.elasticsearch.action.bulk.BulkRequest
import org.elasticsearch.action.bulk.BulkResponse
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.action.update.UpdateRequest
import org.elasticsearch.client.RequestOptions
import org.elasticsearch.client.RestClient
import org.elasticsearch.client.RestHighLevelClient
import org.elasticsearch.client.indices.CreateIndexRequest
import org.elasticsearch.client.indices.GetIndexRequest
import org.elasticsearch.client.indices.GetIndexTemplatesRequest
import org.elasticsearch.client.indices.PutIndexTemplateRequest
import org.elasticsearch.common.xcontent.XContentType
import org.elasticsearch.index.query.QueryBuilders
import org.elasticsearch.search.builder.SearchSourceBuilder
import org.slf4j.LoggerFactory.getLogger
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ElasticSearchService(
    private val mimirProperties: MimirProperties,
    private val json: ObjectMapper
) {
    private final val log = getLogger(javaClass.simpleName)

    private final val indexTemplates = mapOf(
        "assetgroup" to javaClass.getResourceAsStream("/assetgroup_template.json").readAllBytes().decodeToString(),
        "configservice" to javaClass.getResourceAsStream("/configservice_template.json").readAllBytes().decodeToString()
    )

    private final val coreIndices = listOf("awsorganization", "awsaccount", "assetgroup", "scanner")

    private fun credentialsProvider(userName: String, password: String) = BasicCredentialsProvider().apply {
        setCredentials(AuthScope.ANY, UsernamePasswordCredentials(userName, password))
    }

    fun getRestHighLevelClient(): RestHighLevelClient = RestHighLevelClient(
        RestClient.builder(
            HttpHost(
                mimirProperties.elasticSearchHost,
                mimirProperties.elasticSearchPort,
                mimirProperties.elasticSearchProtocol
            )
        ).apply {
            if (mimirProperties.elasticSearchUsername.isNotEmpty() &&
                mimirProperties.elasticSearchPassword.isNotEmpty()
            ) {
                setHttpClientConfigCallback {
                    it.setDefaultCredentialsProvider(
                        credentialsProvider(
                            mimirProperties.elasticSearchUsername,
                            mimirProperties.elasticSearchPassword
                        )
                    )
                }
            }
        }
    )

    fun getDocuments(
        indexName: String,
        filters: Map<String, String>? = null,
        count: Int = 1
    ): JsonNode = getRestHighLevelClient().use { client ->
        return json.convertValue(
            runCatching {
                client.search(SearchRequest(indexName).apply {
                    source(SearchSourceBuilder().size(count).query(if (filters != null) {
                        QueryBuilders.boolQuery().apply {
                            filters.forEach { (key, value) ->
                                filter(QueryBuilders.termQuery(key, value))
                            }
                        }
                    } else {
                        QueryBuilders.matchAllQuery()
                    }))
                }, RequestOptions.DEFAULT).hits.hits.map { hit -> hit.sourceAsMap }
            }.onFailure {
                log.warn("Failed getting documents from index {} with message {}", indexName, it.message)
            }.getOrDefault(emptyList())
        )
    }

    fun writeDocuments(
        documents: List<JsonNode>
    ): BulkResponse {
        getRestHighLevelClient().use { client ->
            val response = client.bulk(BulkRequest().apply {
                setRefreshPolicy("true")
                add(documents.filterIsInstance<ObjectNode>().map { doc ->
                    doc.put("updatedIso", Instant.now().toString())
                    val indexId = if (doc.has("ARN")) {
                        doc.get("ARN").asText()
                    } else {
                        doc.get("arn").asText()
                    }.getEncodedNamedUUID()

                    doc.put("documentId", indexId)

                    UpdateRequest(
                        doc.get("resourceType").asText().replace(":", "").toLowerCase(),
                        indexId
                    ).apply {
                        doc(json.writeValueAsString(doc), XContentType.JSON)
                        docAsUpsert(true)
                    }
                })
            }, RequestOptions.DEFAULT)

            if (response.hasFailures()) {
                log.warn(response.buildFailureMessage())
            }

            return response
        }
    }

    fun ensureIndicesPresent(): Boolean = getRestHighLevelClient().use { client ->
        coreIndices.asSequence().map { index ->
            runCatching {
                if (!client.indices().exists(GetIndexRequest(index), RequestOptions.DEFAULT)) {
                    client.indices().create(CreateIndexRequest(index), RequestOptions.DEFAULT).index() == index
                } else {
                    false
                }
            }.onFailure {
                log.warn("Failure creating index $index, with message ${it.message}")
            }.getOrDefault(false)
        }.all { it }
    }

    @SuppressWarnings("unused")
    fun isTemplatePresent(): Boolean = getRestHighLevelClient().use { client ->
        indexTemplates.asSequence().map { template ->
            runCatching {
                client.indices()
                    .getIndexTemplate(
                        GetIndexTemplatesRequest(template.key),
                        RequestOptions.DEFAULT
                    ).indexTemplates.isNotEmpty()
            }.onFailure {
                log.warn("Failure getting index template ${template.key}, with message ${it.message}")
            }.getOrDefault(false)
        }.all { it }
    }

    fun setupTemplate(): Boolean = getRestHighLevelClient().use { client ->
        indexTemplates.asSequence().map { template ->
            runCatching {
                client.indices().putTemplate(PutIndexTemplateRequest(template.key).apply {
                    source(template.value, XContentType.JSON)
                }, RequestOptions.DEFAULT).isAcknowledged
            }.onFailure {
                log.error("Failure putting index template ${template.key}", it)
            }.getOrDefault(false)
        }.all { it }
    }

    fun deleteAWSIndices(): Boolean = getRestHighLevelClient().use { client ->
        runCatching {
            client.indices().delete(DeleteIndexRequest("aws*"), RequestOptions.DEFAULT).isAcknowledged
        }.onFailure {
            log.warn("Failure deleting indices with pattern aws*", it)
        }.getOrDefault(false)
    }
}
