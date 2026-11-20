package com.sky.siplink.core.logic

import com.sky.siplink.core.model.Phrase

/**
 * Phrases as JSON text, for a store that keeps a text of the interface across restarts and app
 * updates: kept as data, never rendered, so that it shows in the language of the moment. The
 * resources go by their names (`call_failed`), which stay, not by their ids, which change from
 * build to build. Arguments that are no Phrase are kept as text.
 *
 * @param nameOf the name of a string or plural resource
 * @param idOf the id of a resource by its name, a plural one or not; null for one this version does not have
 */
class PhraseCodec(private val nameOf: (Int) -> String, private val idOf: (name: String, plural: Boolean) -> Int?) {
    fun encode(phrases: List<Phrase>): String = Json.write(phrases.map(::tree))

    /**
     * The phrases of [text] written by [encode]; null for none. A text that is no such JSON (a store
     * written before the app kept phrases) is one plain phrase; a phrase whose resource is gone is left out.
     */
    fun decode(text: String): List<Phrase>? {
        if (text.isEmpty()) return null
        val parsed = runCatching { Json.parse(text) }.getOrNull() as? List<*> ?: return listOf(Phrase.plain(text))
        return parsed.mapNotNull(::phrase).takeIf { it.isNotEmpty() }
    }

    private fun tree(phrase: Phrase): Map<String, Any> = when (phrase) {
        is Phrase.Res -> mapOf(STRING to nameOf(phrase.id), ARGS to phrase.args.map(::argument))
        is Phrase.Count -> mapOf(PLURAL to nameOf(phrase.id), COUNT to phrase.count, ARGS to phrase.args.map(::argument))
        is Phrase.Plain -> mapOf(TEXT to phrase.text)
    }

    private fun argument(value: Any): Any = if (value is Phrase) tree(value) else value.toString()

    private fun phrase(node: Any?): Phrase? {
        val map = node as? Map<*, *> ?: return null
        val args = (map[ARGS] as? List<*>).orEmpty().map { if (it is Map<*, *>) phrase(it) ?: return null else it.toString() }
        (map[STRING] as? String)?.let { name -> return idOf(name, false)?.let { Phrase.Res(it, args) } }
        (map[PLURAL] as? String)?.let { name ->
            val count = (map[COUNT] as? Number)?.toInt() ?: return null
            return idOf(name, true)?.let { Phrase.Count(it, count, args) }
        }
        return (map[TEXT] as? String)?.let(Phrase::plain)
    }

    private companion object {
        const val STRING = "string"
        const val PLURAL = "plural"
        const val COUNT = "count"
        const val ARGS = "args"
        const val TEXT = "text"
    }
}
