package com.itsm.assignment

import com.itsm.common.error.TechnicianUnavailable
import com.itsm.common.error.WorkloadExceeded

/** Who may receive an incident at all, before any strategy ranks them. */
object EligibilityPolicy {

    /**
     * A technician's own limit if it is positive, otherwise the system default. A non-positive limit on a
     * profile is read as "not set" (an assumption: the admin form does not define 0). If the default is
     * not positive either, the technician has no capacity.
     */
    fun effectiveMax(candidate: Candidate, defaultMaxWorkload: Int): Int =
        candidate.technician.maxWorkload.takeIf { it > 0 } ?: defaultMaxWorkload

    fun hasCapacity(candidate: Candidate, defaultMaxWorkload: Int): Boolean =
        candidate.activeCount < effectiveMax(candidate, defaultMaxWorkload)

    /** Available, under their workload limit, and not excluded. Order is preserved. */
    fun eligible(candidates: List<Candidate>, request: AssignmentRequest, defaultMaxWorkload: Int): List<Candidate> =
        candidates.filter {
            it.technician.available && it.id !in request.excludeTechnicianIds && hasCapacity(it, defaultMaxWorkload)
        }

    /**
     * For a person-chosen (manual) assignment: the technician is validated instead of filtered.
     * Skill is deliberately NOT required here: an admin may assign outside a technician's listed skills.
     *
     * @throws TechnicianUnavailable (409) when the technician is marked unavailable
     * @throws WorkloadExceeded (409) when they are already at their limit
     */
    fun requireCanTake(candidate: Candidate, defaultMaxWorkload: Int) {
        if (!candidate.technician.available) throw TechnicianUnavailable(candidate.id)
        val max = effectiveMax(candidate, defaultMaxWorkload)
        if (candidate.activeCount >= max) throw WorkloadExceeded(candidate.id, candidate.activeCount, max)
    }
}
