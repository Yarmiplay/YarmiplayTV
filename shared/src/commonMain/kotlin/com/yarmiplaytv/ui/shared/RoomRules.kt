package com.yarmiplaytv.ui.shared

/** The community rules, agreed to once before the first room join; the same as docs/terms.md. */
val ROOM_RULES = listOf(
    "Rooms run on Syncplay servers hosted by other people, not by YarmiplayTV. Anyone who knows a room's name can join it and chat.",
    "Don't post anything illegal, hateful, harassing or sexual, and don't share links to such content.",
    "If someone bothers you, block their name in the room's list of people, hide room chat (in the chat panel or Settings), or leave the room.",
    "Report abuse on a server to the people who run that server.",
)

const val ROOM_RULES_URL = "https://tv.yarmiplay.com/terms/"

const val BLOCKED_NOTE = "Blocked: their messages are hidden until you leave the room. A new name gets through."

const val HIDDEN_CHAT_NOTE = "Room chat is hidden: nobody's messages are shown, whatever name they use."

const val ROOM_PRIVACY_HINT = "Anyone who knows the room's name can join it on that server, so pick one that's hard to guess, or use your own server with a password."
