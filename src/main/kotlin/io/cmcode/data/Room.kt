package io.cmcode.data

import io.cmcode.data.Room.Phase.*
import io.cmcode.data.models.*
import io.cmcode.gson
import io.cmcode.server
import io.cmcode.utils.getRandomWords
import io.cmcode.utils.matchesWord
import io.cmcode.utils.transformToUnderscores
import io.cmcode.utils.words
import io.ktor.http.cio.websocket.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

data class Room(
    val name: String,
    val maxPlayers: Int,
    var players: List<Player> = listOf()
) {

    private var timerJob: Job? = null
    private var drawingPlayer: Player? = null
    private var winningPlayers = listOf<String>()
    private var word: String? = null
    private var curWords: List<String>? = null
    private var drawingPlayerIndex = 0
    private var startTime = 0L

    private val playerRemoveJobs = ConcurrentHashMap<String, Job>()
    private val leftPlayers = ConcurrentHashMap<String, Pair<Player, Int>>()

    private var phaseChangedListener: ((Phase) -> Unit)? = null
    var phase = WAITING_FOR_PLAYERS
        set(value) {
            synchronized(field) {
                field = value
                phaseChangedListener?.let {change ->
                    change(value)

                }
            }
        }

    private fun setPhaseChangedListener(listener: (Phase) -> Unit) {
        phaseChangedListener = listener
    }

    init {
        setPhaseChangedListener { newPhase ->
            when(newPhase) {
                WAITING_FOR_PLAYERS -> waitingForPlayers()
                WAITING_FOR_START -> waitingForStart()
                NEW_ROUND -> newRound()
                GAME_RUNNING -> gameRunning()
                SHOW_WORD -> showWord()
            }
        }
    }

    suspend fun addPlayer(clientId: String, username: String, socket: WebSocketSession): Player {
        val player = Player(username, socket, clientId)
        players = players + player

        if (players.size == 1) {
            phase = WAITING_FOR_PLAYERS
        } else if (players.size == 2 && phase == WAITING_FOR_PLAYERS) {
            phase = WAITING_FOR_START
            players = players.shuffled()
        } else if (phase == WAITING_FOR_START && players.size == maxPlayers) {
            phase = NEW_ROUND
            players = players.shuffled()
        }

        val announcement = Announcement(
            "$username joined the party!",
            System.currentTimeMillis(),
            Announcement.TYPE_PLAYER_JOINED
        )
        sendWordToPlayer(player)
        broadcastPlayersStates()
        broadcast(gson.toJson(announcement))

        return player
    }

    fun removePlayer(clientId: String) {
        val player = players.find { it.clientId == clientId } ?: return
        val index = players.indexOf(player)
        leftPlayers[clientId] = player to index
        players = players - player

        playerRemoveJobs[clientId] = GlobalScope.launch {
            delay(PLAYER_REMOVE_TIME)
            val playerToRemove = leftPlayers[clientId]
            leftPlayers.remove(clientId)
            playerToRemove?.let {
                players = players - it.first
            }
            playerRemoveJobs.remove(clientId)
        }
        val announcement = Announcement(
            "${player.username} left the party :(",
            System.currentTimeMillis(),
            Announcement.TYPE_PLAYER_LEFT
        )
        GlobalScope.launch {
            broadcastPlayersStates()
            broadcast(gson.toJson(announcement))
            if(players.size == 1) {
                phase = WAITING_FOR_PLAYERS
                timerJob?.cancel()
            } else if(players.isEmpty()) {
                kill()
                server.rooms.remove(name)
            }
        }
    }

    private fun timeAndNotify(ms: Long) {
        timerJob?.cancel()
        timerJob = GlobalScope.launch {
            startTime = System.currentTimeMillis()
            val phaseChange = PhaseChange(
                phase,
                ms,
                drawingPlayer?.username
            )
            repeat((ms / UPDATE_TIME_FREQUENCY).toInt()) {
                if (it != 0) {
                    phaseChange.phase = null
                }
                broadcast(gson.toJson(phaseChange))
                phaseChange.time -= UPDATE_TIME_FREQUENCY
                delay(UPDATE_TIME_FREQUENCY)
            }
            phase = when (phase) {
                WAITING_FOR_START -> NEW_ROUND
                GAME_RUNNING -> SHOW_WORD
                SHOW_WORD -> NEW_ROUND
                NEW_ROUND -> GAME_RUNNING
                else -> WAITING_FOR_PLAYERS
            }
        }
    }

    private fun isGuessCorrect(guess: ChatMessage): Boolean {
        return guess.matchesWord(word ?: return false)
                && !winningPlayers.contains(guess.from)
                && guess.from != drawingPlayer?.username
                && phase == GAME_RUNNING
    }
    suspend fun broadcast(message: String) {
        players.forEach { player ->
            if (player.socket.isActive) {
                player.socket.send(Frame.Text(message))
            }
        }
    }

    suspend fun broadcastToAllExcept(message: String, clientId: String) {
        players.forEach { player ->
            if (player.clientId != clientId && player.socket.isActive) {
                player.socket.send(Frame.Text(message))
            }
        }
    }

    fun containsPlayer(username: String): Boolean {
        return players.find { it.username == username } != null
    }

    fun setWordAndSwitchToGameRunning(word: String) {
        this.word = word
        phase = GAME_RUNNING
    }

    private fun waitingForPlayers() {
        GlobalScope.launch {
            val phaseChange = PhaseChange(
                WAITING_FOR_PLAYERS,
                DELAY_WAITING_FOR_START_TO_NEW_ROUND
            )
            broadcast(gson.toJson(phaseChange))
        }
    }

    private fun waitingForStart() {
        GlobalScope.launch {
            timeAndNotify(DELAY_WAITING_FOR_START_TO_NEW_ROUND)
            val phaseChange = PhaseChange(
                WAITING_FOR_START,
                DELAY_WAITING_FOR_START_TO_NEW_ROUND
            )
            broadcast(gson.toJson(phaseChange))
        }
    }

    private fun newRound() {
        curWords = getRandomWords(3)
        val newWords = NewWords(curWords!!)
        nextDrawingPlayer()
        GlobalScope.launch {
            broadcastPlayersStates()
            drawingPlayer?.socket?.send(Frame.Text(gson.toJson(newWords)))
            timeAndNotify(DELAY_NEW_ROUND_TO_GAME_RUNNING)
        }
    }

    private fun gameRunning() {
        winningPlayers = listOf()
        var wordToSend = word ?: curWords?.random() ?: words.random()
        val wordWithUnderscores = wordToSend.transformToUnderscores()
        val drawingUsername = (drawingPlayer ?: players.random()).username
        val gameStateForDrawingPlayer = GameState(
            drawingUsername,
            wordToSend
        )
        val gameStateForguessingPlayers = GameState(
            drawingUsername,
            wordWithUnderscores
        )
        GlobalScope.launch {
            broadcastToAllExcept(
                gson.toJson(gameStateForguessingPlayers),
                drawingPlayer?.clientId ?: players.random().clientId
            )
            drawingPlayer?.socket?.send(Frame.Text(gson.toJson(gameStateForDrawingPlayer)))

            timeAndNotify(DELAY_GAME_RUNNING_TO_SHOW_WORD)
            println("Drawing phase in room $name started. It'll last ${DELAY_GAME_RUNNING_TO_SHOW_WORD / 1000}s")
        }
    }

    private fun showWord() {
        GlobalScope.launch {
            if (winningPlayers.isEmpty()) {
                drawingPlayer?.let {
                    it.score -= PENALTY_NOBODY_GUESSED_IT
                }
            }
            broadcastPlayersStates()
            word?.let {
                val chosenWord = ChosenWord(it, name)
                broadcast(gson.toJson(chosenWord))
            }
            timeAndNotify(DELAY_SHOW_WORD_TO_NEW_ROUND)
            val phaseChange = PhaseChange(SHOW_WORD, DELAY_SHOW_WORD_TO_NEW_ROUND)
            broadcast(gson.toJson(phaseChange))

        }
    }

    fun solution(first: List<Int>, second: List<Int>): MutableList<Int> {
        // put your code here
        return first.plus(second).toMutableList()
    }

    private fun addWinningPlayer(username: String): Boolean {
        winningPlayers = winningPlayers + username
        if (winningPlayers.size == players.size - 1) {
            phase = NEW_ROUND
            return true
        }
        return false
    }

    suspend fun checkWordAndNotifyPlayers(message: ChatMessage): Boolean {
        if (isGuessCorrect(message)) {
            val guessingTime = System.currentTimeMillis() - startTime
            val timePercentageLeft = 1f - guessingTime.toFloat() / DELAY_GAME_RUNNING_TO_SHOW_WORD
            val score = GUESSED_SCORE_DEFAULT + GUESSED_SCORE_PERCENTAGE_MULTIPLIER * timePercentageLeft
            val player = players.find { it.username == message.from }

            player?.let {
                it.score += score.toInt()
            }
            drawingPlayer?.let {
                it.score += GUESSED_SCORE_FOR_DRAWING_PLAYER / players.size
            }
            broadcastPlayersStates()

            val announcement = Announcement(
                "${message.from} has guessed it!",
                System.currentTimeMillis(),
                Announcement.TYPE_PLAYER_GUESSED_WORD
            )
            broadcast(gson.toJson(announcement))
            val isRoundOver = addWinningPlayer((message.from))
            if (isRoundOver) {
                val roundOverAnnouncement = Announcement(
                    "Everybody guessed it! New Round is starting...",
                    System.currentTimeMillis(),
                    Announcement.TYPE_EVERYBODY_GUESSED_IT
                )
                broadcast(gson.toJson(roundOverAnnouncement))
            }
            return true
        }
        return false
    }

    private suspend fun broadcastPlayersStates() {
        val playersList = players.sortedByDescending { it.score }.map {
            PlayerData(it.username, it.isDrawing, it.rank)
        }
        playersList.forEachIndexed { index, playerData ->
            playerData.rank = index + 1
        }
        broadcast(gson.toJson(PlayersList(playersList)))
    }

    private suspend fun sendWordToPlayer(player: Player) {
        val delay = when (phase) {
            WAITING_FOR_START -> DELAY_WAITING_FOR_START_TO_NEW_ROUND
            NEW_ROUND -> DELAY_NEW_ROUND_TO_GAME_RUNNING
            GAME_RUNNING -> DELAY_GAME_RUNNING_TO_SHOW_WORD
            SHOW_WORD -> DELAY_SHOW_WORD_TO_NEW_ROUND
            //WAITING_FOR_PLAYERS ->
            else -> 0L
        }
        val phaseChange = PhaseChange(phase, delay, drawingPlayer?.username)

        word?.let { curWord ->
            drawingPlayer?.let { drawingPlayer ->
                val gameState = GameState(
                    drawingPlayer.username,
                    if (player.isDrawing || phase == SHOW_WORD) {
                        curWord
                    } else {
                        curWord.transformToUnderscores()
                    }
                )
                player.socket.send(Frame.Text(gson.toJson(gameState)))
            }
        }
        player.socket.send(Frame.Text(gson.toJson(phaseChange)))
    }

    private fun nextDrawingPlayer() {
        drawingPlayer?.isDrawing = false
        if (players.isEmpty()) {
            return
        }

        drawingPlayer = if (drawingPlayerIndex <= players.size - 1) {
            players[drawingPlayerIndex]
        } else players.last()

        if (drawingPlayerIndex < players.size - 1)  drawingPlayerIndex++
        else drawingPlayerIndex = 0
    }

    private fun kill() {
        playerRemoveJobs.values.forEach { it.cancel() }
        timerJob?.cancel()
    }

    enum class Phase {
        WAITING_FOR_PLAYERS,
        WAITING_FOR_START,
        NEW_ROUND,
        GAME_RUNNING,
        SHOW_WORD
    }

    companion object {

        const val UPDATE_TIME_FREQUENCY = 1000L
        const val PLAYER_REMOVE_TIME = 60000L
        const val DELAY_WAITING_FOR_START_TO_NEW_ROUND = 10000L
        const val DELAY_NEW_ROUND_TO_GAME_RUNNING = 20000L
        const val DELAY_GAME_RUNNING_TO_SHOW_WORD = 60000L
        const val DELAY_SHOW_WORD_TO_NEW_ROUND = 60000L

        const val PENALTY_NOBODY_GUESSED_IT = 50
        const val GUESSED_SCORE_DEFAULT = 50
        const val GUESSED_SCORE_PERCENTAGE_MULTIPLIER = 50
        const val GUESSED_SCORE_FOR_DRAWING_PLAYER = 50
    }
}
