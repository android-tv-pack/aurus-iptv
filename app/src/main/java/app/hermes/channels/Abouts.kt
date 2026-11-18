package app.hermes.channels

/**
 * What the settings card says about a setting (see [Page]): what it does and what each of its choices means. Keyed by
 * the row's title; a row may also carry its own text ([Page.Row.about]). Rows that are not here show their short line.
 */
object Abouts {
    fun of(title: String): String? = all[title.trim()]

    private val all = mapOf(
        "\uFF0B  Add a playlist".trim() to "Give the playlist link (M3U) your provider sent you. It is watched at once and kept only on this device; " +
            "the link holds your subscription key, so it is not shown again once saved.",
        "Films and series" to "A library of films and series that some providers offer beside their channels (a portal link). " +
            "Add its link here; the library then opens from this row.",
        "EPG of this playlist" to "Where the playlist you watch takes its programmes from: the EPG the playlist names itself, or one you have saved below.",
        "\uFF0B  Add an EPG".trim() to "A link to a guide in XMLTV form (.xml, or packed as .xml.gz). It is saved as a new EPG and used for the playlist " +
            "you are watching. A full guide with descriptions gives more in the channel window than a short one with titles only.",
        "Logos link" to "Optional: a second list (M3U) whose channels carry logos and EPG names. It fills them in for this playlist's channels " +
            "where the playlist itself gives none.",
        "Read the EPG again" to "Fetches the playlist and the guide anew now. The guide is also read by itself twice a day, and when it runs out of programmes.",
        "Picture and sound in step" to "Standard: the app keeps them together, and you can move the sound yourself (Sound delay). " +
            "Video hardware: the box's own video path does it, which some TVs show smoother; a channel whose picture stops with it is played the standard way.",
        "Quality" to "For channels your provider sends in several qualities. Automatic follows the speed of your connection; " +
            "Always the highest never steps down, which may stall on a slow line.",
        "Dolby Digital" to "Passed on: the sound goes to the TV or soundbar as it is, with all its channels. Decoded here: the box turns it into stereo, " +
            "for a TV that stays silent with Dolby.",
        "Dolby Digital Plus" to "The same choice for the newer Dolby kind (E-AC-3). Some soundbars stay silent with it passed on: then decode it here.",
        "Clearer speech" to "Raises voices against music and effects. It works on sound decoded by the app, not on Dolby passed on to the TV. Strong lifts them further.",
        "Sound delay" to "Moves the sound against the picture, in steps of 20 ms near nought and 100 ms further out. + plays the sound later, \u2212 earlier. " +
            "If you hear the words before the lips move, go up. While watching, hold OK and use Lip sync to set it with the picture in view.",
        "Refresh rate" to "The TV's picture rate follows each channel (50 or 60 a second, or 24 for films), for even motion. The screen goes dark for a moment " +
            "when it switches. Once you stay: only after a few seconds on a channel, so that zapping does not blank the screen. At once: straight away.",
        "Catch-up" to "Watch earlier programmes, start the current one over, and pause, on channels your provider records. " +
            "Open them with Right while watching, or OK on the programme card in the channel list.",
        "After a pause" to "When a paused channel goes on, it starts a few seconds before the moment it stopped, so that no word is missed.",
        "Up on the remote" to "Which way Up and Down go through the channels while you watch.",
        "Text colour" to "The colour of all text. Soft white is easy on the eyes in a dim room; Warm is softer still for watching at night; " +
            "Bright gives the most contrast in a bright room.",
        "Clock on screen" to "A small clock stays in the top right corner while you watch.",
        "Subtitle size" to "The size of subtitles sent as text. A sample shows for a moment when you change it.",
        "Subtitle colour" to "White, or a colour that stands out more from a bright picture.",
        "Subtitle weight" to "How heavy the letters of subtitles are, from thin to bold.",
        "Subtitle letters" to "The kind of letters: plain, with serifs, all of one width (typewriter), or narrow.",
        "Subtitle background" to "Edge: a dark outline round each letter. Plate: a dark band behind the line, easiest to read on a busy picture.",
        "Subtitle height" to "Where subtitles stand: at the bottom, or further up, clear of a TV that cuts off its edges.",
        "While a channel changes" to "What shows until the new channel's picture comes. Last picture: it stays still. Black screen. Fade: to black and up again.",
        "Preview while you scroll" to "What the picture beside the channel list shows as you move through it. Your channel: what you watch keeps playing. " +
            "Channel in focus: the channel you rest on plays live after a moment.",
        "See-through bars" to "How much of the picture shows through the bar with the channel's name and through the programme list.",
        "Channels on the home screen" to "Your favourites and recent channels in a row on the TV's home screen. Each opens the app directly on that channel.",
        "Forget recent channels" to "Empties the Recent list, here and on the home screen.",
        "Aurus IPTV update" to "Looks for a newer version among the app's public releases on GitHub, and installs it with Android's own confirmation.",
        "Version" to "The version of Aurus IPTV on this device.",
        "Developer" to "Aurus IPTV is made by aze038, as part of Android TV Pack: free apps for TV boxes with no adverts, no account and no tracking.",
        "GitHub" to "The app's source, new versions, and where to report a fault or ask for something. OK shows the address as a code for your phone.",
        "Buy me a Ko-fi" to "Aurus IPTV is free and stays free. If it is useful to you, you can support its development with a coffee. OK shows the address as a code for your phone.",
        "Open-source parts" to "What the app is built with, and under which licences.",
        // the extras (Aurus Plus)
        "Aurus Plus" to "Extras for the app: colours, text size, multi-view, a second player, a PIN for groups and the settings, reminders. " +
            "One purchase unlocks them for good on your devices; watching itself stays free.",
        "Colour" to "The colour of what is in focus, the bars and the marks, everywhere in the app.",
        "Text size" to "The size of the text in the lists, the guide and the settings.",
        "Channel list" to "Roomy: each channel with its logo. Compact: names only, more channels on the screen at once.",
        "Multi-view" to "Up to four channels at once; the sound comes from the one in focus. Each picture is a stream of its own from your provider.",
        "Pictures in multi-view" to "How many channels multi-view shows at once. Some boxes can decode only two HD pictures together.",
        "Engine" to "VLC for a channel the usual player cannot play: hold OK on it while watching, then Engine.",
        "PIN" to "Four digits, asked for before locked groups are shown. Lock a group by holding OK on it in the channel list.",
        "Lock the settings" to "The PIN is asked for before the settings open, so nobody can unlock groups or change the playlist without it.",
        "Reminders" to "Programmes you asked to be told of. Set one with OK on a later programme in the programme list.",
        "When a programme begins" to "Ask: a card asks whether to switch to it. Switch: the app goes to that channel by itself.",
    )
}
