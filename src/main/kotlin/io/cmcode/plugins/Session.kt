package io.cmcode.plugins

import io.cmcode.session.DrawingSession
import io.ktor.application.*
import io.ktor.sessions.*

fun Application.configureSession() {
    install(Sessions) {
        cookie<DrawingSession>("SESSION")
    }
}