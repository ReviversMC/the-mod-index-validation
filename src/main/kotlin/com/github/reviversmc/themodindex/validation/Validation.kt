package com.github.reviversmc.themodindex.validation

import com.github.reviversmc.themodindex.api.data.IndexJson
import com.github.reviversmc.themodindex.api.data.ManifestJson
import com.github.reviversmc.themodindex.api.downloader.DefaultApiDownloader
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess


const val COROUTINES_PER_TASK = 5 // Arbitrary number of concurrent downloads. Change if better number is found.

private fun validateIndexRegex(indexJson: IndexJson) {
    if (!Regex("^[0-9]+\\.[0-9]+\\.[0-9]+\$").matches(indexJson.indexVersion)) throw IllegalArgumentException("Invalid index version: ${indexJson.indexVersion}")
    indexJson.identifiers.forEach {
        if (!Regex("^[a-z0-9\\-_]+:[a-z0-9\\-_]+:[a-z0-9]{15}\$").matches(it)) throw IllegalArgumentException("Invalid identifier: $it")
    }
}

private fun validateManifestRegex(manifestJson: ManifestJson) {
    if (!Regex("^[0-9]+\\.[0-9]+\\.[0-9]+\$").matches(manifestJson.indexVersion)) throw IllegalArgumentException("Invalid index version: ${manifestJson.indexVersion} in ${manifestJson.genericIdentifier}")
    if (!Regex("^[a-z0-9\\-_]+:[a-z0-9\\-_]+\$").matches(manifestJson.genericIdentifier)) throw IllegalArgumentException("Invalid generic identifier: ${manifestJson.genericIdentifier}")
    if (!Regex("^[a-zA-Z0-9\\-_\\s]+\$").matches(manifestJson.fancyName)) throw IllegalArgumentException("Invalid fancy name: ${manifestJson.fancyName} in ${manifestJson.genericIdentifier}")
    if (!Regex("^[a-zA-Z0-9\\-_\\s]+\$").matches(manifestJson.author)) throw IllegalArgumentException("Invalid author name: ${manifestJson.author} in ${manifestJson.genericIdentifier}")
    if (manifestJson.license?.let { Regex("^[a-zA-Z0-9\\-_\\s]+\$").matches(it) } == false) throw IllegalArgumentException("Invalid license: ${manifestJson.license} in ${manifestJson.genericIdentifier}")
    // No validation needed for CF id (int)
    if (manifestJson.modrinthId?.let { Regex("^[a-zA-Z0-9]+\$").matches(it) } == false) throw IllegalArgumentException("Invalid modrinth id: ${manifestJson.modrinthId} in ${manifestJson.genericIdentifier}")
    if (manifestJson.links.issue?.let { Regex("^[a-zA-Z0-9\\-_]+\$").matches(it) } == false) throw IllegalArgumentException("Invalid issue link: ${manifestJson.links.issue} in ${manifestJson.genericIdentifier}")
    if (manifestJson.links.sourceControl?.let { Regex("^[a-zA-Z0-9\\-_]+\$").matches(it) } == false) throw IllegalArgumentException("Invalid source control link: ${manifestJson.links.sourceControl} in ${manifestJson.genericIdentifier}")
    manifestJson.links.others.forEach {
        if (!Regex("^[a-zA-Z0-9\\-_\\s]+\$").matches(it.linkName)) throw IllegalArgumentException("Invalid link name: ${it.linkName} in ${manifestJson.genericIdentifier}")
        if (!Regex("^[a-zA-Z0-9\\-_:/?&]+\$").matches(it.url)) throw IllegalArgumentException("Invalid link url: ${it.url} in ${manifestJson.genericIdentifier}")
    }
    manifestJson.files.forEach {versionFile ->
        if (!Regex("^[a-zA-Z0-9\\-_\\s]+\$").matches(versionFile.fileName)) throw IllegalArgumentException("Invalid file name: ${versionFile.fileName} in ${manifestJson.genericIdentifier}")
        versionFile.mcVersions.forEach {
            if (!Regex("^[0-9]+\\.[0-9]+\\.[0-9]+[a-zA-Z0-9\\-+._\\s]*\$").matches(it)) throw IllegalArgumentException("Invalid MC version: $it in ${manifestJson.genericIdentifier}")
        }
        if (!Regex("^[a-z0-9]{15}\$").matches(versionFile.shortSha512Hash)) throw IllegalArgumentException("Invalid SHA512 hash: ${versionFile.shortSha512Hash} in ${manifestJson.genericIdentifier}")
        versionFile.downloadUrls.forEach {
            if (!Regex("^[a-zA-Z0-9\\-_:/?&]+\$").matches(it)) throw IllegalArgumentException("Invalid download url: $it in ${manifestJson.genericIdentifier}")
        }
        // No validation needed for curseDownloadAvailable (boolean)
        versionFile.relationsToOtherMods.required.forEach {
            if (!Regex("^[a-z0-9\\-_]+:[a-z0-9\\-_]+\$").matches(it)) throw IllegalArgumentException("Invalid required mod: $it in ${manifestJson.genericIdentifier}")
        }
        versionFile.relationsToOtherMods.incompatible.forEach {
            if (!Regex("^[a-z0-9\\-_]+:[a-z0-9\\-_]+\$").matches(it)) throw IllegalArgumentException("Invalid incompatible mod: $it in ${manifestJson.genericIdentifier}")
        }
    }

}

