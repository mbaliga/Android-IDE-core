package dev.fonebrew.domain.gitcore

/** An object as stored: its type and exact body, not yet parsed. */
class RawObject(val type: ObjectType, val data: ByteArray)
