package com.github.reviversmc.themodindex.validation

import com.github.reviversmc.themodindex.api.downloader.DefaultApiDownloader
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient

fun main(args: Array<String>) {

    val okHttpClient = OkHttpClient()
    val apiDownloader = if (args.isEmpty()) DefaultApiDownloader(okHttpClient)
    else DefaultApiDownloader(okHttpClient, args[0])

    println("Attempting to validate index file...")

    val indexJson = try {
        apiDownloader.getOrDownloadIndexJson()
    } catch (ex: SerializationException) {
        throw SerializationException("Serialization error of \"index.json\" at repository ${apiDownloader.repositoryUrlAsString}.")
    }

    println("Index file validated successfully.")

    val availableManifests = indexJson?.files?.mapNotNull { indexFile ->
        indexFile.identifier?.let {
            it.substring(0, it.lastIndexOf(":"))
        }
    }?.toSet() ?: return //Make set to remove duplicates

    println("Attempting to validate all manifests...")

    var lastPercentagePrinted = 0.0
    availableManifests.forEachIndexed { manifestNum, it ->
        try {
            apiDownloader.downloadManifestJson(it) //Just check if successful
        } catch (ex: SerializationException) {
            throw SerializationException("Serialization error of \"$it\" at repository ${apiDownloader.repositoryUrlAsString}.")
        }

        /*
        Add one to manifest num as it is 0 indexed.
        If there is only one entry, we should get (((0 + 1) / 1) * 100) = 100%, not ((0 / 1) * 100) = 0%.
         */
        val percentageComplete = (((manifestNum + 1) / availableManifests.size.toDouble()) * 100).toInt()

        //We want to print progress every 10% from 0 to 100
        if (percentageComplete >= (((lastPercentagePrinted / 10) + 1)) * 10) {
            println("Manifest validation $percentageComplete% complete.")
            lastPercentagePrinted = percentageComplete.toDouble()
        }
    }
    println("All manifests validated successfully.")
}