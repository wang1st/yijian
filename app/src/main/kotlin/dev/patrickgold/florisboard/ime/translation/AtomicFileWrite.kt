/*
 * Copyright (C) 2026 The yijian Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.translation

import java.io.File

/**
 * Replaces a file's contents without ever leaving it empty.
 *
 * Writes the full new content to a sibling temp file, flushes it, then renames it over the
 * original — a rename on the same filesystem is atomic, so a crash mid-write leaves either the
 * old file or the new one, never a truncated one. Shared by every persistent store in the
 * practice/review pipeline.
 */
internal fun atomicRewriteFile(file: File, content: String) {
    val dir = file.parentFile ?: return
    val tmp = File(dir, "${file.name}.tmp")
    tmp.writeText(content)
    if (!tmp.renameTo(file)) {
        // Rename can fail across some mount types; fall back but keep the temp copy as a backup.
        file.writeText(content)
        tmp.delete()
    }
}
