package com.serwylo.retrowars.net

import com.badlogic.gdx.Gdx
import com.esotericsoftware.kryonet.Client
import com.esotericsoftware.kryonet.Connection
import com.esotericsoftware.kryonet.FrameworkMessage
import com.esotericsoftware.kryonet.Listener
import com.esotericsoftware.kryonet.Listener.ThreadedListener
import com.serwylo.retrowars.net.Network.register
import com.serwylo.retrowars.utils.AppProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress

class RetrowarsClient(host: InetAddress?) {

    companion object {

        private const val TAG = "RetorwarsClient"
        private const val SCORE_BREAKPOINT_SIZE = 40000L

        private var client: RetrowarsClient? = null

        /**
         * @param connectToSelf Use this flag when you are also the server. Will result in connecting
         * directly to localhost, rather than trying to discover a server on the network.
         */
        fun connect(connectToSelf: Boolean): RetrowarsClient {
            Gdx.app.log(TAG, "Establishing connecting from client to server.")
            if (client != null) {
                throw IllegalStateException("Cannot connect to server, client connection has already been opened.")
            }

            val host = if (connectToSelf) {
                InetAddress.getLocalHost()
            } else {
                null
            }
            val newClient = RetrowarsClient(host)
            client = newClient
            return newClient
        }

        fun get(): RetrowarsClient? = client

        fun disconnect() {
            client?.close()
            client = null
        }

    }

    val players = mutableListOf<Player>()
    val scores = mutableMapOf<Player, Long>()

    /**
     * For each player, record the next score for which we will send an event to other players.
     * Once we notice their score go over this threshold, we'll bump it to the next breakpoint.
     */
    val scoreBreakpoints = mutableMapOf<Player, Long>()

    /**
     * By convention, the server always tells a client about themselves first before then passing
     * through details of all other players. Thus, the first player corresponds to the client in question.
     */
    fun me():Player? =
        if (players.size == 0) null else players[0]

    /**
     * Opposite of [me]. All players but the first.
     */
    fun otherPlayers(): List<Player> =
        if (players.size == 0) emptyList() else players.subList(1, players.size)

    private var client = Client()

    /**
     * Record if we receive a nice graceful message from the server.
     * If we *haven't* but we still get disconnected, we can show an error message to the user.
     * If we *have* shut down gracefully by the time we receive the disconnect event, then we can display a friendlier message.
     */
    private var hasShutdownGracefully = false

    private var playersChangedListener: ((List<Player>) -> Unit)? = null
    private var startGameListener: (() -> Unit)? = null
    private var scoreChangedListener: ((player: Player, score: Long) -> Unit)? = null
    private var scoreBreakpointListener: ((player: Player, strength: Int) -> Unit)? = null
    private var playerStatusChangedListener: ((player: Player, status: String) -> Unit)? = null
    private var networkCloseListener: ((wasGraceful: Boolean) -> Unit)? = null

    /**
     * The only wayt o listen to network events is via this function. It ensures that no previous
     * listeners will be left dangling around in previous views, by updating every single
     * listener (potentially to null).
     *
     * The intent is to:
     *  - Call this once per screen during the initialization phase.
     *  - Use named arguments so that you can those which are unneeded.
     *
     *  All are optional except for [networkCloseListener], if you choose to interact with the
     *  client, then you need to make sure you handle disconnections properly, because we don't
     *  know when these could happen.
     */
    fun listen(
        networkCloseListener: ((wasGraceful: Boolean) -> Unit),
        playersChangedListener: ((List<Player>) -> Unit)? = null,
        startGameListener: (() -> Unit)? = null,
        scoreChangedListener: ((player: Player, score: Long) -> Unit)? = null,
        scoreBreakpointListener: ((player: Player, strength: Int) -> Unit)? = null,
        playerStatusChangedListener: ((player: Player, status: String) -> Unit)? = null
    ) {
        this.playersChangedListener = playersChangedListener
        this.startGameListener = startGameListener
        this.scoreChangedListener = scoreChangedListener
        this.scoreBreakpointListener = scoreBreakpointListener
        this.playerStatusChangedListener = playerStatusChangedListener
        this.networkCloseListener = networkCloseListener
    }

    init {

        client.start()

        register(client)

        client.addListener(ThreadedListener(object : Listener {
            override fun connected(connection: Connection) {}

            override fun disconnected(connection: Connection) {
                if (hasShutdownGracefully) {
                    Gdx.app.log(TAG, "Client received disconnected event. Previously received a graceful shutdown so will broadcast that.")
                } else {
                    Gdx.app.log(TAG, "Client received disconnected event. No graceful shutdown from server, so will broadcast that.")
                }

                Gdx.app.debug(TAG, "Disconnected. Invoking RetrowarsClient.networkCloseListener (${if (hasShutdownGracefully) "shut down gracefully" else "ungraceful shutdown"})")
                networkCloseListener?.invoke(hasShutdownGracefully)
            }

            override fun received(connection: Connection, obj: Any) {
                if (obj !is FrameworkMessage.KeepAlive) {
                    Gdx.app.log(TAG, "Received message from server: $obj")
                }

                when(obj) {
                    is Network.Client.OnPlayerAdded -> onPlayerAdded(obj.id, obj.game)
                    is Network.Client.OnPlayerRemoved -> onPlayerRemoved(obj.id)
                    is Network.Client.OnPlayerScored -> onScoreChanged(obj.id, obj.score)
                    is Network.Client.OnPlayerStatusChange -> onStatusChanged(obj.id, obj.status)
                    is Network.Client.OnPlayerReturnedToLobby -> onReturnedToLobby(obj.id, obj.game)
                    is Network.Client.OnStartGame -> onStartGame()
                    is Network.Client.OnServerStopped -> onServerStopped()
                }
            }
        }))

        try {
            val address = host
                ?: client.discoverHost(Network.defaultUdpPort, 10000)
                ?: throw IOException("Could not server on the local network to connect to.")

            client.connect(5000, address, Network.defaultPort, Network.defaultUdpPort)
            client.sendTCP(Network.Server.RegisterPlayer(AppProperties.appVersionCode))
        } catch (e: IOException) {
            client.stop()
            throw IOException(e)
        }

    }

