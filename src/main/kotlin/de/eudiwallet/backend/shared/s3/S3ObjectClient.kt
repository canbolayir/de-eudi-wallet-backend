package de.eudiwallet.backend.shared.s3

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodySubscribers
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

open class S3ClientException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class S3ObjectNotFoundException(
    bucket: String,
    key: String,
) : S3ClientException("Object not found: $bucket/$key")

internal class S3Response(
    val status: Int,
    val body: ByteArray,
)

class S3ObjectClient(
    private val endpoint: URI,
    private val signer: S3RequestSigner,
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
    private val requestTimeout: Duration = REQUEST_TIMEOUT,
) : AutoCloseable by httpClient {
    fun getObject(
        bucket: String,
        key: String,
    ): ByteArray {
        val response = request("GET", s3ObjectPath(bucket, key), ByteArray(0))
        val code = response.errorCode()
        return when {
            response.status == HTTP_OK -> {
                response.body
            }

            response.status == HTTP_NOT_FOUND && code == "NoSuchKey" -> {
                throw S3ObjectNotFoundException(bucket, key)
            }

            else -> {
                throw response.toException("GET $bucket/$key")
            }
        }
    }

    internal fun request(
        method: String,
        path: String,
        body: ByteArray,
    ): S3Response {
        val uri = endpoint.resolve(path)
        val request = HttpRequest.newBuilder(uri).method(method, HttpRequest.BodyPublishers.ofByteArray(body))
        signer.sign(method, uri, body).forEach { (name, value) -> request.header(name, value) }
        val future =
            httpClient.sendAsync(request.build()) {
                BodySubscribers.limiting(BodySubscribers.ofByteArray(), MAX_BODY_BYTES)
            }
        return try {
            val response = future.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
            S3Response(response.statusCode(), response.body())
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw S3ClientException("$method $uri timed out after $requestTimeout", e)
        } catch (e: ExecutionException) {
            throw S3ClientException("$method $uri failed", e.cause ?: e)
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw S3ClientException("$method $uri interrupted", e)
        }
    }

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)
        const val MAX_BODY_BYTES = (1 shl 20).toLong()
        const val HTTP_OK = 200
        const val HTTP_NOT_FOUND = 404
    }
}

internal fun S3Response.toException(operation: String): S3ClientException =
    S3ClientException("$operation returned HTTP $status${errorCode()?.let { " $it" } ?: ""}")

internal fun S3Response.errorCode(): String? = ERROR_CODE.find(body.decodeToString())?.groupValues?.get(1)

private val ERROR_CODE = Regex("<Code>([A-Za-z]{1,64})</Code>")

fun s3ObjectPath(
    bucket: String,
    key: String? = null,
): String {
    require(bucket.isNotBlank()) { "S3 bucket name must not be blank" }
    val segments = listOf(bucket) + (key?.split("/") ?: emptyList())
    return segments.joinToString("/", prefix = "/") { it.sigV4Encode() }
}

private fun String.sigV4Encode(): String =
    buildString {
        for (byte in this@sigV4Encode.toByteArray()) {
            val char = byte.toInt().toChar()
            if (char.isUnreserved()) append(char) else append("%%%02X".format(byte.toInt() and BYTE_MASK))
        }
    }

private fun Char.isUnreserved(): Boolean = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this in "-_.~"

private const val BYTE_MASK = 0xFF
