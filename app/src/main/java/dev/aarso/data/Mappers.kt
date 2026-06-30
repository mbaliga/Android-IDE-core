package dev.aarso.data

import dev.aarso.data.entity.MessageNodeEntity
import dev.aarso.domain.MessageNode
import dev.aarso.domain.Role

/**
 * Mapping between the Room entity and the pure domain model. Token counts are
 * intentionally *not* hydrated here (they live in a separate table and are
 * fetched on demand), so the path/branch views stay cheap.
 */

private val converters = Converters()

fun MessageNodeEntity.toDomain(): MessageNode = MessageNode(
    id = id,
    parentId = parentId,
    role = Role.fromWire(role),
    content = content,
    modelId = modelId,
    createdAt = createdAt,
    tokenCounts = emptyMap(),
    metadata = converters.toMetadata(metadataJson),
)

fun MessageNode.toEntity(): MessageNodeEntity = MessageNodeEntity(
    id = id,
    parentId = parentId,
    role = role.wire,
    content = content,
    modelId = modelId,
    createdAt = createdAt,
    metadataJson = converters.fromMetadata(metadata),
)
