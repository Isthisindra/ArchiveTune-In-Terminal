package moe.rukamori.archivetune.cli.playback

import moe.rukamori.archivetune.cli.config.ScrobbleConfig
import moe.rukamori.archivetune.cli.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The scrobble rules match the app's ScrobbleManager: inactive sessions and
 * very short tracks never scrobble, the listen goal is a fraction of the song
 * duration capped at three minutes.
 */
class ScrobbleGatewayTest {

    private fun gateway(block: ScrobbleConfig.() -> ScrobbleConfig = { this }) =
        ScrobbleGateway(ScrobbleConfig(enabled = true, sessionKey = "sk").block()) { }

    private fun track(durationSeconds: Int?) = Track(id = "id", title = "t", duration = durationSeconds)

    @Test
    fun `disabled config never scrobbles`() {
        val disabled = ScrobbleGateway(ScrobbleConfig(enabled = false, sessionKey = "sk")) { }
        assertEquals(-1L, disabled.listenGoalMs(track(500)))
    }

    @Test
    fun `missing session key never scrobbles`() {
        val noSession = ScrobbleGateway(ScrobbleConfig(enabled = true)) { }
        assertEquals(-1L, noSession.listenGoalMs(track(500)))
    }

    @Test
    fun `short tracks never scrobble`() {
        assertEquals(-1L, gateway().listenGoalMs(track(29)))
        // Thirty seconds is the Last.fm floor and, matching the app, tracks
        // right on it are still skipped; only strictly longer ones scrobble.
        assertEquals(-1L, gateway().listenGoalMs(track(30)))
        assertEquals(15_500L, gateway().listenGoalMs(track(31)))
    }

    @Test
    fun `unknown duration never scrobbles`() {
        assertEquals(-1L, gateway().listenGoalMs(track(null)))
    }

    @Test
    fun `goal is the fraction of the duration`() {
        assertEquals(100_000L, gateway().listenGoalMs(track(200)))
        assertEquals(25_000L, gateway { copy(scrobbleThreshold = 0.25) }.listenGoalMs(track(100)))
    }

    @Test
    fun `goal is capped at three minutes`() {
        // 50% of a 20-minute track would be ten minutes; the cap wins.
        assertEquals(180_000L, gateway().listenGoalMs(track(1200)))
    }

    @Test
    fun `threshold outside the valid range clamps`() {
        val over = gateway { copy(scrobbleThreshold = 5.0) }
        val under = gateway { copy(scrobbleThreshold = 0.0) }
        // 5.0 -> 1.0 -> goal of 100s; 0.0 -> 0 -> instant scrobble.
        assertEquals(100_000L, over.listenGoalMs(track(100)))
        assertEquals(0L, under.listenGoalMs(track(100)))
    }
}