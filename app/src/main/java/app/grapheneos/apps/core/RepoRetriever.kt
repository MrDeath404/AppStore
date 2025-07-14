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
import java.net.HttpURLConnection
import java.net.HttpURLConnection.HTTP_NOT_MODIFIED
import java.net.HttpURLConnection.HTTP_OK
import java.nio.charset.StandardCharsets.UTF_8
import java.security.GeneralSecurityException
import okhttp3.OkHttpClient
import okhttp3.Request

private const val METADATA_VERSION = 1
private const val CACHE_FILE_VERSION = 1

private const val MIN_TIMESTAMP = 1_714_000_000L
private const val PUBLIC_KEY = BuildConfig.REPO_PUBLIC_KEY
private const val KEY_VERSION = BuildConfig.REPO_KEY_VERSION

private val cacheFile = AtomicFile2("repo")

@Throws(Exception::class)
fun fetchGitHubRawJson(url: String, minTimestamp: Long): Repo {
    val client = OkHttpClient()

    val request = Request.Builder()
        .url(url)
        .build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw Exception("Failed to fetch data: ${response.code}")
        }

        val eTag = response.header("ETag") ?: ""

        val jsonString = response.body?.string() ?: throw Exception("Empty response body")

        val repo = Repo(JSONObject(jsonString), eTag)

        if (repo.timestamp < minTimestamp) {
            throw Exception("repo downgrade detected")
        }

        return repo
    }
}

fun fetchOrginalRepo(currentRepo: Repo, url: String): Repo {
    return if (!currentRepo.isDummy) {
        openConnection(null, url) {
            setRequestProperty("If-None-Match", currentRepo.eTag)
        }.use { conn ->
            when (conn.v.responseCode) {
                HTTP_OK -> {
                    fetchInner(conn.v,
                        // in case MIN_TIMESTAMP was updated
                        maxOf(currentRepo.timestamp, MIN_TIMESTAMP))
                }
                HTTP_NOT_MODIFIED -> {
                    currentRepo
                }
                else -> throwResponseCodeException(conn.v)
            }
        }
    } else {
        openConnection(null, url, {}).use { conn ->
            if (conn.v.responseCode != HTTP_OK) {
                throwResponseCodeException(conn.v)
            }

            fetchInner(conn.v, MIN_TIMESTAMP)
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

    val customRepo = fetchGitHubRawJson(
        "https://raw.githubusercontent.com/MrDeath404/DryBlood-Client-Packages/refs/heads/main/packages-list.json",
        MIN_TIMESTAMP
    )

    var originalRepo = fetchOrginalRepo(currentRepo, url)

    return if (customRepo.packages.isNotEmpty()) {
        mergeRepos(originalRepo, customRepo)
    } else {
        originalRepo
    }
}

private fun fetchInner(conn: HttpURLConnection, minTimestamp: Long): Repo {
    val eTag = conn.getHeaderField("ETag") ?: ""

    val unverifiedBytes = conn.inputStream.use {
        it.readBytes()
    }

    // format:
    // JSON as UTF-8 string
    // 1 byte of newline
    // 100 bytes of base64 encoded signature (its real size is 74 bytes)
    // 1 byte of newline

    val unverifiedJson: ByteArray = unverifiedBytes.copyOfRange(0, unverifiedBytes.size - 102)
    val signature: String =
        unverifiedBytes.copyOfRange(unverifiedBytes.size - 101, unverifiedBytes.size - 1)
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