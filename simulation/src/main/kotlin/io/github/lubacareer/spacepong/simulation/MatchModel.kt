package io.github.lubacareer.spacepong.simulation

/** Logical coordinates are independent of device density; positive Y points upward. */
object Arena {
    const val WIDTH = 1280.0
    const val HEIGHT = 720.0
    const val STEP_SECONDS = 1.0 / 120.0
    const val PADDLE_WIDTH = 18.0
    const val PADDLE_HEIGHT = 120.0
    const val PADDLE_SPEED = 1000.0
    const val BALL_RADIUS = 10.0
    const val INITIAL_BALL_SPEED = 420.0
    const val MAX_NORMAL_SPEED = 850.0
    const val MAX_PULSE_SPEED = 1000.0
    const val SERVE_SECONDS = 1.5
}

enum class Encounter { FLIGHT_SCHOOL, ASTEROID_PASSAGE, GRAVITY_RELAY, WARDEN }
enum class MatchMode { CAMPAIGN, LOCAL_VERSUS }
enum class Side { LEFT, RIGHT }
enum class MatchPhase { COUNTDOWN, PLAYING, PAUSED, FINISHED }
enum class HazardKind { NONE, ASTEROIDS, GRAVITY }
enum class HazardPhase { INACTIVE, WARNING, ACTIVE }
enum class MatchEventKind { PADDLE_HIT, WALL_HIT, BUMPER_HIT, PULSE_ARMED, GOAL, MATCH_FINISHED }

data class PlayerLoadout(val heightRank: Int = 0, val speedRank: Int = 0, val pulseRank: Int = 0) {
    init {
        require(heightRank in 0..2 && speedRank in 0..2 && pulseRank in 0..2)
    }
}

data class MatchConfig(
    val encounter: Encounter = Encounter.FLIGHT_SCHOOL,
    val mode: MatchMode = MatchMode.CAMPAIGN,
    val seed: Long = 1L,
    val loadout: PlayerLoadout = PlayerLoadout(),
)

data class InputFrame(
    val leftTargetY: Double? = null,
    val rightTargetY: Double? = null,
    val leftAxis: Double = 0.0,
    val rightAxis: Double = 0.0,
    val leftPulse: Boolean = false,
    val rightPulse: Boolean = false,
)

class PaddleState internal constructor(
    val x: Double,
    val height: Double,
    val speed: Double,
    /** Cooldown length; the armed hit window is always 0.6 seconds. */
    val pulseDuration: Double,
) {
    var y: Double = Arena.HEIGHT / 2
        internal set
    var previousY: Double = y
        internal set
    var velocityY: Double = 0.0
        internal set
    var pulseArmedSeconds: Double = 0.0
        internal set
    var pulseCooldownSeconds: Double = 0.0
        internal set
}

class BallState internal constructor() {
    var x: Double = Arena.WIDTH / 2
        internal set
    var y: Double = Arena.HEIGHT / 2
        internal set
    var previousX: Double = x
        internal set
    var previousY: Double = y
        internal set
    var velocityX: Double = 0.0
        internal set
    var velocityY: Double = 0.0
        internal set
    var normalSpeed: Double = Arena.INITIAL_BALL_SPEED
        internal set
    var pulsed: Boolean = false
        internal set
    internal var pulseOwner: Side? = null
}

class BumperState internal constructor(val x: Double, val y: Double, val radius: Double = 32.0) {
    var active: Boolean = false
        internal set
    var skippedThisCycle: Boolean = false
        internal set
}

class MatchState internal constructor(val config: MatchConfig, val left: PaddleState, val right: PaddleState) {
    val ball = BallState()
    val bumpers: List<BumperState> = listOf(BumperState(640.0, 240.0), BumperState(640.0, 480.0))
    var phase: MatchPhase = MatchPhase.COUNTDOWN
        internal set
    var tick: Long = 0L
        internal set
    var countdownSeconds: Double = Arena.SERVE_SECONDS
        internal set
    var rallySeconds: Double = 0.0
        internal set
    var leftScore: Int = 0
        internal set
    var rightScore: Int = 0
        internal set
    var leftHull: Int = 3
        internal set
    var rightHull: Int = 6
        internal set
    var winner: Side? = null
        internal set
    var hazardKind: HazardKind = HazardKind.NONE
        internal set
    var hazardPhase: HazardPhase = HazardPhase.INACTIVE
        internal set
    var bossPhase: Int = 1
        internal set
    val isBoss: Boolean get() = config.mode == MatchMode.CAMPAIGN && config.encounter == Encounter.WARDEN
}

data class MatchEvent(val kind: MatchEventKind, val side: Side?, val x: Double, val y: Double)
