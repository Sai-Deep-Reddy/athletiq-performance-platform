package com.athletiq.core.domain

import com.athletiq.core.model.ScoringStandardSpec
import com.athletiq.core.model.TestType

data class ScoringBand(
    val label: String,
    val minValue: Double?,
    val maxValue: Double?,
    val points: Double?,
    val qualified: Boolean = false,
)

data class ConfiguredScoringRubric(
    val spec: ScoringStandardSpec,
    val direction: String = "higher_is_better", // or "lower_is_better"
    val bands: List<ScoringBand>,
)

sealed interface ScoringEvaluationOutcome {
    data class Evaluated(
        val standardId: String,
        val standardName: String,
        val sourceCitation: String,
        val categoryLabel: String?,
        val points: Double?,
        val qualified: Boolean?,
    ) : ScoringEvaluationOutcome

    data class NoStandardConfigured(val reason: String) : ScoringEvaluationOutcome
}

/**
 * Pure Kotlin configurable performance scoring evaluator.
 * Never invents qualification cutoffs when no matching rubric is configured.
 */
object ScoringEvaluator {
    fun evaluate(
        value: Double,
        testType: TestType,
        ageYears: Int?,
        genderCategory: String?,
        discipline: String?,
        rubrics: List<ConfiguredScoringRubric>,
    ): ScoringEvaluationOutcome {
        val matching = rubrics
            .filter { r ->
                r.spec.testType == testType &&
                    (ageYears == null || ageYears in r.spec.ageMin..r.spec.ageMax) &&
                    (r.spec.genderCategory == null || r.spec.genderCategory == genderCategory) &&
                    (r.spec.discipline == null || r.spec.discipline == discipline)
            }
            .maxByOrNull { r ->
                (if (r.spec.genderCategory != null) 2 else 0) + (if (r.spec.discipline != null) 1 else 0)
            }
            ?: return ScoringEvaluationOutcome.NoStandardConfigured(
                "No institution scoring standard configured for $testType",
            )

        val matchedBand = matching.bands.firstOrNull { b ->
            val minOk = b.minValue == null || value >= b.minValue
            val maxOk = b.maxValue == null || value <= b.maxValue
            minOk && maxOk
        } ?: matching.bands.let { bands ->
            if (bands.isEmpty()) null
            else if (matching.direction == "lower_is_better") {
                val sorted = bands.sortedBy { it.maxValue ?: Double.POSITIVE_INFINITY }
                if (value <= (sorted.first().maxValue ?: value)) sorted.first() else sorted.last()
            } else {
                val sorted = bands.sortedBy { it.minValue ?: Double.NEGATIVE_INFINITY }
                if (value < (sorted.first().minValue ?: value)) sorted.first() else sorted.last()
            }
        }

        return ScoringEvaluationOutcome.Evaluated(
            standardId = matching.spec.id,
            standardName = matching.spec.name,
            sourceCitation = matching.spec.sourceCitation,
            categoryLabel = matchedBand?.label,
            points = matchedBand?.points,
            qualified = matchedBand?.qualified,
        )
    }
}
