package dev.aarso.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Room representation of [dev.aarso.domain.curation.FormState]. [msgId] is the primary key — one stored answer-set per questionnaire message, overwritten when the form is resubmitted after a rewind. */
@Entity(tableName = "form_states")
data class FormStateEntity(
    @PrimaryKey val msgId: String,
    val schemaJson: String,
    val answersJson: String,
)
