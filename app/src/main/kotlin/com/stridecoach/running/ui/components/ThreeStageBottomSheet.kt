package com.stridecoach.running.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stridecoach.running.ui.theme.KdeBorder
import com.stridecoach.running.ui.theme.KdeCardBg
import com.stridecoach.running.ui.theme.KdeMuted
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

enum class SheetDetent { PEEK, HALF, FULL }

class ThreeStageSheetState(initial: SheetDetent = SheetDetent.PEEK) {
    var detent by mutableStateOf(initial)
        internal set
    var heightPx by mutableIntStateOf(0)
        internal set

    internal var targetAnim: (suspend (SheetDetent) -> Unit)? = null

    suspend fun animateTo(next: SheetDetent) {
        detent = next
        targetAnim?.invoke(next)
    }
}

@Composable
fun rememberThreeStageSheetState(initial: SheetDetent = SheetDetent.PEEK): ThreeStageSheetState =
    remember { ThreeStageSheetState(initial) }

@Composable
fun ThreeStageBottomSheet(
    state: ThreeStageSheetState,
    peekHeightDp: Dp,
    halfFraction: Float = 0.50f,
    fullFraction: Float = 0.90f,
    sheetContent: @Composable ColumnScope.() -> Unit,
    content: @Composable () -> Unit
) {
    val d = LocalDensity.current
    val scope = rememberCoroutineScope()
    val anim = remember { Animatable(0f) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val totPx = with(d) { maxHeight.toPx() }.coerceAtLeast(300f)
        val peekPx = with(d) { peekHeightDp.toPx() }.coerceIn(140f, totPx * 0.45f)
        val halfPx = (totPx * halfFraction).coerceIn(peekPx + 80f, totPx * 0.72f)
        val fullPx = (totPx * fullFraction).coerceAtLeast(halfPx + 80f)
        val anchors = remember(peekPx, halfPx, fullPx) {
            mapOf(SheetDetent.PEEK to peekPx, SheetDetent.HALF to halfPx, SheetDetent.FULL to fullPx)
        }
        LaunchedEffect(anchors) {
            if (anim.value <= 1f) anim.snapTo(anchors[state.detent] ?: peekPx)
            state.targetAnim = { next ->
                anim.animateTo(anchors[next] ?: peekPx, spring(stiffness = Spring.StiffnessMediumLow))
            }
        }
        state.heightPx = (anim.value.roundToInt() / 8) * 8
        Box(modifier = Modifier.fillMaxSize()) { content() }
        SheetPanelSurface(
            heightDp = with(d) { anim.value.coerceIn(peekPx, fullPx).toDp() },
            state = state,
            anim = anim,
            peekPx = peekPx,
            halfPx = halfPx,
            fullPx = fullPx,
            scope = scope,
            sheetContent = sheetContent
        )
    }
}

@Composable
private fun BoxWithConstraintsScope.SheetPanelSurface(
    heightDp: androidx.compose.ui.unit.Dp,
    state: ThreeStageSheetState,
    anim: Animatable<Float, *>,
    peekPx: Float,
    halfPx: Float,
    fullPx: Float,
    scope: kotlinx.coroutines.CoroutineScope,
    sheetContent: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(heightDp),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = KdeCardBg,
        border = BorderStroke(1.dp, KdeBorder.copy(alpha = 0.55f))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SheetDragHandle(
                onTap = { scope.launch { state.animateTo(nextDetent(state.detent)) } },
                onDrag = { dy -> scope.launch { anim.snapTo((anim.value - dy).coerceIn(peekPx, fullPx)) } },
                onDragEnd = { vel -> scope.launch { state.animateTo(snapDetent(anim.value, vel, peekPx, halfPx, fullPx)) } }
            )
            sheetContent()
        }
    }
}

@Composable
private fun SheetDragHandle(
    onTap: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: (Float) -> Unit
) {
    var lastVel = 0f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { _, dy -> lastVel = -dy; onDrag(dy) },
                    onDragEnd = { onDragEnd(lastVel) }
                )
            }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(38.dp)
                .height(4.dp)
                .background(KdeMuted.copy(alpha = 0.48f), RoundedCornerShape(999.dp))
        )
    }
}

private fun nextDetent(cur: SheetDetent): SheetDetent = when (cur) {
    SheetDetent.PEEK -> SheetDetent.HALF
    SheetDetent.HALF -> SheetDetent.FULL
    SheetDetent.FULL -> SheetDetent.PEEK
}

private fun snapDetent(h: Float, velUp: Float, peek: Float, half: Float, full: Float): SheetDetent {
    if (velUp > 12f) return if (h < half) SheetDetent.HALF else SheetDetent.FULL
    if (velUp < -12f) return if (h > half) SheetDetent.HALF else SheetDetent.PEEK
    val pts = listOf(SheetDetent.PEEK to peek, SheetDetent.HALF to half, SheetDetent.FULL to full)
    return pts.minByOrNull { abs(it.second - h) }?.first ?: SheetDetent.PEEK
}
