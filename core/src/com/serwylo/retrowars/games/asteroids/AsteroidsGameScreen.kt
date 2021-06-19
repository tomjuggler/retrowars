package com.serwylo.retrowars.games.asteroids

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.ui.HorizontalGroup
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.serwylo.beatgame.ui.UI_SPACE
import com.serwylo.retrowars.RetrowarsGame
import com.serwylo.retrowars.games.GameScreen
import com.serwylo.retrowars.games.Games
import com.serwylo.retrowars.games.asteroids.entities.Asteroid
import com.serwylo.retrowars.games.asteroids.entities.Bullet
import com.serwylo.retrowars.games.asteroids.entities.HasBoundingSphere
import com.serwylo.retrowars.games.asteroids.entities.Ship
import com.serwylo.retrowars.input.AsteroidsSoftController
import com.serwylo.retrowars.utils.Options

class AsteroidsGameScreen(game: RetrowarsGame) : GameScreen(game, Games.asteroids, 400f, 400f) {

    companion object {
        @Suppress("unused")
        const val TAG = "AsteroidsGameScreen"
    }

    private val state = AsteroidsGameState(viewport.worldWidth, viewport.worldHeight)

    private val controller = AsteroidsSoftController(Options.getSoftController(Games.asteroids), game.uiAssets)

    private val lifeContainer = HorizontalGroup().apply { space(UI_SPACE) }

    init {

        state.ship.onShoot {
            it.setWorldSize(viewport.worldWidth, viewport.worldHeight)
            state.bullets.add(it)
        }

        addGameOverlayToHUD(controller.getActor())
        addGameScoreToHUD(lifeContainer)
        showMessage("Destroy the asteroids", "Protect your ship")

    }

    override fun show() {
        Gdx.input.inputProcessor = getInputProcessor()
    }

    override fun updateGame(delta: Float) {
        state.timer += delta

        state.ship.left = controller.isPressed(AsteroidsSoftController.Buttons.LEFT) || Gdx.input.isKeyPressed(Input.Keys.LEFT)
        state.ship.right = controller.isPressed(AsteroidsSoftController.Buttons.RIGHT) || Gdx.input.isKeyPressed(Input.Keys.RIGHT)
        state.ship.shooting = controller.isPressed(AsteroidsSoftController.Buttons.FIRE) || Gdx.input.isKeyPressed(Input.Keys.SPACE)
        state.ship.thrust = controller.isPressed(AsteroidsSoftController.Buttons.THRUST) || Gdx.input.isKeyPressed(Input.Keys.UP)

        updateEntities(delta)

        // TODO: Show end of game screen.
        if (state.numLives <= 0) {
            endGame()
        }
    }

    private fun updateEntities(delta: Float) {

        if (state.isShipAlive()) {

            // If we have not died (i.e. we are not in the mandatory waiting period for respawning the ship).
            state.ship.update(delta)

        } else if (state.isShipReadyToRespawn()) {

            // If we have died, waited the minimum amount of time, and are now ready to respawn...
            respawnShipIfSafe()

        }

        state.bullets.forEach { it.update(delta) }
        state.bullets.removeAll { it.isExpired() }

        val iterator = state.asteroids.iterator()
        while (iterator.hasNext()) {
            iterator.next().update(delta)
        }

        checkCollisions()

        if (state.asteroids.size == 0 && state.nextAsteroidRespawnTime < 0f) {
            queueAsteroidsRespawn()
        } else if (state.areAsteroidsReadyToRespawn()) {
            respawnAsteroids()
        }

    }

    object SafeRespawnArea : HasBoundingSphere {

        val screenCentre = Vector2()

        override fun getPosition() = screenCentre
        override fun getRadius() = Ship.HEIGHT * 3

    }

    /**
     * Check a bounding box in the middle of the screen, and if there are no asteroids there,
     * then respawn the ship there and reset the respawn counter.
     */
    private fun respawnShipIfSafe() {

        val asteroidsInWay = state.asteroids.any { asteroid ->
            asteroid.isColliding(SafeRespawnArea)
        }

        if (asteroidsInWay) {
            return
        }

        state.ship.respawnInCentre()
        state.nextShipRespawnTime = -1f

    }

    // TODO: Doesn't correctly check for collisions on the wrapped side of the world.
    private fun checkCollisions() {
        val asteroidsToBreak = mutableListOf<Asteroid>()

        val iterator = state.asteroids.iterator()
        while (iterator.hasNext()) {
            val asteroid = iterator.next()

            if (state.isShipAlive() && asteroid.isColliding(state.ship)) {

                asteroidsToBreak.add(asteroid)

//                shipHit()

            } else {

                val bullet = state.bullets.firstOrNull { asteroid.isColliding(it) }

                if (bullet != null) {
                    asteroidsToBreak.add(asteroid)
                    state.bullets.remove(bullet)
                    increaseScore(asteroid.size.points)
                }

            }

        }

        asteroidsToBreak.forEach { toBreak ->

            val newAsteroids = toBreak.split()
            newAsteroids.forEach { it.setWorldSize(viewport.worldWidth, viewport.worldHeight) }

            state.asteroids.remove(toBreak)
            state.asteroids.addAll(newAsteroids)

        }
    }

    private fun shipHit() {
        state.numLives--
        state.nextShipRespawnTime = state.timer + AsteroidsGameState.SHIP_RESPAWN_DELAY
    }

    private fun queueAsteroidsRespawn() {
        state.nextAsteroidRespawnTime = state.timer + Asteroid.RESPAWN_DELAY
    }

    /**
     * Add a new collection of large asteroids around the edge of the screen. There will be one
     * more than last time we did this. We will then reset the respawning status.
     */
    private fun respawnAsteroids() {
        val numToRespawn = ++state.currentNumAsteroids
        state.nextAsteroidRespawnTime = -1f
        state.asteroids.addAll(Asteroid.spawn(numToRespawn, viewport.worldWidth, viewport.worldHeight))
    }

    override fun onReceiveDamage(strength: Int) {
        state.asteroids.addAll(Asteroid.spawn(strength, viewport.worldWidth, viewport.worldHeight))
    }

    override fun renderGame(camera: OrthographicCamera) {
        val r = game.uiAssets.shapeRenderer

        // Make the ship disappear when respawning. It will then reappear in the future when ready to
        // go again.
        if (state.isShipAlive()) {
            state.ship.render(camera, r)
        }

        Bullet.renderBulk(camera, r, state.bullets)
        Asteroid.renderBulk(camera, r, state.asteroids)

        if (lifeContainer.children.size != state.numLives) {
            redrawLives()
        }
    }

    private fun redrawLives() {
        lifeContainer.clear()
        for (i in 0 until state.numLives) {
            lifeContainer.addActor(Label("x", game.uiAssets.getStyles().label.large))
        }
    }

    override fun resizeViewport(viewportWidth: Float, viewportHeight: Float) {
        state.ship.setWorldSize(viewportWidth, viewportHeight)
        state.bullets.forEach { it.setWorldSize(viewportWidth, viewportHeight) }

        val iterator = state.asteroids.iterator()
        while (iterator.hasNext()) {
            iterator.next().setWorldSize(viewportWidth, viewportHeight)
        }

        SafeRespawnArea.screenCentre.set(viewportWidth / 2f, viewportHeight / 2f)
    }

}
