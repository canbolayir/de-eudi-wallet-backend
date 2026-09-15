package de.eudiwallet.backend.shared.s3

import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awssigning.AwsSignedBodyHeader
import aws.smithy.kotlin.runtime.auth.awssigning.AwsSigningConfig
import aws.smithy.kotlin.runtime.auth.awssigning.DefaultAwsSigner
import aws.smithy.kotlin.runtime.auth.awssigning.HashSpecification
import aws.smithy.kotlin.runtime.http.Headers
import aws.smithy.kotlin.runtime.http.HttpBody
import aws.smithy.kotlin.runtime.http.HttpMethod
import aws.smithy.kotlin.runtime.http.request.HttpRequest
import aws.smithy.kotlin.runtime.net.url.Url
import de.eudiwallet.backend.shared.crypto.toSha256
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.time.Clock
import java.util.TreeMap
import aws.smithy.kotlin.runtime.time.Instant as SdkInstant

class S3RequestSigner(
    accessKey: String,
    secretKey: String,
    private val region: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val credentials = Credentials(accessKey, secretKey)

    fun sign(
        method: String,
        uri: URI,
        payload: ByteArray,
        headers: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val request =
            HttpRequest(
                method = HttpMethod.parse(method),
                url = Url.parse(uri.toString()),
                headers = Headers { headers.forEach { (name, value) -> append(name, value) } },
                body = HttpBody.fromBytes(payload),
            )
        val config =
            AwsSigningConfig {
                region = this@S3RequestSigner.region
                service = SERVICE
                credentials = this@S3RequestSigner.credentials
                signingDate = clock.instant().let { SdkInstant.fromEpochSeconds(it.epochSecond, it.nano) }
                useDoubleUriEncode = false
                normalizeUriPath = false
                signedBodyHeader = AwsSignedBodyHeader.X_AMZ_CONTENT_SHA256
                hashSpecification = HashSpecification.Precalculated(payload.toSha256().toHexString())
            }
        val signed = runBlocking { DefaultAwsSigner.sign(request, config).output }
        return signed.headers
            .entries()
            .filterNot { it.key.equals(HOST, ignoreCase = true) }
            .associateTo(TreeMap(String.CASE_INSENSITIVE_ORDER)) { it.key to it.value.joinToString(",") }
    }

    private companion object {
        const val SERVICE = "s3"
        const val HOST = "Host"
    }
}
