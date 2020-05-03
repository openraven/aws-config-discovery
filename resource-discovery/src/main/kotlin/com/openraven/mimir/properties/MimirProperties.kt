/**
 * ***********************************************************
 * Copyright, 2020, Open Raven Inc.
 * APACHE LICENSE, VERSION 2.0
 * https://www.openraven.com/legal/apache-2-license
 * *********************************************************
 */
package com.openraven.mimir.properties

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.cloud.context.config.annotation.RefreshScope
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "openraven.app.v1.resource-discovery")
@RefreshScope
data class MimirProperties(
    var elasticSearchHost: String = "",
    var elasticSearchPort: Int = -1,
    var elasticSearchProtocol: String = "",
    var elasticSearchUsername: String = "",
    var elasticSearchPassword: String = ""
)
