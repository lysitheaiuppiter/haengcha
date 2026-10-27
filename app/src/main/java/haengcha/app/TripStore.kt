package haengcha.app

import haengcha.judge.TripRecord
import java.io.File

/** 주행 기록을 앱 내부 저장소의 CSV 파일에 한 줄씩 쌓는다. 형식이 깨진 줄은 읽을 때 건너뛴다. */
class TripStore(private val file: File) {

    fun load(): List<TripRecord> =
        if (file.exists()) file.readLines().mapNotNull(TripRecord::parse) else emptyList()

    fun append(record: TripRecord) {
        val text = buildString {
            if (!file.exists()) appendLine(TripRecord.CSV_HEADER)
            appendLine(record.toCsv())
        }
        file.appendText(text)
    }
}
