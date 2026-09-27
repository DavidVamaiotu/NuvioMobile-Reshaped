package com.nuvio.app.features.library

internal expect object LibraryStorage {
    fun loadPayload(profileId: Int): String?
    fun savePayload(profileId: Int, payload: String)

    fun loadCalendarPayload(profileId: Int): String?
    fun saveCalendarPayload(profileId: Int, payload: String)
}
