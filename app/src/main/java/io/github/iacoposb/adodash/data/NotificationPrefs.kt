package io.github.iacoposb.adodash.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.DayOfWeek

/** What can be notified, as the settings list it; one or more [EventKind]s each, plus app updates. */
enum class NotifyKind {
    MENTIONS, ASSIGNMENTS, REVIEWS, VOTES, PR_COMMENTS, BUILDS, UPDATES;

    companion object {
        fun of(kind: EventKind): NotifyKind = when (kind) {
            EventKind.MENTION -> MENTIONS
            EventKind.ASSIGNED -> ASSIGNMENTS
            EventKind.PR_REVIEW -> REVIEWS
            EventKind.PR_VOTE -> VOTES
            EventKind.PR_COMMENT -> PR_COMMENTS
            EventKind.BUILD_FAILED -> BUILDS
        }
    }
}

/** Which sound a notification setting is about. */
enum class SoundTarget { MENTIONS, OTHERS }

/**
 * Quiet hours: from [from] to [to] (minutes of the day, the end excluded) on [days], the
 * weekdays a quiet period starts on (ISO numbers, 1 Monday … 7 Sunday). A period that ends
 * before it starts runs overnight into the next day; equal ends make a whole day.
 */
@Serializable
data class QuietHours(
    val enabled: Boolean = false,
    val from: Int = 19 * 60,
    val to: Int = 8 * 60,
    val days: Set<Int> = ALL_DAYS,
    /** Mentions still make a sound during quiet hours. */
    val mentionsSound: Boolean = false,
) {
    fun hasDay(day: DayOfWeek) = day.value in days

    fun toggling(day: DayOfWeek) = copy(days = if (hasDay(day)) days - day.value else days + day.value)

    companion object {
        val ALL_DAYS = (1..7).toSet()
    }
}

/**
 * The notification settings. Sounds: null is the system's default notification sound, an empty
 * string no sound, anything else a sound's URI. Each sound changed bumps its channel's version:
 * see [io.github.iacoposb.adodash.sync.NotificationChannels].
 */
@Serializable
data class NotificationPrefs(
    /** The kinds turned off: still in Activity, never an Android notification. */
    val off: Set<NotifyKind> = emptySet(),
    val quiet: QuietHours = QuietHours(),
    val mentionsSound: String? = null,
    val othersSound: String? = null,
    val mentionsChannel: Int = 1,
    val othersChannel: Int = 1,
) {
    fun isOn(kind: NotifyKind) = kind !in off

    fun switching(kind: NotifyKind, on: Boolean) = copy(off = if (on) off - kind else off + kind)

    fun sound(target: SoundTarget): String? = when (target) {
        SoundTarget.MENTIONS -> mentionsSound
        SoundTarget.OTHERS -> othersSound
    }

    /** [sound] for [target], with a new channel version when it changes (channels keep their sound). */
    fun withSound(target: SoundTarget, sound: String?): NotificationPrefs = when {
        sound == sound(target) -> this
        target == SoundTarget.MENTIONS -> copy(mentionsSound = sound, mentionsChannel = mentionsChannel + 1)
        else -> copy(othersSound = sound, othersChannel = othersChannel + 1)
    }

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(text: String?): NotificationPrefs =
            text?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: NotificationPrefs()
    }
}
