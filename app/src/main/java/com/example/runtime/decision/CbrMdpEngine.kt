package com.example.runtime.decision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Coupled Belief-Resource Markov Decision Process (CBR-MDP) Engine.
 * Implements discrete Bayesian updates on Q in [0, 1], Wasserstein distance,
 * Lyapunov negative drift conditions, T3 queue thresholds, and D1 querying worthiness.
 */
class DiscreteBelief(val numBins: Int = 20) {
    var weights: FloatArray = FloatArray(numBins) { 1.0f / numBins }
        private set

    fun binCenters(): FloatArray {
        return FloatArray(numBins) { (it + 0.5f) / numBins }
    }

    fun expectedValue(): Float {
        val centers = binCenters()
        var sum = 0f
        for (i in 0 until numBins) {
            sum += weights[i] * centers[i]
        }
        return sum
    }

    fun update(observedScore: Float, likelihoodSharpness: Float = 8.0f) {
        val clamped = max(0.0f, min(1.0f, observedScore))
        val centers = binCenters()
        val unnormalized = FloatArray(numBins)
        var total = 0f

        for (i in 0 until numBins) {
            val dist = centers[i] - clamped
            val likelihood = max(1e-6f, 1.0f - likelihoodSharpness * dist * dist)
            unnormalized[i] = weights[i] * likelihood
            total += unnormalized[i]
        }

        if (total > 0f) {
            for (i in 0 until numBins) {
                weights[i] = unnormalized[i] / total
            }
        } else {
            weights = FloatArray(numBins) { 1.0f / numBins }
        }
    }

    fun copy(): DiscreteBelief {
        val b = DiscreteBelief(numBins)
        b.weights = this.weights.copyOf()
        return b
    }

    companion object {
        fun peakedAt(target: Float, numBins: Int = 20, sharpness: Float = 8.0f): DiscreteBelief {
            val belief = DiscreteBelief(numBins)
            belief.update(target, sharpness)
            return belief
        }
    }
}

object CbrMdpEngine {
    fun wassersteinDistance(b1: DiscreteBelief, b2: DiscreteBelief): Float {
        if (b1.numBins != b2.numBins) return 1.0f
        val binWidth = 1.0f / b1.numBins
        var cdf1 = 0f
        var cdf2 = 0f
        var total = 0f

        for (i in 0 until b1.numBins) {
            cdf1 += b1.weights[i]
            cdf2 += b2.weights[i]
            total += abs(cdf1 - cdf2) * binWidth
        }
        return total
    }

    fun computeObservedQuality(
        reviewerScore: Float? = null,
        toolSuccess: Float? = null,
        searchRelevance: Float? = null,
        wReviewer: Float = 0.5f,
        wTool: Float = 0.3f,
        wSearch: Float = 0.2f
    ): Float? {
        var totalWeight = 0f
        var weightedSum = 0f

        if (reviewerScore != null) {
            weightedSum += reviewerScore * wReviewer
            totalWeight += wReviewer
        }
        if (toolSuccess != null) {
            weightedSum += toolSuccess * wTool
            totalWeight += wTool
        }
        if (searchRelevance != null) {
            weightedSum += searchRelevance * wSearch
            totalWeight += wSearch
        }

        if (totalWeight <= 0f) return null
        return max(0.0f, min(1.0f, weightedSum / totalWeight))
    }

    fun isQueryingWorthwhile(
        currentBelief: DiscreteBelief,
        referenceBelief: DiscreteBelief,
        queryBaseCost: Float = 1.0f,
        wassersteinEta: Float = 0.3f,
        discountGamma: Float = 0.9f
    ): Boolean {
        val dist = wassersteinDistance(currentBelief, referenceBelief)
        val expectedDiscountedValue = (discountGamma * wassersteinEta * dist) / max(1e-9f, 1.0f - discountGamma)
        return expectedDiscountedValue > queryBaseCost
    }

    fun exceedsQueueThreshold(queueLength: Int, threshold: Int = 5): Boolean {
        return queueLength > threshold
    }

    fun computeStepCost(
        baseCost: Float,
        queueLength: Int,
        usedBudget: Int,
        totalBudget: Int,
        currentBelief: DiscreteBelief,
        referenceBelief: DiscreteBelief,
        beta: Float = 0.1f,
        safetyMargin: Float = 0.1f,
        barrierPenalty: Float = 50.0f,
        eta: Float = 0.3f
    ): Float {
        val queuePenalty = beta * (queueLength * queueLength)
        val remainingRatio = if (totalBudget > 0) (totalBudget - usedBudget).toFloat() / totalBudget else 0f
        val barrierCost = if (remainingRatio < safetyMargin) barrierPenalty else 0f
        val beliefDistanceCost = eta * wassersteinDistance(currentBelief, referenceBelief)
        return baseCost + queuePenalty + barrierCost + beliefDistanceCost
    }
}
