package io.github.lubacareer.spacepong.bricks

import io.github.lubacareer.spacepong.simulation.Arena

enum class BrickPower { WIDE_PADDLE, SHIELD }
enum class BrickPhase { DOCKED, PLAYING, PAUSED, WON, LOST }
enum class BrickEvent { BRICK_HIT, PADDLE_HIT, WALL_HIT, PICKUP, SHIELD_SAVE, LIFE_LOST, WON, LOST }

class Brick internal constructor(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val pickup: BrickPower?,
) {
    var alive: Boolean = true
        internal set
}

class BrickDrop internal constructor(val x: Double, y: Double, val power: BrickPower) {
    var y: Double = y
        internal set
}

class BrickLevel internal constructor(val name: String, vararg grid: String) {
    val rows: List<String> = grid.toList()

    /** Rows are authored roof-first. A fresh set of bricks belongs to each attempt. */
    internal fun build(): List<Brick> {
        val columns = rows.first().length
        require(rows.all { it.length == columns })
        val left = (Arena.WIDTH - columns * CELL_WIDTH) / 2
        return buildList {
            for ((row, cells) in rows.withIndex()) {
                for ((col, cell) in cells.withIndex()) {
                    if (cell == '.') continue
                    val power = when (cell) {
                        '#' -> null
                        'W' -> BrickPower.WIDE_PADDLE
                        'S' -> BrickPower.SHIELD
                        else -> error("Unknown brick tile '$cell' in $name.")
                    }
                    add(Brick(left + col * CELL_WIDTH, ROOFLINE - (row + 1) * CELL_HEIGHT, CELL_WIDTH, CELL_HEIGHT, power))
                }
            }
        }
    }

    private companion object {
        const val CELL_WIDTH = 48.0
        const val CELL_HEIGHT = 27.0
        const val ROOFLINE = 580.0
    }
}

object BrickBuildings {
    val levels = listOf(
        BrickLevel(
            "Outpost",
            "....#....",
            "...###...",
            "..#####..",
            "###W#S###",
            "###...###",
        ),
        BrickLevel(
            "Hangar",
            "....#####....",
            "..#########..",
            ".###S###W###.",
            "####.....####",
            "###.......###",
            "##.........##",
        ),
        BrickLevel(
            "Twin Towers",
            "..#.......#..",
            ".###.....###.",
            "##W##...##S##",
            "##.##...##.##",
            "#####...#####",
            "##.#######.##",
            "#####...#####",
        ),
        BrickLevel(
            "Observatory",
            "......###......",
            "....#######....",
            "..###W###S###..",
            ".###.......###.",
            "###.........###",
            ".#############.",
            "...##.....##...",
            "..####...####..",
        ),
        BrickLevel(
            "Launch Gantry",
            "...#.............",
            "..###......#.....",
            "..#S#.....###....",
            "..###....##W##...",
            "..#######.###....",
            "..#.#.....###....",
            "..###....#####...",
            "..#.#...##...##..",
            ".#####.#########.",
        ),
        BrickLevel(
            "Orbital Citadel",
            ".........#.........",
            "...#....###....#...",
            "..###..##S##..###..",
            ".##W##.#####.##S##.",
            ".#####..#.#..#####.",
            ".##.###########.##.",
            ".#####..###..#####.",
            "###.##.##W##.##.###",
            "######.##.##.######",
            "#####.........#####",
        ),
    )
}
