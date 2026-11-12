package com.itsm.integration

import com.fasterxml.jackson.databind.JsonNode
import com.itsm.admin.AdminApiTestSupport
import com.itsm.admin.Caller
import com.itsm.auth.JwtService
import com.itsm.common.model.Role
import com.itsm.common.security.ItsmUserDetails
import com.itsm.common.security.Permission
import com.itsm.user.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/** One HTTP operation discovered from the running application's handler mappings (not from a hand-kept list). */
private data class Route(val method: HttpMethod, val pattern: String) {
    val key get() = "${method.name()} $pattern"
    val isPublic get() = pattern == "/api/auth/login" || pattern == "/api/auth/register"
    val isAdmin get() = pattern.startsWith("/api/admin/")
    val hasBody get() = method in setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH)

    /** A concrete URL with plausible ids. It is deliberately NOT a valid request: it is used to prove who is refused. */
    fun url(): String = pattern
        .replace("{eventId}", UUID.randomUUID().toString())
        .replace("{incidentId}", "INC-1")
        .replace(Regex("\\{[^/}]+}"), "1")
}

/** A fully valid request for one route, built against freshly created data so it can succeed. */
private data class Probe(val method: HttpMethod, val path: String, val body: Any? = null, val params: Map<String, String> = emptyMap())

