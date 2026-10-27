package haengcha.app

import haengcha.judge.JournalHeader
import haengcha.judge.JournalState
import haengcha.judge.TripJournal
import java.io.BufferedWriter
import java.io.File

/**
 * 진행 중인 주행 일지 파일(형식은 core-judge [TripJournal]). 주행이 끝나면 지운다.
 * 차속 표본은 10개(약 1초)마다 디스크에 내려 쓰고, 지역·아이 지킴이처럼 드문 줄은 바로 내려 쓴다.
 */
class TripJournalFile(private val file: File) {

    private var out: BufferedWriter? = null
    private var pending = 0

    fun load(): JournalState? = if (file.exists()) TripJournal.parse(file.readLines()) else null

    fun begin(header: JournalHeader) {
        close()
        out = file.bufferedWriter()
        append(TripJournal.header(header), flush = true)
    }

    /** 다시 읽은 일지에 이어 쓴다. */
    fun reopen() {
        close()
        out = BufferedWriter(java.io.FileWriter(file, true))
    }

    fun append(line: String, flush: Boolean = false) {
        val o = out ?: return
        o.write(line)
        o.newLine()
        if (flush || ++pending >= 10) {
            o.flush()
            pending = 0
        }
    }

    fun clear() {
        close()
        file.delete()
    }

    private fun close() {
        runCatching { out?.close() }
        out = null
        pending = 0
    }
}
