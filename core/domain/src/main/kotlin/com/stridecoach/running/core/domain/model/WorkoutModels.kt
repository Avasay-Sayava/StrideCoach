package com.stridecoach.running.core.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface SegmentTarget {
    @Serializable
    @SerialName("distance")
    data class Distance(val meters: Int) : SegmentTarget {
        init {
            require(meters > 0) { "Distance target must be positive" }
        }
    }

    @Serializable
    @SerialName("duration")
    data class Duration(val seconds: Int) : SegmentTarget {
        init {
            require(seconds > 0) { "Duration target must be positive" }
        }
    }

    @Serializable
    @SerialName("open")
    data object Open : SegmentTarget
}

@Serializable
enum class StepKind {
    WARM_UP,
    WALK,
    RUN,
    REST,
    COOL_DOWN
}

@Serializable
sealed interface WorkoutSegment {
    @Serializable
    @SerialName("step")
    data class AtomicStep(
        val label: String,
        val kind: StepKind,
        val target: SegmentTarget,
        val paceBounds: PaceBounds? = null
    ) : WorkoutSegment {
        init {
            require(label.isNotBlank()) { "Step label cannot be blank" }
        }
    }

    @Serializable
    @SerialName("repeat")
    data class RepeatBlock(
        val label: String,
        val repetitions: Int,
        val steps: List<AtomicStep>
    ) : WorkoutSegment {
        init {
            require(repetitions > 0) { "Repetitions must be positive" }
            require(steps.isNotEmpty()) { "Repeat block must have at least one step" }
        }
    }
}

@Serializable
data class StructuredWorkout(
    val id: String,
    val title: String,
    val description: String = "",
    val segments: List<WorkoutSegment>,
    val racePhases: List<RacePhase> = emptyList(),
    val deletedAtMillis: Long? = null
) {
    fun flattenSteps(): List<ExecutableStep> {
        val out = mutableListOf<ExecutableStep>()
        for (seg in segments) {
            appendSegment(seg, out)
        }
        return out
    }

    private fun appendSegment(seg: WorkoutSegment, out: MutableList<ExecutableStep>) {
        when (seg) {
            is WorkoutSegment.AtomicStep -> {
                out.add(ExecutableStep(out.size, seg, null, null))
            }
            is WorkoutSegment.RepeatBlock -> appendRepeat(seg, out)
        }
    }

    private fun appendRepeat(block: WorkoutSegment.RepeatBlock, out: MutableList<ExecutableStep>) {
        for (rep in 1..block.repetitions) {
            for (child in block.steps) {
                out.add(ExecutableStep(out.size, child, rep, block.repetitions))
            }
        }
    }

    fun fastestTargetPaceSec(): Int? = flattenSteps()
        .mapNotNull { it.step.paceBounds?.fastSecPerKm }
        .minOrNull()

    fun estimatedDistanceMeters(): Int = flattenSteps().sumOf(::estimateStepMeters)

    private fun estimateStepMeters(exec: ExecutableStep): Int {
        return when (val t = exec.step.target) {
            is SegmentTarget.Distance -> t.meters
            is SegmentTarget.Duration -> {
                val pace = exec.step.paceBounds?.midpointSecPerKm() ?: 390
                ((t.seconds.toDouble() / pace) * 1000.0).toInt()
            }
            SegmentTarget.Open -> 0
        }
    }
}

data class ExecutableStep(
    val index: Int,
    val step: WorkoutSegment.AtomicStep,
    val repeatIndex: Int?,
    val repeatTotal: Int?
) {
    fun displayName(): String = if (repeatIndex != null && repeatTotal != null) {
        "${step.label} ($repeatIndex/$repeatTotal)"
    } else {
        step.label
    }
}

@Serializable
data class RacePhase(
    val name: String,
    val startMeters: Int,
    val endMeters: Int,
    val paceBounds: PaceBounds
) {
    init {
        require(startMeters >= 0 && endMeters > startMeters) { "Invalid phase bounds" }
    }
}