/**
 * Authorization matrix over EVERY /api route the application exposes. Routes are discovered at runtime, so a future
 * endpoint that forgets authorization (or has no probe here) fails without anyone editing the discovery.
 *
 * The expected permission of each admin area is stated in [AREAS], independently of the code under test, and checked at
 * three levels so that a route wrongly requiring a DIFFERENT permission is caught, not only a route open to everyone:
 *  1. structure: every admin handler declares an HTTP method and a `@PreAuthorize` naming exactly the area's permission;
 *  2. reach: a principal holding ONLY that permission gets a 2xx from a valid request to every route of the area;
 *  3. refusal: a principal holding ONLY any other permission, and real EMPLOYEE / TECHNICIAN users, get 403 on every
 *     route even with an invalid body (so the decision is made in the filter chain, before request validation).
 * Anonymous callers get 401 on everything except login and register.
 *
 * The single-permission principals carry an ADMIN-role identity but exactly one authority. The URL rules and
 * `@PreAuthorize` evaluate the authority, so this isolates them; the services' own role-based check (a third,
 * redundant layer) is satisfied by the ADMIN role and is not what is being tested here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControllableProvidersConfig::class) // a present strategy catalog, so PUT /assignment-settings can succeed
@Testcontainers(disabledWithoutDocker = true)
class RouteAuthorizationMatrixIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")
        private const val ADMIN = "/api/admin"

        /** Expected permission per admin area: the test's own statement of the intended design. */
        private val AREAS: List<Pair<String, Permission>> = listOf(
            "$ADMIN/users" to Permission.USER_MANAGE,
            "$ADMIN/technicians" to Permission.USER_MANAGE,
            "$ADMIN/categories" to Permission.CATALOG_MANAGE,
            "$ADMIN/services" to Permission.CATALOG_MANAGE,
            "$ADMIN/sla-policies" to Permission.SLA_CONFIGURE,
            "$ADMIN/assignment-settings" to Permission.ASSIGNMENT_CONFIGURE,
            "$ADMIN/audit-events" to Permission.AUDIT_VIEW
        )
        private val ADMIN_PERMISSIONS = AREAS.map { it.second }.toSet()
        private val HAS_AUTHORITY = Regex("""hasAuthority\('([A-Z_]+)'\)""")

        fun expectedPermission(pattern: String): Permission? =
            AREAS.firstOrNull { (prefix, _) -> pattern == prefix || pattern.startsWith("$prefix/") }?.second
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired @Qualifier("requestMappingHandlerMapping") lateinit var mappings: RequestMappingHandlerMapping

    private lateinit var t: AdminApiTestSupport
    private lateinit var admin: Caller
    private lateinit var employee: Caller
    private lateinit var technician: Caller

    @BeforeEach fun setUp() {
        t = AdminApiTestSupport(users, jwt, jdbc)
        admin = t.caller(Role.ADMIN); employee = t.caller(Role.EMPLOYEE); technician = t.caller(Role.TECHNICIAN)
    }

    // --- discovery ----------------------------------------------------------------------------------------------------

    private fun routes(): List<Route> = mappings.handlerMethods.keys.flatMap { info ->
        val patterns = info.pathPatternsCondition?.patternValues.orEmpty()
        val methods = info.methodsCondition.methods.map { HttpMethod.valueOf(it.name) }
        patterns.filter { it.startsWith("/api/") }.flatMap { p -> methods.map { Route(it, p) } }
    }.distinct().sortedWith(compareBy({ it.pattern }, { it.method.name() }))

    private fun adminRoutes() = routes().filter { it.isAdmin }

    // --- setup helpers (real ADMIN JWT: creates the data the probes act on) -------------------------------------------

    private fun letters(n: Int = 8) = UUID.randomUUID().toString().replace("-", "").take(n).map { 'a' + it.digitToInt(16) }.joinToString("")

    private fun api(method: HttpMethod, path: String, body: Any? = null, expect: Int = 200): JsonNode {
        val response = mvc.perform(
            request(method, path).header("Authorization", admin.bearer).apply {
                if (body != null) contentType(MediaType.APPLICATION_JSON).content(t.json.writeValueAsString(body))
            }
        ).andReturn().response
        assertEquals(expect, response.status, "setup $method $path -> ${response.contentAsString}")
        return t.json.readTree(response.contentAsString)
    }

    private fun category(active: Boolean = true): JsonNode {
        val c = api(HttpMethod.POST, "$ADMIN/categories", mapOf("code" to "RM_" + letters().uppercase(), "name" to "Cat " + letters(), "keywords" to listOf("kw" + letters())), 201)
        return if (active) c else api(HttpMethod.POST, "$ADMIN/categories/${c["id"].asLong()}/deactivate")
    }

    private fun service(active: Boolean = true): JsonNode {
        val s = api(HttpMethod.POST, "$ADMIN/services", mapOf("name" to "Svc " + letters(), "businessCriticality" to 3), 201)
        return if (active) s else api(HttpMethod.POST, "$ADMIN/services/${s["id"].asLong()}/deactivate")
    }

    private fun policy(active: Boolean = true): JsonNode {
        val p = api(
            HttpMethod.POST, "$ADMIN/sla-policies",
            mapOf("serviceId" to service()["id"].asLong(), "priority" to "P3", "responseMinutes" to 30, "resolutionMinutes" to 240), 201
        )
        return if (active) p else api(HttpMethod.POST, "$ADMIN/sla-policies/${p["id"].asLong()}/deactivate")
    }

    private fun user(active: Boolean = true): JsonNode {
        val id = t.caller(Role.EMPLOYEE).id
        if (!active) api(HttpMethod.POST, "$ADMIN/users/$id/deactivate", mapOf("version" to 0))
        return api(HttpMethod.GET, "$ADMIN/users/$id")
    }

    private fun technicianProfile(active: Boolean = true): JsonNode {
        val id = t.caller(Role.TECHNICIAN).id
        val profile = api(
            HttpMethod.POST, "$ADMIN/technicians",
            mapOf("userId" to id, "maxWorkload" to 5, "experienceYears" to 1, "available" to true, "isTeamLead" to false), 201
        )
        return if (active) profile else api(HttpMethod.POST, "$ADMIN/technicians/$id/deactivate", mapOf("version" to profile["version"].asLong()))
    }

    /** One valid request per route, keyed by "METHOD pattern". Each builds its own fresh data, so probes are independent. */
    private val probes: Map<String, () -> Probe> = mapOf(
        "GET $ADMIN/users" to { Probe(HttpMethod.GET, "$ADMIN/users") },
        "GET $ADMIN/users/{id}" to { Probe(HttpMethod.GET, "$ADMIN/users/${user()["id"].asLong()}") },
        "POST $ADMIN/users" to {
            Probe(HttpMethod.POST, "$ADMIN/users", mapOf("email" to "rm-${letters()}@campus.example", "name" to "Probe", "password" to "Correct-Horse-9x", "role" to "EMPLOYEE"))
        },
        "PUT $ADMIN/users/{id}" to {
            val u = user(); Probe(HttpMethod.PUT, "$ADMIN/users/${u["id"].asLong()}", mapOf("email" to "rm-${letters()}@campus.example", "name" to "Renamed", "version" to u["version"].asLong()))
        },
        "PUT $ADMIN/users/{id}/role" to {
            val u = user(); Probe(HttpMethod.PUT, "$ADMIN/users/${u["id"].asLong()}/role", mapOf("role" to "TECHNICIAN", "version" to u["version"].asLong()))
        },
        "POST $ADMIN/users/{id}/activate" to {
            val u = user(active = false); Probe(HttpMethod.POST, "$ADMIN/users/${u["id"].asLong()}/activate", mapOf("version" to u["version"].asLong()))
        },
        "POST $ADMIN/users/{id}/deactivate" to {
            val u = user(); Probe(HttpMethod.POST, "$ADMIN/users/${u["id"].asLong()}/deactivate", mapOf("version" to u["version"].asLong()))
        },
        "POST $ADMIN/users/{id}/reset-password" to {
            Probe(HttpMethod.POST, "$ADMIN/users/${user()["id"].asLong()}/reset-password", mapOf("password" to "Another-Pass-77"))
        },
        "POST $ADMIN/users/{id}/revoke-sessions" to { Probe(HttpMethod.POST, "$ADMIN/users/${user()["id"].asLong()}/revoke-sessions") },

        "GET $ADMIN/technicians" to { Probe(HttpMethod.GET, "$ADMIN/technicians") },
        "GET $ADMIN/technicians/{userId}" to { Probe(HttpMethod.GET, "$ADMIN/technicians/${technicianProfile()["userId"].asLong()}") },
        "POST $ADMIN/technicians" to {
            Probe(
                HttpMethod.POST, "$ADMIN/technicians",
                mapOf("userId" to t.caller(Role.TECHNICIAN).id, "maxWorkload" to 5, "experienceYears" to 1, "available" to true, "isTeamLead" to false)
            )
        },
        "PUT $ADMIN/technicians/{userId}" to {
            val p = technicianProfile()
            Probe(HttpMethod.PUT, "$ADMIN/technicians/${p["userId"].asLong()}", mapOf("maxWorkload" to 6, "experienceYears" to 2, "available" to true, "isTeamLead" to false, "version" to p["version"].asLong()))
        },
        "PUT $ADMIN/technicians/{userId}/skills" to {
            val p = technicianProfile()
            Probe(HttpMethod.PUT, "$ADMIN/technicians/${p["userId"].asLong()}/skills", mapOf("skills" to listOf(mapOf("categoryId" to category()["id"].asLong(), "proficiency" to 3)), "version" to p["version"].asLong()))
        },
        "POST $ADMIN/technicians/{userId}/activate" to {
            val p = technicianProfile(active = false); Probe(HttpMethod.POST, "$ADMIN/technicians/${p["userId"].asLong()}/activate", mapOf("version" to p["version"].asLong()))
        },
        "POST $ADMIN/technicians/{userId}/deactivate" to {
            val p = technicianProfile(); Probe(HttpMethod.POST, "$ADMIN/technicians/${p["userId"].asLong()}/deactivate", mapOf("version" to p["version"].asLong()))
        },

        "GET $ADMIN/categories" to { Probe(HttpMethod.GET, "$ADMIN/categories") },
        "GET $ADMIN/categories/{id}" to { Probe(HttpMethod.GET, "$ADMIN/categories/${category()["id"].asLong()}") },
        "POST $ADMIN/categories" to {
            Probe(HttpMethod.POST, "$ADMIN/categories", mapOf("code" to "RM_" + letters().uppercase(), "name" to "Cat " + letters(), "keywords" to listOf("kw" + letters())))
        },
        "PUT $ADMIN/categories/{id}" to {
            val c = category(); Probe(HttpMethod.PUT, "$ADMIN/categories/${c["id"].asLong()}", mapOf("name" to "Cat " + letters(), "keywords" to listOf("kw" + letters()), "version" to c["version"].asLong()))
        },
        "POST $ADMIN/categories/{id}/activate" to { Probe(HttpMethod.POST, "$ADMIN/categories/${category(active = false)["id"].asLong()}/activate") },
        "POST $ADMIN/categories/{id}/deactivate" to { Probe(HttpMethod.POST, "$ADMIN/categories/${category()["id"].asLong()}/deactivate") },

        "GET $ADMIN/services" to { Probe(HttpMethod.GET, "$ADMIN/services") },
        "GET $ADMIN/services/{id}" to { Probe(HttpMethod.GET, "$ADMIN/services/${service()["id"].asLong()}") },
        "POST $ADMIN/services" to { Probe(HttpMethod.POST, "$ADMIN/services", mapOf("name" to "Svc " + letters(), "businessCriticality" to 3)) },
        "PUT $ADMIN/services/{id}" to {
            val s = service(); Probe(HttpMethod.PUT, "$ADMIN/services/${s["id"].asLong()}", mapOf("name" to "Svc " + letters(), "businessCriticality" to 4, "dependsOnServiceIds" to emptyList<Long>(), "version" to s["version"].asLong()))
        },
        "POST $ADMIN/services/{id}/activate" to { Probe(HttpMethod.POST, "$ADMIN/services/${service(active = false)["id"].asLong()}/activate") },
        "POST $ADMIN/services/{id}/deactivate" to { Probe(HttpMethod.POST, "$ADMIN/services/${service()["id"].asLong()}/deactivate") },

        "GET $ADMIN/sla-policies" to { Probe(HttpMethod.GET, "$ADMIN/sla-policies") },
        "GET $ADMIN/sla-policies/effective" to {
            Probe(HttpMethod.GET, "$ADMIN/sla-policies/effective", params = mapOf("serviceId" to service()["id"].asText(), "priority" to "P2"))
        },
        "GET $ADMIN/sla-policies/{id}" to { Probe(HttpMethod.GET, "$ADMIN/sla-policies/${policy()["id"].asLong()}") },
        "POST $ADMIN/sla-policies" to {
            Probe(HttpMethod.POST, "$ADMIN/sla-policies", mapOf("serviceId" to service()["id"].asLong(), "priority" to "P3", "responseMinutes" to 30, "resolutionMinutes" to 240))
        },
        "PUT $ADMIN/sla-policies/{id}" to {
            val p = policy(); Probe(HttpMethod.PUT, "$ADMIN/sla-policies/${p["id"].asLong()}", mapOf("responseMinutes" to 20, "resolutionMinutes" to 200, "warningThresholdPct" to 75, "version" to p["version"].asLong()))
        },
        "POST $ADMIN/sla-policies/{id}/activate" to { Probe(HttpMethod.POST, "$ADMIN/sla-policies/${policy(active = false)["id"].asLong()}/activate") },
        "POST $ADMIN/sla-policies/{id}/deactivate" to { Probe(HttpMethod.POST, "$ADMIN/sla-policies/${policy()["id"].asLong()}/deactivate") },

        "GET $ADMIN/assignment-settings" to { Probe(HttpMethod.GET, "$ADMIN/assignment-settings") },
        "PUT $ADMIN/assignment-settings" to {
            val current = api(HttpMethod.GET, "$ADMIN/assignment-settings")
            Probe(HttpMethod.PUT, "$ADMIN/assignment-settings", mapOf("activeStrategy" to "SKILL_BASED", "defaultMaxWorkload" to 6, "categoryOverrides" to emptyList<Any>(), "version" to current["version"].asLong()))
        },

        "GET $ADMIN/audit-events" to { Probe(HttpMethod.GET, "$ADMIN/audit-events") },
        "GET $ADMIN/audit-events/{eventId}" to {
            category() // guarantees at least one audit event exists
            Probe(HttpMethod.GET, "$ADMIN/audit-events/${api(HttpMethod.GET, "$ADMIN/audit-events?size=1")["items"][0]["id"].asText()}")
        }
    )

    // --- principals -------------------------------------------------------------------------------------------------

    /** An ADMIN-role identity holding exactly one authority, or none. */
    private fun holding(vararg permissions: Permission): RequestPostProcessor {
        val principal = ItsmUserDetails(admin.id, admin.email, "", Role.ADMIN)
        return authentication(UsernamePasswordAuthenticationToken(principal, null, permissions.map { SimpleGrantedAuthority(it.name) }))
    }

    private fun send(route: Route, who: RequestPostProcessor?, bearer: String? = null): Int = mvc.perform(
        request(route.method, route.url()).apply {
            if (who != null) with(who)
            if (bearer != null) header("Authorization", bearer)
            if (route.hasBody) contentType(MediaType.APPLICATION_JSON).content("{}")   // invalid on purpose
        }
    ).andReturn().response.status

    private fun sendProbe(probe: Probe, who: RequestPostProcessor): Int = mvc.perform(
        request(probe.method, probe.path).with(who).apply {
            probe.params.forEach { (k, v) -> param(k, v) }
            if (probe.body != null) contentType(MediaType.APPLICATION_JSON).content(t.json.writeValueAsString(probe.body))
        }
    ).andReturn().response.status

    // --- 1. structure -----------------------------------------------------------------------------------------------

    @Test fun `route discovery sees the whole P1 surface`() {
        val all = routes()
        assertTrue(all.count { it.isAdmin } >= 39, "expected the admin surface to be discovered, got ${all.count { it.isAdmin }} routes")
        assertTrue(all.any { it.pattern == "/api/incidents/{incidentId}/audit" } && all.any { it.pattern == "/api/users/me" })
    }

    @Test fun `every admin handler declares an HTTP method and a PreAuthorize naming exactly its area's permission`() {
        val violations = mutableListOf<String>()
        mappings.handlerMethods.forEach { (info, handler) ->
            val patterns = info.pathPatternsCondition?.patternValues.orEmpty().filter { it.startsWith("$ADMIN/") }
            if (patterns.isEmpty()) return@forEach
            val name = "${handler.beanType.simpleName}.${handler.method.name} $patterns"
            if (info.methodsCondition.methods.isEmpty()) violations += "$name: no HTTP method (it would be invisible to the matrix)"
            val annotation = AnnotatedElementUtils.findMergedAnnotation(handler.method, PreAuthorize::class.java)
                ?: AnnotatedElementUtils.findMergedAnnotation(handler.beanType, PreAuthorize::class.java)
            if (annotation == null) { violations += "$name: no @PreAuthorize on the method or the class"; return@forEach }
            val named = HAS_AUTHORITY.find(annotation.value)?.groupValues?.get(1)
            patterns.forEach { pattern ->
                val expected = expectedPermission(pattern)
                when {
                    expected == null -> violations += "$name: $pattern is not in a known admin area (add it to AREAS)"
                    named != expected.name -> violations += "$name: @PreAuthorize says '${annotation.value}' but the area requires ${expected.name}"
                }
            }
        }
        assertTrue(violations.isEmpty(), "authorization structure violations:\n" + violations.joinToString("\n"))
    }

    @Test fun `every admin route has exactly one valid probe and no probe is stale`() {
        val discovered = adminRoutes().map { it.key }.toSet()
        assertEquals(discovered, probes.keys, "routes without a probe: ${discovered - probes.keys}; probes without a route: ${probes.keys - discovered}")
    }

    // --- 2. anonymous -----------------------------------------------------------------------------------------------

    @Test fun `anonymous callers get 401 on every protected route`() {
        val wrong = routes().filterNot { it.isPublic }.mapNotNull { r -> send(r, null).takeIf { it != 401 }?.let { "${r.key} -> $it" } }
        assertTrue(wrong.isEmpty(), "anonymous access was not rejected with 401:\n" + wrong.joinToString("\n"))
    }

    // --- 3. reach: the right single permission, a valid request --------------------------------------------------------

    @Test fun `a principal holding only the area's permission reaches every admin route with a valid request`() {
        val wrong = adminRoutes().mapNotNull { route ->
            val permission = expectedPermission(route.pattern)!!
            val probe = probes.getValue(route.key)()
            val status = sendProbe(probe, holding(permission))
            status.takeUnless { it in 200..299 }?.let { "${route.key} with only ${permission.name} -> $it" }
        }
        assertTrue(wrong.isEmpty(), "a route is not reachable with its own permission (or needs a different one):\n" + wrong.joinToString("\n"))
    }

    // --- 4. refusal: any other single permission, and the real non-admin roles --------------------------------------------

    @Test fun `a principal holding only a DIFFERENT admin permission gets 403 on every route, even with an invalid body`() {
        val wrong = adminRoutes().flatMap { route ->
            val required = expectedPermission(route.pattern)!!
            (ADMIN_PERMISSIONS - required).mapNotNull { other ->
                send(route, holding(other)).takeIf { it != 403 }?.let { "${route.key} with only ${other.name} -> $it (requires ${required.name})" }
            }
        }
        assertTrue(wrong.isEmpty(), "a route accepted or mishandled the wrong permission:\n" + wrong.joinToString("\n"))
    }

    @Test fun `a principal with no authority at all gets 403 on every admin route`() {
        val wrong = adminRoutes().mapNotNull { r -> send(r, holding()).takeIf { it != 403 }?.let { "${r.key} -> $it" } }
        assertTrue(wrong.isEmpty(), "an authority-less principal was not refused with 403:\n" + wrong.joinToString("\n"))
    }

    @Test fun `employees and technicians get 403 on every admin route, even with an invalid body`() {
        val wrong = adminRoutes().flatMap { r ->
            listOf(employee, technician).mapNotNull { caller ->
                send(r, null, caller.bearer).takeIf { it != 403 }?.let { "${caller.role} ${r.key} -> $it" }
            }
        }
        assertTrue(wrong.isEmpty(), "non-admin callers were not rejected with 403:\n" + wrong.joinToString("\n"))
    }
}
