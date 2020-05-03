/**
 * ***********************************************************
 * Copyright, 2020, Open Raven Inc.
 * APACHE LICENSE, VERSION 2.0
 * https://www.openraven.com/legal/apache-2-license
 * *********************************************************
 */
package com.openraven.mimir.helpers

import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID

fun String.getEncodedNamedUUID(): String {
    if (this.isEmpty()) {
        error("Target for converting to Encoded Named UUID is blank!")
    }

    val targetBytes = this.toByteArray(charset("UTF-8"))
    val uuid = UUID.nameUUIDFromBytes(targetBytes)
    val uuidBytes = ByteBuffer.wrap(ByteArray(16))
    uuidBytes.putLong(uuid.mostSignificantBits)
    uuidBytes.putLong(uuid.leastSignificantBits)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(uuidBytes.array())
}
