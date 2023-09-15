package io.cmcode.data.models

data class BasicApiResponse(
    val successful: Boolean,
    val message: String? = null
)
