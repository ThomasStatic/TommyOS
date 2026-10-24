package app.lumen.sports

import org.json.JSONArray
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The array's objects, skipping anything that isn't one. */
internal fun JSONArray.objects(): List<org.json.JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

/** Writes via a temporary file moved into place, so a crash mid-write never leaves a half-written file. */
internal fun writeAtomically(file: File, text: String) {
    val tmp = File(file.parentFile, "${file.name}.tmp")
    tmp.writeText(text)
    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}
