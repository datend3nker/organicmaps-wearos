package app.organicmaps.wear.presentation.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.tooling.preview.devices.WearDevices

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState

@Composable
fun NavigationScreen(
    distanceToNextTurn: String,
    turnIcon: ImageVector,
    remainingTime: String,
    onCancelClick: () -> Unit,
    exitNum: Int = 0,
    // When non-null, the big icon becomes a live real-world bearing pointer: a single upward arrow
    // rotated by this many degrees clockwise so it points at the next turn relative to where the
    // watch is physically facing (angle = bearingToTurn - deviceHeading). When null we fall back to
    // the fixed maneuver glyph (no GPS fix / no turn point / no compass).
    pointerAngleDeg: Float? = null,
) {
    val listState = rememberScalingLazyListState()
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(top = 28.dp, bottom = 40.dp, start = 12.dp, end = 12.dp)
        ) {
            // Distance text
            item {
                Text(
                    text = distanceToNextTurn.ifEmpty { "Proceed" },
                    style = MaterialTheme.typography.title1.copy(
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold
                    ),
                    color = MaterialTheme.colors.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }

            // Large turn icon with optional exit number
            item {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(68.dp)) {
                    if (pointerAngleDeg != null) {
                        // Live bearing pointer: ONE upright arrow rotated to point at the next turn
                        // in real space. The maneuver glyph's left/right shape is intentionally
                        // dropped here — direction is encoded by the rotation, not the icon. (Caller
                        // only supplies a non-null angle when a GPS fix, a turn point and a compass
                        // heading are all available; otherwise it passes null and we draw the glyph.)
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = "Direction to next turn",
                            modifier = Modifier
                                .size(56.dp)
                                .rotate(pointerAngleDeg),
                            tint = MaterialTheme.colors.onBackground
                        )
                    } else {
                        Icon(
                            imageVector = turnIcon,
                            contentDescription = "Turn icon",
                            // The maneuver glyph (ArrowForward=right, ArrowBack=left, ArrowUpward=
                            // straight) already encodes the turn direction. Do NOT rotate it by the
                            // device compass heading — that spun an already-correct icon to a wrong
                            // angle. Keep it upright so it always matches the real maneuver.
                            modifier = Modifier
                                .size(56.dp),
                            tint = MaterialTheme.colors.onBackground
                        )
                    }
                    if (exitNum > 0) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .align(Alignment.BottomEnd)
                                .background(MaterialTheme.colors.surface, CircleShape)
                                .padding(2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = exitNum.toString(),
                                style = MaterialTheme.typography.title3.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                ),
                                color = MaterialTheme.colors.onSurface
                            )
                        }
                    }
                }
            }

            // Street name
            item {
                Text(
                    text = remainingTime,
                    style = MaterialTheme.typography.body1.copy(fontWeight = FontWeight.Medium, fontSize = 15.sp),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp)
                )
            }
            
            // Cancel button
            item {
                Spacer(modifier = Modifier.height(6.dp))
                Button(
                    onClick = onCancelClick,
                    modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
                    colors = ButtonDefaults.secondaryButtonColors()
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun NavigationScreenPreview() {
    NavigationScreen(
        distanceToNextTurn = "150 m",
        turnIcon = Icons.Default.ArrowUpward,
        remainingTime = "Avenue de l'Opéra",
        onCancelClick = { },
    )
}
