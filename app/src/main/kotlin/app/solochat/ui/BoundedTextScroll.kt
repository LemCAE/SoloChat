package app.solochat.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity

/** 多行输入的惯性停在框边界；手指仍在拖动时继续采用正常的嵌套滚动。 */
internal fun Modifier.stopTextFlingPropagation(): Modifier = nestedScroll(TextFlingBoundary)

private object TextFlingBoundary : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        // 惯性动画的每帧也会派发剩余位移，只拦结束速度仍会先带动外层页面。
        return if (source == NestedScrollSource.SideEffect) Offset(0f, available.y) else Offset.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        // 接住框内滚动结束后的纵向余速，避免外层另起一次惯性。
        return Velocity(0f, available.y)
    }
}
