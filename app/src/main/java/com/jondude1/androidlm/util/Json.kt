package com.jondude1.androidlm.util

import kotlinx.serialization.json.Json

/** Shared JSON configuration: tolerant of unknown keys so stored files and API clients never break on new fields. */
val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = true
    coerceInputValues = true
}
