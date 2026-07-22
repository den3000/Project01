package ru.den.writes.code.agenticHub.mcps.atimelogger

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the aTimeLogger v2 API. Only the fields the tools use are mapped; the client
 * decodes with `ignoreUnknownKeys = true`, so unmapped fields are tolerated. Identifier and
 * time fields are defaulted so a response that omits or renames a field can't crash decoding —
 * exact field names are confirmed against a live response when the field is actually consumed.
 */
@Serializable
internal data class TypesResponse(val types: List<ActivityTypeDto> = emptyList())

@Serializable
internal data class ActivityTypeDto(
    val guid: String = "",
    val name: String,
    val color: String? = null,
)
