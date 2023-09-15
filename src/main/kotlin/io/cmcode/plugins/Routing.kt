package io.cmcode.plugins

import io.cmcode.routes.createRoomRoute
import io.cmcode.routes.getRoomsRoute
import io.cmcode.routes.joinRoomRoute
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*

fun Application.configureRouting() {
    install(Routing) {
        createRoomRoute()
        getRoomsRoute()
        joinRoomRoute()
    }
}
