// SPDX-License-Identifier: Apache-2.0
package dev.aarso.watchbrew

import android.util.AtomicFile
import java.io.File

class SessionStore(directory: File) {
    private val file = AtomicFile(File(directory, "watchbrew-demo.json"))
    fun load(): Session {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return Session()
        return SessionCodec.decode(String(file.readFully(), Charsets.UTF_8))
    }
    fun save(session: Session) {
        val stream = file.startWrite()
        try {
            stream.write(SessionCodec.encode(session).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) { file.failWrite(stream); throw e }
    }
}
