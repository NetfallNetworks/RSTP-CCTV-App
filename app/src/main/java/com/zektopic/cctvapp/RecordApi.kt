package com.zektopic.cctvapp

/**
 * Manual recording control and the archive hand-off, as served on the /record/ routes and
 * POST /events/<id>/archived. Every method returns a JSON body.
 */
interface RecordApi {
    fun state(): String
    fun start(minutes: Int): String
    fun hold(minutes: Int): String
    fun stop(pauseAutoMinutes: Int): String
    fun discard(pauseAutoMinutes: Int): String
    fun resume(): String
    /** (HTTP status, body): 200 deleted, 404 unknown, 409 still recording. */
    fun archived(eventId: String): Pair<Int, String>
}
