package com.serwylo.retrowars.server

import com.badlogic.gdx.ApplicationListener
import com.badlogic.gdx.Gdx
import io.javalin.Javalin

class ServerApp: ApplicationListener {

    private val games = mutableListOf<GameServer>()

    companion object {
        const val TAG = "ServerApp"
    }

    override fun create() {
        Gdx.app.log(TAG, "Creating server app")
        val app = Javalin.create().start(8080)
        app.get("/games") { ctx -> ctx.result("Listing games") }
        app.put("/game") { ctx -> ctx.json("Creating a game") }
        app.get("/game/:id") { ctx -> ctx.result("Getting details of game ${ctx.pathParam("id")}") }
        app.delete("/game/:id") { ctx -> ctx.result("Deleting game ${ctx.pathParam("id")}") }
    }

    override fun resize(width: Int, height: Int) {}
    override fun render() {}
    override fun pause() {}
    override fun resume() {}
    override fun dispose() {}
}