fun main(args: Array<String>) {

    val apiDownloader = if (args.isEmpty()) DefaultApiDownloader()
    else DefaultApiDownloader(baseUrl = args[0])

    println("Attempting to validate index file...")

    val indexJson = try {
        apiDownloader.getOrDownloadIndexJson()
    } catch (ex: SerializationException) {
        throw SerializationException("Serialization error of \"index.json\" at repository ${apiDownloader.formattedBaseUrl}.")
    } ?: throw IOException("Could not download \"index.json\" at repository ${apiDownloader.formattedBaseUrl}.")

    validateIndexRegex(indexJson)
    val availableManifests = indexJson.identifiers.map { it.substringBeforeLast(":") }.distinct()

    val versionHashes = indexJson.identifiers.map { it.substringAfterLast(":") }

    // Protect against the rare chance where we have a hash collision
    if (versionHashes != versionHashes.distinct()) {
        val distinctHashes = versionHashes.distinct()
        for (i in distinctHashes.indices) {
            if (versionHashes[i] != distinctHashes[i]) { // Duplicate found. The duplicate is versionHashes[i]
                val collidedIdentifiers =
                    indexJson.identifiers.filter { it.substringAfterLast(":") == versionHashes[i] }
                val name = collidedIdentifiers.first().split(":")[1]
                if (collidedIdentifiers != collidedIdentifiers.filter { it.split(":")[1] == name }) {
                    throw IllegalStateException(
                        "Hash collision found for the follow projects. A hash collision occurs when two or more different projects (the same project for a different mod loader does not count as separate projects) have the same hash for a file.\n" +
                                "Projects: ${collidedIdentifiers.joinToString(", ")}\n" +
                                "Hash: ${versionHashes[i]}"
                    )
                }
            }
        }
    }

    println("Index file validated successfully.")

    println("Attempting to validate all manifests...")

    runBlocking {
        val manifestDownloadSemaphore = Semaphore(COROUTINES_PER_TASK)

        val checkedManifests = AtomicInteger(0)

        val manifestRequests = availableManifests.map {
            launch {
                manifestDownloadSemaphore.withPermit {
                    try {
                        val manifest = apiDownloader.downloadManifestJson(it) ?: throw IOException("Could not download manifest for $it")
                        val currentlyChecked = checkedManifests.incrementAndGet()
                        try {
                            validateManifestRegex(manifest)
                        } catch (ex: IllegalArgumentException) {
                            ex.printStackTrace()
                            exitProcess(1)
                        }
                        if (currentlyChecked % 10 == 0) println("Checked $currentlyChecked / ${availableManifests.size} of manifests.")

                    } catch (ex: SerializationException) {
                        throw SerializationException("Serialization error of manifest \"$it\" at repository ${apiDownloader.formattedBaseUrl}.")
                    } catch (ex: IOException) {
                        throw IOException("Could not download manifest \"$it\" at repository ${apiDownloader.formattedBaseUrl}.")
                    }
                }
            }
        }

        manifestRequests.joinAll()

        println("All manifests validated successfully.")
        exitProcess(0)
    }
}