package com.github.kr328.clash.common.custom

/** One independently queried provider's normalized carrier result. */
data class CarrierOperatorVote(
    val source: String,
    val operator: String,
)

/** A carrier is accepted only when at least two providers agree. */
data class CarrierOperatorAgreement(
    val operator: String,
    val sources: List<String>,
)

object CarrierOperatorConsensus {
    private val supportedOperators = setOf("电信", "联通", "移动", "广电")

    fun resolve(votes: List<CarrierOperatorVote>): CarrierOperatorAgreement? {
        val valid = votes.filter { it.operator in supportedOperators }
        val winners = valid.groupBy { it.operator }.filterValues { it.size >= 2 }
        if (winners.size != 1) return null
        val (operator, agreeing) = winners.entries.first()
        return CarrierOperatorAgreement(operator, agreeing.map { it.source })
    }

    fun hasDisagreement(votes: List<CarrierOperatorVote>): Boolean =
        votes.asSequence()
            .map { it.operator }
            .filter { it in supportedOperators }
            .distinct()
            .take(2)
            .count() > 1
}
