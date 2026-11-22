package com.yarmiplaytv.local

/**
 * Volume directory names under `/storage` that are removable disks. `emulated` is the internal shared folder
 * (added separately) and `self` is a mirror of it.
 */
fun removableVolumeNames(names: Collection<String>): List<String> =
    names.filter { name ->
        name.isNotEmpty() &&
            name != "emulated" &&
            name != "self" &&
            !name.startsWith(".") &&
            !name.contains('/') &&
            !name.contains('\\')
    }.distinct()
