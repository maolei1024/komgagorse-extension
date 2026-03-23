package eu.kanade.tachiyomi.extension.all.komgagorse

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class KomgaGorseFactory : SourceFactory {
    override fun createSources(): List<Source> {
        val firstKomga = KomgaGorse("")
        val komgaCount = firstKomga.preferences
            .getString(KomgaGorse.PREF_EXTRA_SOURCES_COUNT, KomgaGorse.PREF_EXTRA_SOURCES_DEFAULT)!!
            .toInt()

        return buildList(komgaCount) {
            add(firstKomga)

            for (i in 0 until komgaCount) {
                add(KomgaGorse("${i + 2}"))
            }
        }
    }
}
