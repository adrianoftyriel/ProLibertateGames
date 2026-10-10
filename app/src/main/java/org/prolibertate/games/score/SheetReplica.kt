package org.prolibertate.games.score

/**
 * A guest's copy of somebody else's score sheet.
 *
 * The host's sheet is the truth. What a guest *shows* is that sheet with the
 * guest's own not-yet-acknowledged changes laid on top, so a number typed into a
 * cell appears at once instead of after a round trip — and, more to the point,
 * so that a sheet pushed by the host a moment before the guest's change has
 * landed does not wipe out what the guest has just done. Each change is tagged
 * with a sequence number; the host reports the last one it has applied, and
 * everything up to it is dropped from the pile because it is by then part of the
 * sheet the host sent.
 *
 * Not thread-safe. The session that owns one serialises access to it.
 */
class SheetReplica {

    private var confirmed = ScoreSheet()
    private var nextSeq = 1
    private val pending = ArrayDeque<Pair<Int, SheetOp>>()

    /** The revision of the host's sheet this was last brought up to. */
    var revision: Int = 0
        private set

    /** What to draw: the host's sheet plus this guest's own unacknowledged changes. */
    var view: ScoreSheet = confirmed
        private set

    /** How many of this guest's changes the host has not yet confirmed. */
    val unconfirmed: Int get() = pending.size

    /** Records a change made here, and returns the sequence number to send it under. */
    fun local(op: SheetOp): Int {
        val seq = nextSeq++
        pending.addLast(seq to op)
        view = op.applyTo(view)
        return seq
    }

    /**
     * Takes the host's sheet at [revision], which includes every change of this
     * guest's up to and including [acknowledged].
     *
     * A sheet older than the one already held is ignored; links deliver in
     * order, but a resync request and a broadcast can cross.
     */
    fun receive(sheet: ScoreSheet, revision: Int, acknowledged: Int) {
        if (revision < this.revision) return
        this.revision = revision
        confirmed = sheet
        while (pending.isNotEmpty() && pending.first().first <= acknowledged) pending.removeFirst()
        view = pending.fold(confirmed) { acc, (_, op) -> op.applyTo(acc) }
    }

    /**
     * Forgets everything, for a fresh link to the host.
     *
     * Changes still waiting on the old link are gone with it: nothing says
     * whether the host got them, and replaying them could apply them twice. The
     * sheet the host sends next is the answer either way.
     */
    fun reset() {
        confirmed = ScoreSheet()
        view = confirmed
        pending.clear()
        revision = 0
        nextSeq = 1
    }
}
