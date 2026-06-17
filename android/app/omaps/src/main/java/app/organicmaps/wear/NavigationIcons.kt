package app.organicmaps.wear

import androidx.annotation.DrawableRes
import app.organicmaps.sdk.routing.CarDirection
import app.organicmaps.sdk.routing.PedestrianDirection

object NavigationIcons {
    @DrawableRes
    fun getTurnIcon(carDirection: Int, pedestrianDirection: Int, exitNum: Int = 0): Int {
        // If pedestrian direction is not NoTurn/GoStraight, use it
        if (pedestrianDirection != 0 && pedestrianDirection != 1) {
            val pDir = PedestrianDirection.entries[pedestrianDirection.coerceIn(0, PedestrianDirection.entries.size - 1)]
            return pDir.turnRes
        }

        val directions = CarDirection.entries
        val cDir = directions[carDirection.coerceIn(0, directions.size - 1)]
        return cDir.getTurnRes(exitNum)
    }
}
