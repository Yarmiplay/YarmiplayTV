package com.yarmiplaytv.syncplay

import kotlin.math.abs

/** The ways Syncplay's "your file differs" warning tells two users' files apart. */
enum class FileDifference { NAME, SIZE, DURATION }

object FileMatch {
    /** How [a] and [b] differ, by Syncplay's checks; empty when they look like the same file. */
    fun differences(a: FileInfo, b: FileInfo): Set<FileDifference> = buildSet {
        if (!Filenames.same(a.name, b.name)) add(FileDifference.NAME)
        if (!sameSize(a, b)) add(FileDifference.SIZE)
        if (!sameDuration(a, b)) add(FileDifference.DURATION)
    }

    /** Syncplay's utils.sameFilesize: an unknown size matches anything, and a hashed size matches its plain size. */
    fun sameSize(a: FileInfo, b: FileInfo): Boolean {
        if (!a.hasSize || !b.hasSize) return true
        if (a.size > 0 && b.size > 0) return a.size == b.size
        return a.comparableSizeHash == b.comparableSizeHash
    }

    /** Syncplay's utils.sameFileduration, on whole seconds. */
    fun sameDuration(a: FileInfo, b: FileInfo): Boolean =
        abs(Math.round(a.duration) - Math.round(b.duration)) < Constants.DIFFERENT_DURATION_THRESHOLD

    private val FileInfo.hasSize: Boolean get() = size > 0 || sizeHash != null
    private val FileInfo.comparableSizeHash: String get() = sizeHash ?: Filenames.hashSize(size)
}
