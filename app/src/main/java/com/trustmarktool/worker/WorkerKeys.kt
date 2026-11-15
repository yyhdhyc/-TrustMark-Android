package com.trustmarktool.worker

object WorkerKeys {
    // Encode: the task list is a JSON file in app-private storage because
    // WorkManager Data is capped at 10 KB.
    const val TASKS_PATH = "tasks_path"
    const val BATCH_ID = "batch_id"
    const val STRENGTH = "strength"

    // Decode keeps the image URI list.
    const val URIS = "uris"

    const val PHASE = "phase"
    const val COMPLETED = "completed"
    const val TOTAL = "total"
    const val CURRENT = "current_file"
    const val ERROR = "error"
    const val RESULT_COUNT = "result_count"
    const val FAILED_COUNT = "failed_count"
    const val SUMMARY = "summary"
    const val DETAILS = "details"
    const val STATE = "task_state"

    const val UNIQUE_ENCODE = "trustmark_encode_batch"
    const val UNIQUE_DECODE = "trustmark_decode_batch"
}
