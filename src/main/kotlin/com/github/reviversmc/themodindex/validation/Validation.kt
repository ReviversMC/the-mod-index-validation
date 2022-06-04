package com.github.reviversmc.themodindex.validation

import com.github.reviversmc.themodindex.api.downloader.DefaultApiDownloader
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess


const val COROUTINES_PER_TASK = 5 // Arbitrary number of concurrent downloads. Change if better number is found.

@ExperimentalSerializationApi
fun main(args: Array<String>) {
    //TODO Validate against regex provided in json schema as well

    val apiDownloader = if (args.isEmpty()) DefaultApiDownloader()
    else DefaultApiDownloader(baseUrl = args[0])

    println("Attempting to validate index file...")

    val indexJson = try {
        apiDownloader.getOrDownloadIndexJson()
    } catch (ex: SerializationException) {
        throw SerializationException("Serialization error of \"index.json\" at repository ${apiDownloader.formattedBaseUrl}.")
    } ?: throw IOException("Could not download \"index.json\" at repository ${apiDownloader.formattedBaseUrl}.")

    val availableManifests = indexJson.identifiers.map {
        if (it.lowercase() != it) throw IllegalStateException("Identifier \"$it\" is not lowercase.")
        if (it.split(":").size != 3) throw IllegalStateException("Identifier \"$it\" is not in the format \"modloader:modname:hash\".")
        it.substringBeforeLast(":")
    }.distinct()

    val versionHashes = indexJson.identifiers.map { it.split(":")[2] }

    //Protect against the rare chance where we have a hash collision
    if (versionHashes != versionHashes.distinct()) {
        val distinctHashes = versionHashes.distinct()
        for (i in distinctHashes.indices) {
            if (versionHashes[i] != distinctHashes[i]) { //Duplicate found. The duplicate is versionHashes[i]
                val collidedIdentifiers =
                    indexJson.identifiers.filter { it.substringAfterLast(":") == versionHashes[i] }
                val name = collidedIdentifiers.first().split(":")[1]
                if (collidedIdentifiers != collidedIdentifiers.filter { it.split(":")[1] == name }) {
                    throw IllegalStateException("Hash collision found for the follow projects. A hash collision occurs when two or more different projects (the same project for a different mod loader does not count as separate projects) have the same hash for a file.\n" +
                            "Projects: ${collidedIdentifiers.joinToString(", ")}\n" +
                            "Hash: $versionHashes[$i]")
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
            async {
                manifestDownloadSemaphore.withPermit {
                    try {
                        apiDownloader.downloadManifestJson(it)
                        val currentlyChecked = checkedManifests.incrementAndGet()

                        if (currentlyChecked % 10 == 0) println("Checked $currentlyChecked / ${availableManifests.size} of manifests.")

                    } catch (ex: SerializationException) {
                        throw SerializationException("Serialization error of manifest \"$it\" at repository ${apiDownloader.formattedBaseUrl}.")
                    } catch (ex: IOException) {
                        throw IOException("Could not download manifest \"$it\" at repository ${apiDownloader.formattedBaseUrl}.")
                    }
                }
            }
        }

        manifestRequests.awaitAll()

        println("All manifests validated successfully.")
        exitProcess(0)
    }
}