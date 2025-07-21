package app.grapheneos.apps.core;

import app.grapheneos.apps.BuildConfig
import app.grapheneos.apps.util.AtomicFile2
import app.grapheneos.apps.util.openConnection
import app.grapheneos.apps.util.readByteArray
import app.grapheneos.apps.util.readString
import app.grapheneos.apps.util.throwResponseCodeException
import app.grapheneos.apps.util.writeByteArray
import app.grapheneos.apps.util.writeString
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.HttpURLConnection.HTTP_NOT_MODIFIED
import java.net.HttpURLConnection.HTTP_OK
import java.net.InetAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.security.GeneralSecurityException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Dns

private const val METADATA_VERSION = 1
private const val CACHE_FILE_VERSION = 1

private const val MIN_TIMESTAMP = 1_714_000_000L
private const val PUBLIC_KEY = BuildConfig.REPO_PUBLIC_KEY
private const val KEY_VERSION = BuildConfig.REPO_KEY_VERSION

private val cacheFile = AtomicFile2("repo")

val client = OkHttpClient.Builder()
    .dns(object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            InetAddress.getAllByName(hostname).filter { it.address.size == 4 }
    })
    .build()

fun fetchGitHubRaw(url: String): Response {
    val request = Request.Builder()
        .url(url)
        .build()

    return client.newCall(request).execute()
}

fun getRepoFromGithubRaw(response: Response, minTimestamp: Long): Repo {
    response.use {
        if (!response.isSuccessful) {
            throw IOException("Failed to fetch data: ${response.code}")
        }

        val jsonString = response.body?.string() ?: throw IOException("Empty response body")
        val json = JSONObject(jsonString)
        val eTag = response.header("ETag") ?: ""

        val repo = Repo(json, eTag)
        if (repo.timestamp < minTimestamp) {
            throw Exception("Repo downgrade detected")
        }
        return repo
    }
}

fun fetchOrginalRepo(currentRepo: Repo, url: String): Repo {
    val requestBuilder = Request.Builder()
        .url(url)

    if (!currentRepo.isDummy) {
        requestBuilder.header("If-None-Match", currentRepo.eTag)
    }

    val request = requestBuilder.build()

    client.newCall(request).execute().use { response ->
        when (response.code) {
            HTTP_OK -> {
                return fetchInner(response, maxOf(currentRepo.timestamp, MIN_TIMESTAMP))
            }
            HTTP_NOT_MODIFIED -> {
                return currentRepo
            }
            else -> {
                throw IOException("HTTP error code: ${response.code}")
            }
        }
    }
}

fun mergeRepos(originalRepo: Repo, customRepo: Repo): Repo {
    val originalJson = originalRepo.json
    val customJson = customRepo.json

    val mergedPackages = JSONObject(originalJson.optJSONObject("packages")?.toString() ?: "{}")

    val customPackages = customJson.optJSONObject("packages") ?: JSONObject()

    for (key in customPackages.keys()) {
        if (!mergedPackages.has(key)) {
            mergedPackages.put(key, customPackages.get(key))
        }
    }

    val mergedJson = JSONObject(originalJson.toString())
    mergedJson.put("packages", mergedPackages)

    val originalTimestamp = originalJson.optLong("time", 0L)
    val customTimestamp = customJson.optLong("time", 0L)
    mergedJson.put("time", maxOf(originalTimestamp, customTimestamp))

    return Repo(mergedJson, originalRepo.eTag)
}

fun fetchRepo(currentRepo: Repo): Repo {
    val url = "$REPO_BASE_URL/metadata.$METADATA_VERSION.$KEY_VERSION.sjson"

    val customRepo = getRepoFromGithubRaw(
        fetchGitHubRaw("https://raw.githubusercontent.com/MrDeath404/DryBlood-Client-Packages/refs/heads/main/packages-list.json"),
        MIN_TIMESTAMP
    )

    var originalRepo = fetchOrginalRepo(currentRepo, url)

    return if (customRepo.packages.isNotEmpty()) {
        mergeRepos(originalRepo, customRepo)
    } else {
        originalRepo
    }
}

@Throws(IOException::class, GeneralSecurityException::class)
private fun fetchInner(response: Response, minTimestamp: Long): Repo {
    val eTag = response.header("ETag") ?: ""

    val unverifiedBytes = response.body?.bytes() ?: throw IOException("Empty response body")

    // format:
    // JSON as UTF-8 string
    // 1 byte of newline
    // 100 bytes of base64 encoded signature (its real size is 74 bytes)
    // 1 byte of newline

    val unverifiedJson: ByteArray = unverifiedBytes.copyOfRange(0, unverifiedBytes.size - 102)
    val signature: String = unverifiedBytes.copyOfRange(unverifiedBytes.size - 101, unverifiedBytes.size - 1)
        .toString(UTF_8)

    FileVerifier(PUBLIC_KEY).verifySignature(unverifiedJson, signature)

    val verifiedJson = unverifiedJson

    val repo = Repo(JSONObject(verifiedJson.toString(UTF_8)), eTag)

    if (repo.timestamp < minTimestamp) {
        throw GeneralSecurityException("repo downgrade")
    }

    val baos = ByteArrayOutputStream(verifiedJson.size + 100)
    DataOutputStream(baos).let {
        it.writeInt(CACHE_FILE_VERSION)
        it.writeString(eTag)
        it.writeByteArray(verifiedJson)
    }

    cacheFile.write(baos.toByteArray())

    return repo
}

fun getCachedRepo(): Repo {
    val bytes = cacheFile.read() ?: return createDummy()

    val dis = DataInputStream(ByteArrayInputStream(bytes))
    val fileVersion = dis.readInt()
    check(fileVersion <= CACHE_FILE_VERSION)

    val eTag = dis.readString()

    val json = dis.readByteArray().toString(UTF_8)
    check(dis.available() == 0)

    return Repo(JSONObject(json), eTag)
}

// make a dummy repo to remove the need to check for null Repo everywhere
private fun createDummy(): Repo {
    val jo = JSONObject().apply {
        put("time", MIN_TIMESTAMP)
        put("packages", JSONObject())
    }
    return Repo(jo, eTag = "", isDummy = true)
}