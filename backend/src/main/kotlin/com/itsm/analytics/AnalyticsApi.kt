package com.itsm.analytics

import com.itsm.common.api.ApiPaths
import com.itsm.common.error.FieldError
import com.itsm.common.error.InvalidInput
import com.itsm.common.spi.TechnicianWorkloadProvider
import org.springframework.beans.factory.ObjectProvider
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

data class SlaCountsDto(
    val tracked: Long,
    val responseMet: Long,
    val responseBreached: Long,
    val responseCompliancePct: Double?,
    val resolutionMet: Long,
    val resolutionBreached: Long,
    val resolutionCompliancePct: Double?
) {
    companion object {
        fun of(c: SlaCounts) = SlaCountsDto(c.tracked, c.responseMet, c.responseBreached, c.responseCompliancePct, c.resolutionMet, c.resolutionBreached, c.resolutionCompliancePct)
    }
}

data class SlaGroupDto(val id: String?, val name: String, val counts: SlaCountsDto)

data class AnalyticsSummary(
    /** Inclusive UTC dates. */
    val from: LocalDate,
    val to: LocalDate,
    val incidents: IncidentVolume,
    val sla: SlaSection,
    val times: Times,
    val escalations: Escalations,
    val perDay: List<DayPoint>,
    val technicians: List<TechnicianRow>
) {
    data class IncidentVolume(
        val reported: Long,
        val resolved: Long,
        /** Right now, whatever the range. */
        val openNow: Long,
        val unassignedNow: Long,
        val openByState: Map<String, Long>,
        val openByPriority: Map<String, Long>
    )
    data class SlaSection(val overall: SlaCountsDto, val byPriority: List<SlaGroupDto>, val byService: List<SlaGroupDto>, val byCategory: List<SlaGroupDto>)
    data class Times(val toRespond: DurationStats, val toResolve: DurationStats)
    data class Escalations(val total: Long, val byTrigger: Map<String, Long>)
}

/**
 * The analytics dashboard's numbers for a range of UTC days (docs/architecture/p4.md section 5). "Reported in the range"
 * drives SLA compliance and times, so a slow incident counts against the period it was reported in.
 */
@Service
@Transactional(readOnly = true)
class AnalyticsService(
    private val queries: AnalyticsQueries,
    private val workload: ObjectProvider<TechnicianWorkloadProvider>,
    private val clock: Clock
) {
    fun summary(fromParam: LocalDate?, toParam: LocalDate?): AnalyticsSummary {
        val today = LocalDate.now(clock.withZone(ZoneOffset.UTC))
        val to = toParam ?: today
        val from = fromParam ?: to.minusDays(DEFAULT_DAYS - 1)
        validate(from, to)
        val start = from.atStartOfDay().toInstant(ZoneOffset.UTC)
        val end = to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)

        val (openByState, openByPriority) = queries.openNow()
        val escalations = queries.escalationsByTrigger(start, end)
        val daily = queries.perDay(start, end)
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { d ->
            DayPoint(d, daily.count("reported", d), daily.count("resolved", d), daily.count("breaches", d), daily.count("escalations", d))
        }.toList()

        val techs = queries.technicians(start, end)
        // Workload is P3's to count (TechnicianWorkloadProvider); without it the column is simply zero.
        val active = workload.getIfAvailable()?.activeIncidentCounts(techs.map { it.userId }).orEmpty()

        return AnalyticsSummary(
            from = from, to = to,
            incidents = AnalyticsSummary.IncidentVolume(
                reported = queries.reported(start, end),
                resolved = queries.resolved(start, end),
                openNow = openByState.values.sum(),
                unassignedNow = openByState["CLASSIFIED"] ?: 0,
                openByState = openByState,
                openByPriority = openByPriority
            ),
            sla = AnalyticsSummary.SlaSection(
                overall = SlaCountsDto.of(queries.slaTotal(start, end)),
                byPriority = queries.slaByPriority(start, end).map { SlaGroupDto(it.id, it.name, SlaCountsDto.of(it.counts)) },
                byService = queries.slaByService(start, end).map { SlaGroupDto(it.id, it.name, SlaCountsDto.of(it.counts)) },
                byCategory = queries.slaByCategory(start, end).map { SlaGroupDto(it.id, it.name, SlaCountsDto.of(it.counts)) }
            ),
            times = AnalyticsSummary.Times(queries.timeToRespond(start, end), queries.timeToResolve(start, end)),
            escalations = AnalyticsSummary.Escalations(escalations.values.sum(), escalations),
            perDay = days,
            technicians = techs.map { TechnicianRow(it.userId, it.name, it.maxWorkload, active[it.userId] ?: 0, it.resolved, it.ratings, it.averageRating?.let(::oneDecimal)) }
        )
    }

    private fun Map<String, Map<LocalDate, Long>>.count(series: String, day: LocalDate) = this[series]?.get(day) ?: 0

    private fun oneDecimal(v: Double) = Math.round(v * 10) / 10.0

    companion object {
        const val DEFAULT_DAYS = 30L
        const val MAX_DAYS = 366L

        fun validate(from: LocalDate, to: LocalDate) {
            val errors = buildList {
                if (from.isAfter(to)) add(FieldError("from", "must not be after to"))
                else if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) add(FieldError("to", "the range can be at most $MAX_DAYS days"))
            }
            if (errors.isNotEmpty()) throw InvalidInput("Invalid date range", errors)
        }
    }
}

@RestController
class AnalyticsController(private val service: AnalyticsService) {

    /** `from` and `to` are inclusive UTC dates (`2026-11-01`); the default is the last 30 days. */
    @GetMapping("${ApiPaths.ADMIN}/analytics/summary")
    @PreAuthorize("hasAuthority('ANALYTICS_VIEW')")
    fun summary(
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?
    ): AnalyticsSummary = service.summary(from, to)
}
