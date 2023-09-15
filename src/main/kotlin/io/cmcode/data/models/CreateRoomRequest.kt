package io.cmcode.data.models

data class CreateRoomRequest(
    val name: String,
    val maxPlayers: Int
)