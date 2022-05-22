package com.github.reviversmc.themodindex.validation

import com.github.reviversmc.themodindex.api.downloader.DefaultApiDownloader
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

@ExperimentalSerializationApi
fun main(args: Array<String>) {

    val okHttpClient = OkHttpClient()
    val apiDownloader = if (args.isEmpty()) DefaultApiDownloader(okHttpClient)
    else DefaultApiDownloader(okHttpClient, args[0])

    println("Attempting to validate index file...")

    val indexJson = try {
        apiDownloader.getOrDownloadIndexJson()
    } catch (ex: SerializationException) {
        throw SerializationException("Serialization error of \"index.json\" at repository ${apiDownloader.repositoryUrlAsString}.")
    } ?: throw IOException("Could not download \"index.json\" at repository ${apiDownloader.repositoryUrlAsString}.")

    val availableManifests = indexJson.identifiers.map {
        if (it.lowercase() != it) throw IllegalStateException("Identifier \"$it\" is not lowercase.")
        if (it.split(":").size != 3) throw IllegalStateException("Identifier \"$it\" is not in the format \"modloader:modname:hash\".")
        it.substring(0, it.lastIndexOf(":"))
    }.distinct()

    val versionHashes = indexJson.identifiers.map { it.split(":")[2] }

    //Protect against the rare chance where we have a hash collision
    if (versionHashes != versionHashes.distinct()) throw IllegalStateException("Duplicate version hashes found.")

    println("Index file validated successfully.")

    println("Attempting to validate all manifests...")

    runBlocking {
        val checkedManifests = AtomicInteger(0)
        val lastPercentagePrinted = AtomicInteger(0)
        availableManifests.forEach {
            launch {
                try {
                    apiDownloader.asyncDownloadManifestJson(it)
                    checkedManifests.incrementAndGet()
                    if (((checkedManifests.toDouble() / availableManifests.size) * 100).toInt() > lastPercentagePrinted.get()) {
                        println("Checked ${(checkedManifests.toDouble() / availableManifests.size) * 100}% of manifests.")
                        lastPercentagePrinted.set(((checkedManifests.toDouble() / availableManifests.size) * 100).toInt())
                    }
                } catch (ex: SerializationException) {
                    throw SerializationException("Serialization error of manifest \"$it\" at repository ${apiDownloader.repositoryUrlAsString}.")
                } catch (ex: IOException) {
                    throw IOException("Could not download manifest \"$it\" at repository ${apiDownloader.repositoryUrlAsString}.")
                }
            }
        }
        println("All manifests validated successfully.")
        exitProcess(0)
    }
}