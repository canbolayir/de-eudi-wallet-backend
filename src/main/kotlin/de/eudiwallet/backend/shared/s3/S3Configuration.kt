package de.eudiwallet.backend.shared.s3

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI

@ConfigurationProperties(prefix = "s3")
class S3Properties(
    val endpoint: String,
    val region: String,
    val bucket: String,
    val accessKey: String,
    val secretKey: String,
)

@Configuration
class S3ClientConfiguration(
    private val properties: S3Properties,
) {
    @Bean
    fun s3ObjectClient(): S3ObjectClient {
        val endpoint = URI.create(properties.endpoint)
        require(endpoint.isOrigin()) {
            "s3.endpoint must be an origin: http[s]://host[:port] without path, credentials or query"
        }
        return S3ObjectClient(
            endpoint = endpoint,
            signer = S3RequestSigner(properties.accessKey, properties.secretKey, properties.region),
        )
    }
}

private fun URI.isOrigin(): Boolean =
    (scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)) &&
        host != null &&
        rawUserInfo == null &&
        rawQuery == null &&
        rawFragment == null &&
        (rawPath.isNullOrEmpty() || rawPath == "/")
