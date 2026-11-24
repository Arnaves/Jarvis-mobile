package com.jarvis.launcher.ai

/**
 * AXIOM's brain switchboard, on top of Gemini: one router, four specialists.
 * CHAT for everyday questions, THINK for planning/maths (shows its reasoning),
 * CODE for programming, VISION when there's an image (camera or screen).
 */
enum class Brain(val label: String, val blurb: String) {
    CHAT("Chat", "Fast everyday assistant"),
    THINK("Think", "Deep reasoning with visible thought"),
    CODE("Code", "Writes and explains code"),
    VISION("Vision", "Sees through your camera and screen");

    companion object {
        private val code = Regex("\\b(code|coding|python|bash|script|function|bug|error|compile|regex|sql|javascript|typescript|kotlin|java|html|css|api|program|algorithm|json|git)\\b", RegexOption.IGNORE_CASE)
        private val think = Regex("\\b(why|prove|plan|planning|strategy|compare|decide|calculate|solve|math|maths|step by step|reason|pros and cons|analy[sz]e|optimi[sz]e|should i|which is better|riddle|puzzle)\\b", RegexOption.IGNORE_CASE)

        /** Picks a brain for [prompt]; [forced] (from settings) wins unless an image needs VISION. */
        fun route(prompt: String, hasImage: Boolean, forced: Brain? = null): Brain = when {
            hasImage -> VISION
            forced != null && forced != VISION -> forced
            code.containsMatchIn(prompt) -> CODE
            think.containsMatchIn(prompt) -> THINK
            else -> CHAT
        }

        fun of(id: String?) = entries.firstOrNull { it.name == id }

        /** Splits a streamed reply into (thoughts, answer). */
        fun splitThoughts(s: String): Pair<String, String> {
            val thoughts = Regex("<think>(.*?)(</think>|$)", RegexOption.DOT_MATCHES_ALL).findAll(s).joinToString("\n") { it.groupValues[1].trim() }
            val answer = s.replace(Regex("<think>.*?(</think>|$)", RegexOption.DOT_MATCHES_ALL), "").trim()
            return thoughts.trim() to answer
        }

        /** What to say aloud: no code blocks, no markdown. */
        fun speakable(answer: String): String {
            val noCode = answer.replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), " (code on screen) ")
            return noCode.replace(Regex("[*_#`>|]"), "").replace(Regex("\\[\\d+\\]"), "").replace(Regex("\\s+"), " ").trim()
        }
    }
}
