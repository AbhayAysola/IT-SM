package com.itsm.lifecycle

import com.itsm.assignment.AssignmentDecision
import com.itsm.common.api.ApiPaths
import com.itsm.common.api.PageQuery
import com.itsm.common.error.InvalidInput
import com.itsm.common.error.ResourceNotFound
import com.itsm.common.model.Priority
import com.itsm.common.security.CurrentUserProvider
import com.itsm.feedback.Feedback
import com.itsm.investigation.InvestigationEntry
import com.itsm.investigation.InvestigationEntryType
import com.itsm.investigation.Visibility
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class IncidentDto(
    val id: String, val title: String, val description: String, val createdAt: Instant, val updatedAt: Instant, val state: String, val reporterId: Long, val assigneeId: Long?, val categoryId: Long?, val serviceId: Long?,
    val priority: Priority?, val assignedAt: Instant?, val firstResponseAt: Instant?, val resolvedAt: Instant?, val closedAt: Instant?,
    /** What the caller can do right now (drives which buttons to show). Moves are `POST .../transitions`. */
    val availableTransitions: List<String>
)

/** A row of the list: no transitions or description, so a page stays small. */
data class IncidentSummaryDto(
    val id: String, val title: String, val state: String, val priority: Priority?, val reporterId: Long, val assigneeId: Long?,
    val categoryId: Long?, val serviceId: Long?, val createdAt: Instant, val updatedAt: Instant, val resolvedAt: Instant?,
    /** True once the reporter has rated it; lets a UI decide whether feedback is still due. */
    val hasFeedback: Boolean
)

data class IncidentListDto(
    val items: List<IncidentSummaryDto>, val page: Int, val size: Int, val totalItems: Long, val totalPages: Int,
    /** Incidents per state across everything the caller may see, ignoring the filters. */
    val stateCounts: Map<String, Int>
)

data class TimelineEntryDto(
    val type: String, val authorId: Long, val visibility: String, val body: String, val incidentState: String, val createdAt: Instant
)

data class FeedbackDto(val rating: Int, val comment: String?, val submittedAt: Instant)

data class RecommendationDto(val technicianId: Long, val name: String, val score: Double, val reasons: List<String>)

data class AssignmentResultDto(
    val incident: IncidentDto,
    /** `ASSIGNED`, `MANUAL_REQUIRED`, `NO_ELIGIBLE_TECHNICIAN`, or `MANUAL` for a technician chosen by name. */
    val outcome: String,
    val strategy: String?,
    val fellBackFrom: String?,
    val recommendations: List<RecommendationDto>
)

data class ResolvedIncidentDto(val incidentId: String, val title: String, val resolution: String, val resolvedAt: Instant, val sameService: Boolean)

data class TransitionRequest(@field:NotNull val transition: IncidentTransition?, val reason: String? = null)
data class AssignRequest(/** Omit to let the configured strategy choose. */ val technicianId: Long? = null, val reason: String? = null)
data class NoteRequest(@field:NotNull val type: InvestigationEntryType?, val body: String = "", val visibility: Visibility? = null)
data class FeedbackRequest(@field:NotNull val rating: Int?, val comment: String? = null)

