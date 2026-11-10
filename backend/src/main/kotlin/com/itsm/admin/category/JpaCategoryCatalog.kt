package com.itsm.admin.category

import com.itsm.common.spi.CategoryCatalog
import com.itsm.common.spi.CategoryView
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * P1's implementation of the shared [CategoryCatalog] SPI. Returns immutable [CategoryView]s, never entities.
 * Read-only transactions join the caller's transaction when there is one (e.g. P2's incident creation).
 *
 * Semantics (the interface leaves these open; documented in docs/architecture/integration-guide.md):
 *  - [findById] / [findByCode] return active AND inactive categories, so historical incidents can still be shown;
 *    use `requireActive` to validate a choice for a new incident.
 *  - [findByCode] matches the stored code exactly (codes are UPPER_SNAKE_CASE).
 *  - [listActive] is ordered by name; keywords inside each view are normalised and sorted.
 */
@Component
@Transactional(readOnly = true)
class JpaCategoryCatalog(private val categories: CategoryRepository) : CategoryCatalog {

    override fun findById(categoryId: Long): CategoryView? = categories.findById(categoryId).orElse(null)?.toView()

    override fun findByCode(code: String): CategoryView? = categories.findByCode(code)?.toView()

    override fun listActive(): List<CategoryView> = categories.findAllByActiveTrueOrderByNameAsc().map { it.toView() }

    private fun CategoryEntity.toView() = CategoryView(
        id = requireId(),
        code = code,
        name = name,
        description = description,
        keywords = java.util.Collections.unmodifiableList(keywords.sorted()),
        active = active
    )
}
