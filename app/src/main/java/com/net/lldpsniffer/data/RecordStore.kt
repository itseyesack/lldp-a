package com.net.lldpsniffer.data

import android.content.Context
import android.util.Log
import com.net.lldpsniffer.model.MergedSwitchportRecord
import com.net.lldpsniffer.model.fromJson
import com.net.lldpsniffer.model.toJson
import org.json.JSONArray
import java.io.File

class RecordStore(private val context: Context) {

    companion object {
        private const val TAG = "RecordStore"
        private const val FILE_NAME = "records_history.json"
    }

    private val file: File get() = File(context.filesDir, FILE_NAME)

    fun load(): List<MergedSwitchportRecord> {
        return try {
            if (!file.exists()) return emptyList()
            val array = JSONArray(file.readText())
            (0 until array.length()).map { MergedSwitchportRecord.fromJson(array.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading records history, starting empty", e)
            emptyList()
        }
    }

    fun save(records: List<MergedSwitchportRecord>) {
        try {
            val array = JSONArray()
            records.forEach { array.put(it.toJson()) }
            val tempFile = File(context.filesDir, "$FILE_NAME.tmp")
            tempFile.writeText(array.toString())
            tempFile.renameTo(file)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving records history", e)
        }
    }
}