/** P3's endpoints on an existing incident. Creating incidents is P2's. All rules live in the services. */
@RestController
@RequestMapping(ApiPaths.INCIDENTS)
class IncidentController(
    private val lifecycle: IncidentLifecycleService,
    private val assignment: IncidentAssignmentService,
    private val currentUser: CurrentUserProvider
) {
    /** The caller's incidents (an admin's: all). `states` is a comma-separated list of states; `q` matches id or title. */
    @GetMapping
    fun list(
        @RequestParam(required = false) states: List<String>?,
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): IncidentListDto {
        val paging = PageQuery(page, size)
        val wanted = states.orEmpty().filter { it.isNotBlank() }.map {
            IncidentState.entries.firstOrNull { s -> s.name == it.trim().uppercase() } ?: throw InvalidInput("Unknown state '${it.take(30)}'")
        }.toSet()
        val result = lifecycle.list(currentUser.current(), wanted, q, paging.page, paging.size)
        return IncidentListDto(
            result.rows.map { (r, fb) -> IncidentSummaryDto(r.id, r.title, r.state.name, r.priority, r.reporterId, r.assigneeId, r.categoryId, r.serviceId, r.createdAt, r.updatedAt, r.resolvedAt, fb) },
            paging.page, paging.size, result.total, ((result.total + paging.size - 1) / paging.size).toInt(),
            result.stateCounts.mapKeys { it.key.name }
        )
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): IncidentDto = lifecycle.get(id, currentUser.current()).let(::dto)

    @PostMapping("/{id}/transitions")
    fun transition(@PathVariable id: String, @Valid @RequestBody body: TransitionRequest): IncidentDto =
        lifecycle.transition(id, body.transition!!, body.reason, currentUser.current()).let(::dto)

    @PostMapping("/{id}/assignment")
    fun assign(@PathVariable id: String, @Valid @RequestBody body: AssignRequest): AssignmentResultDto {
        val user = currentUser.current()
        if (body.technicianId != null) {
            val incident = assignment.assignTo(id, body.technicianId, body.reason, user)
            return AssignmentResultDto(dto(incident), "MANUAL", "MANUAL", null, emptyList())
        }
        val result = assignment.assignAutomatically(id, body.reason, user)
        return AssignmentResultDto(
            dto(result.incident), result.decision.outcome.name, result.decision.strategyKey, result.decision.fellBackFrom,
            recommendations(result.decision)
        )
    }

    @GetMapping("/{id}/recommendations")
    fun recommendations(@PathVariable id: String): List<RecommendationDto> =
        recommendations(assignment.recommendations(id, currentUser.current()))

    @GetMapping("/{id}/timeline")
    fun timeline(@PathVariable id: String): List<TimelineEntryDto> =
        lifecycle.timeline(id, currentUser.current()).map(::dto)

    @PostMapping("/{id}/timeline")
    @ResponseStatus(HttpStatus.CREATED)
    fun addNote(@PathVariable id: String, @Valid @RequestBody body: NoteRequest): TimelineEntryDto =
        dto(lifecycle.addNote(id, body.type!!, body.body, body.visibility, currentUser.current()))

    @GetMapping("/{id}/feedback")
    fun feedback(@PathVariable id: String): FeedbackDto =
        lifecycle.feedback(id, currentUser.current())?.let(::dto) ?: throw ResourceNotFound("Feedback", id)

    @PostMapping("/{id}/feedback")
    fun submitFeedback(@PathVariable id: String, @Valid @RequestBody body: FeedbackRequest): ResponseEntity<FeedbackDto> =
        ResponseEntity.status(HttpStatus.CREATED).body(dto(lifecycle.submitFeedback(id, body.rating!!, body.comment, currentUser.current())))

    @GetMapping("/{id}/similar")
    fun similar(@PathVariable id: String, @RequestParam(defaultValue = "5") limit: Int): List<ResolvedIncidentDto> =
        lifecycle.similarResolved(id, currentUser.current(), limit).map { ResolvedIncidentDto(it.incidentId, it.title, it.resolution, it.resolvedAt, it.sameService) }

    private fun dto(r: IncidentRecord) = IncidentDto(
        r.id, r.title, r.description, r.createdAt, r.updatedAt, r.state.name, r.reporterId, r.assigneeId, r.categoryId, r.serviceId, r.priority, r.assignedAt, r.firstResponseAt,
        r.resolvedAt, r.closedAt, lifecycle.availableTransitions(r, currentUser.current()).map { it.name }
    )

    private fun dto(e: InvestigationEntry) = TimelineEntryDto(e.type.name, e.authorId, e.visibility.name, e.body, e.incidentState.name, e.createdAt)
    private fun dto(f: Feedback) = FeedbackDto(f.rating, f.comment, f.submittedAt)
    private fun recommendations(d: AssignmentDecision) =
        d.recommendations.map { RecommendationDto(it.technician.userId, it.technician.name, it.score, it.reasons) }
}
