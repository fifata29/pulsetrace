package dk.nst.hrvmonitor.data

/** What the user was doing/feeling at the time of a measurement.
 *  Lets us interpret HRV trends meaningfully — RMSSD pre-coffee vs post-workout
 *  is a different baseline. */
enum class StateTag(val displayName: String, val key: String) {
    Resting("Resting", "resting"),
    PreWorkout("Pre-workout", "pre_workout"),
    PostWorkout("Post-workout", "post_workout"),
    Stressed("Stressed", "stressed"),
    AfterCoffee("After coffee", "after_coffee"),
    Other("Other", "other");

    companion object {
        fun fromKey(k: String?): StateTag? = entries.firstOrNull { it.key == k }
    }
}