    private fun onServerStopped() {
        Gdx.app.log(TAG, "Recording that server stopped somewhat-gracefully.")
        hasShutdownGracefully = true
    }

    private fun onStartGame() {
        // We reuse the same servers/clients many time over if you finish a game and immediately
        // start a new one. Therefore we need to forget all we know about peoples scores before
        // continuing with a new game.
        scores.clear()
        players.forEach { it.status = Player.Status.playing }

        Gdx.app.debug(TAG, "Game started. Invoking RetrowarsClient.startGameListener")
        startGameListener?.invoke()
    }

    private fun onPlayerAdded(id: Long, game: String) {
        players.add(Player(id, game))

        Gdx.app.debug(TAG, "Player added. Invoking RetrowarsClient.playersChangedListener (Number of players is now ${players.size}, new player: ${id})")
        playersChangedListener?.invoke(players.toList())
    }

    private fun onPlayerRemoved(id: Long) {
        players.removeAll { it.id == id }

        Gdx.app.debug(TAG, "Player removed. Invoking RetrowarsClient.playersChangedListener (Number of players is now ${players.size}, removed player: ${id})")
        playersChangedListener?.invoke(players.toList())
    }

    private fun onScoreChanged(playerId: Long, score: Long) {
        val player = players.find { it.id == playerId } ?: return

        Gdx.app.log(TAG, "Updating player $playerId score to $score")
        scores[player] = score

        Gdx.app.debug(TAG, "Score changed. Invoking RetrowarsClient.scoreChangedListener (player ${player.id}, score: $score)")
        scoreChangedListener?.invoke(player, score)

        val breakpoint = getNextScoreBreakpointFor(player)
        if (breakpoint <= score) {
            val strength = incrementScoreBreakpoint(player, score)

            Gdx.app.log(TAG, "Player ${player.id} hit the score breakpoint of $breakpoint, so will send event to client.")
            Gdx.app.debug(TAG, "Breakpoint $breakpoint hit. Invoking RetrowarsClient.scoreBreakpointListener (player ${player.id}, strength: $strength)")
            scoreBreakpointListener?.invoke(player, strength)
        } else {
            Gdx.app.debug(TAG, "Next breakpoint: $breakpoint. Not yet hit with current score of $score")
        }
    }

    private fun onStatusChanged(playerId: Long, status: String) {
        val player = players.find { it.id == playerId } ?: return

        if (!Player.Status.isValid(status)) {
            Gdx.app.error(TAG, "Received unsupported status: $status... will ignore. Is this a client/server that is running the same version?")
            return
        }

        Gdx.app.log(TAG, "Received status change for player $playerId: $status")
        player.status = status

        Gdx.app.debug(TAG, "Status changed. Invoking RetrowarsClient.playerStatusChangedListener (player $playerId, status: $status)")
        playerStatusChangedListener?.invoke(player, status)
    }

    private fun onReturnedToLobby(playerId: Long, playersNewGame: String) {
        val player = players.find { it.id == playerId } ?: return

        // TODO: Validate game.

        Gdx.app.log(TAG, "Received return to lobby request for player $playerId. New game: $playersNewGame")
        player.status = Player.Status.lobby
        player.game = playersNewGame

        Gdx.app.debug(TAG, "Player returning to lobby. Invoking RetrowarsClient.playerStatusChangedListener (player $playerId, status: ${Player.Status.lobby})")
        playerStatusChangedListener?.invoke(player, Player.Status.lobby)
    }

    fun changeStatus(status: String) {
        me()?.status = status
        client.sendTCP(Network.Server.UpdateStatus(status))
    }

    fun updateScore(score: Long) {
        val me = me()
        if (me != null) {
            scores[me] = score
        }
        client.sendTCP(Network.Server.UpdateScore(score))
    }

    fun close() {
        client.close()
    }

    fun getScoreFor(player: Player): Long {
        return scores[player] ?: 0
    }

    private fun getNextScoreBreakpointFor(player: Player): Long {
        val breakpoint = scoreBreakpoints[player] ?: 0L
        if (breakpoint == 0L) {
            scoreBreakpoints[player] = SCORE_BREAKPOINT_SIZE
            return SCORE_BREAKPOINT_SIZE
        }

        return breakpoint
    }

    private fun incrementScoreBreakpoint(player: Player, currentScore: Long): Int {
        var counter = 0
        do {
            val breakpoint = getNextScoreBreakpointFor(player)
            val nextBreakpoint = breakpoint + SCORE_BREAKPOINT_SIZE
            scoreBreakpoints[player] = nextBreakpoint
            Gdx.app.debug(TAG, "Incrementing breakpoint from $breakpoint to $nextBreakpoint for player ${player.id}")
            counter ++
        } while (breakpoint + SCORE_BREAKPOINT_SIZE < currentScore)

        return counter
    }

}