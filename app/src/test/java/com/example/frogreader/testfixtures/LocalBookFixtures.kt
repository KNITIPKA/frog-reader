package com.example.frogreader.testfixtures

import java.io.File

/** Optional local books are never bundled in the repository or required by CI. */
object LocalBookFixtures {
    private val directories: List<File> = buildList {
        // The environment variable also reaches Gradle's forked test JVM.
        System.getProperty("frogreader.testBookDir")?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
        System.getenv("FROGREADER_TEST_BOOK_DIR")?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
        add(File(".testbooks"))
        add(File("../.testbooks"))
        add(File(System.getProperty("user.home"), "Downloads"))
    }.distinctBy { it.absolutePath }

    fun find(name: String): File = directories.asSequence()
        .map { File(it, name) }
        .firstOrNull { candidate ->
            runCatching { candidate.inputStream().use { it.read() } }.isSuccess
        }
        ?: File(directories.first(), name)
}
