package io.cmcode.data.models

import io.cmcode.data.Room
import io.cmcode.utils.Constants.TYPE_PHASE_CHANGE

data class PhaseChange(
    var phase: Room.Phase?,
    var time: Long,
    val drawingPlayer: String? = null
): BaseModel(TYPE_PHASE_CHANGE)
