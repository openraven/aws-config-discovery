/**
 * ***********************************************************
 * Copyright, 2020, Open Raven Inc.
 * APACHE LICENSE, VERSION 2.0
 * https://www.openraven.com/legal/apache-2-license
 * *********************************************************
 */
package com.openraven.mimir.helpers

import org.slf4j.LoggerFactory.getLogger
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component
import org.springframework.web.context.annotation.RequestScope
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest

/*
Intended use
masterRoleTemplate.format(masterAccountId, sourceAccountId)
 */
private const val masterRoleTemplate = "arn:aws:iam::%s:role/openraven-cross-account-org-%s"

/*
Intended use
resourceRoleTemplate.format(accountId, sourceAccountId)
 */
private const val resourceRoleTemplate = "arn:aws:iam::%s:role/openraven-cross-account-%s"

@Component
@RequestScope
class AWSCredentialsHelper : DisposableBean {
    private val logger = getLogger(javaClass.simpleName)
    private val stsClient by lazy {
        StsClient.create()
    }

    private val stsClients = mutableMapOf<String, StsClient>()

    private val sourceAccountId by lazy {
        stsClient.callerIdentity.account()
    }

    fun getAccountSession(accountId: String): StsAssumeRoleCredentialsProvider {
        return getCredentials(accountId, resourceRoleTemplate.format(accountId, sourceAccountId))
    }

    fun getMasterSession(masterAccountId: String): StsAssumeRoleCredentialsProvider {
        return getCredentials(masterAccountId, masterRoleTemplate.format(masterAccountId, sourceAccountId))
    }

    private fun getCredentials(
        accountId: String,
        roleArn: String
    ): StsAssumeRoleCredentialsProvider {
        stsClients[sourceAccountId] = stsClient

        return StsAssumeRoleCredentialsProvider.builder().stsClient(stsClient).refreshRequest(
            AssumeRoleRequest.builder().durationSeconds(900)
                .roleArn(roleArn)
                .roleSessionName("openraven-$accountId").build()
        ).build()
    }

    override fun destroy() {
        stsClients.forEach { (account, client) ->
            logger.info("Closing stsClient for $account")
            client.close()
        }
    }
}
