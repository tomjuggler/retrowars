package com.serwylo.retrowars.games.snake

import java.util.*

class SnakeGameState() {

    companion object {
        // Based on my count from a screenshot of the old Nokia 6110 version.
        const val CELLS_WIDE = 29
        const val CELLS_HIGH = 19
    }

    var timeStep = 0.45f
    var nextTimeStep = timeStep
    var timer = 0f

    /**
     * If this is greater than one, then we will extend the length of the snake for this many time
     * steps regardless of whether you have eaten anything or not.
     */
    var queuedGrowth = 0

    var cells: List<List<Cell>> = (0 until CELLS_HIGH).map { y ->
        (0 until CELLS_WIDE).map { x ->
            Cell(x, y)
        }
    }

    val snake = LinkedList<Cell>().apply {
        add(cells[0][CELLS_WIDE/2])
    }

    var food = cells[CELLS_HIGH * 2 / 3][CELLS_WIDE / 2]

    var left = false
    var right = false
    var up = false
    var down = false

    var currentDirection = Direction.UP
    var nextDirection = Direction.UP

    class Cell(val x: Int, val y: Int) {
        override fun toString() = "($x, $y)"
    }

}

enum class Direction {
    UP, DOWN, LEFT, RIGHT
}
