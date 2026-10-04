package moe.rukamori.archivetune.cli.commands

import com.github.ajalt.clikt.parameters.arguments.argument
import moe.rukamori.archivetune.cli.CliContext
import moe.rukamori.archivetune.cli.model.YouTubeRefs

class SongCommand(ctx: CliContext) :
    BaseCommand(
        ctx = ctx,
        name = "song",
        helpText = "Play a single track by video id or URL.",
        epilogText = "Example:  archivetune song https://youtu.be/dQw4w9WgXcQ",
    ) {
        private val reference by argument("VIDEO_ID_OR_URL", help = "A YouTube video id or watch URL")

        override fun run() {
            val videoId =
                YouTubeRefs.videoId(reference)
                    ?: fail("\"$reference\" is not a YouTube video id or watch URL.")
            ctx.requireNetwork()
            val track = blocking { catalog.song(videoId, ctx.streamClient).orFail() }
            play(listOf(track))
        }
    }
