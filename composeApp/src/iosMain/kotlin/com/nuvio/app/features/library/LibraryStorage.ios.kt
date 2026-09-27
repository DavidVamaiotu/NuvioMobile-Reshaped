package com.nuvio.app.features.library

import platform.Foundation.NSUserDefaults

actual object LibraryStorage {
    private fun payloadKey(profileId: Int) = "library_payload_$profileId"

    actual fun loadPayload(profileId: Int): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(payloadKey(profileId))

    actual fun savePayload(profileId: Int, payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = payloadKey(profileId))
    }

    actual fun loadCalendarPayload(profileId: Int): String? =
        NSUserDefaults.standardUserDefaults.stringForKey("library_calendar_v1_$profileId")

    actual fun saveCalendarPayload(profileId: Int, payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = "library_calendar_v1_$profileId")
    }
}
