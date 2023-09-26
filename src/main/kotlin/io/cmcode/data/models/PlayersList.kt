package io.cmcode.data.models

import io.cmcode.utils.Constants.TYPE_PLAYERS_LIST

data class PlayersList(
    val playersList: List<PlayerData>
): BaseModel(TYPE_PLAYERS_LIST)
