package ru.den.writes.code.agenticHub.mcps.atimelogger

/** Builds an [IntervalDto] pointing at activity type [guid], spanning unix seconds [from]..[to]. */
internal fun interval(guid: String, from: Long, to: Long): IntervalDto =
    IntervalDto(from = from, to = to, type = IntervalTypeRef(guid = guid))
