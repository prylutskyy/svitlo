package ua.svitlo.app.domain

/** Published plan times do not assert that an address incident ended or voltage is present. */
fun Schedule.publishedQueuePlan(): Schedule = copy(currentOutage = null, reportedOutages = null,
    outagesExpected = false,outagesUnavailable = false,outagesFetchedAt = null)
