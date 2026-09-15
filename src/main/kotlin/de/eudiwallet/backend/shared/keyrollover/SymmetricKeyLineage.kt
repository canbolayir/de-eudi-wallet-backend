package de.eudiwallet.backend.shared.keyrollover

import de.eudiwallet.backend.shared.hsm.HsmKey
import de.eudiwallet.backend.shared.hsm.HsmKeyClass
import de.eudiwallet.backend.shared.hsm.HsmKeyId
import de.eudiwallet.backend.shared.hsm.HsmKeyRef
import de.eudiwallet.backend.shared.hsm.HsmProvider
import de.eudiwallet.backend.shared.hsm.findPrimaryKey
import de.eudiwallet.backend.shared.telemetry.MetricsService
import de.eudiwallet.backend.shared.telemetry.runBlockingWithTelemetry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

data class SymmetricKeySet(
    val validKeys: List<HsmKey>,
    val primary: HsmKey,
) {
    val primaryId: HsmKeyId get() = primary.keyId

    fun heldKey(): HeldKey = HeldKey(primary.keyId, primary.expiresAt())
}

class SymmetricKeyLineage<T : HsmKeyRef>(
    override val name: String,
    private val keyPrefix: String,
    private val keyClass: HsmKeyClass<T>,
    private val hsmProvider: HsmProvider,
    private val ioDispatcher: CoroutineDispatcher,
    private val metricsService: MetricsService,
    private val clock: Clock = Clock.systemDefaultZone(),
) : RefreshableLineage,
    KeySource<SymmetricKeySet> {
    private val log = KotlinLogging.logger {}
    private val held = AtomicReference<SymmetricKeySet?>(null)

    override fun current(): SymmetricKeySet {
        val keySet = requireNotNull(held.get()) { "$name lineage has no resolved key" }
        val now = Instant.now(clock)
        val heldKey = keySet.heldKey()
        if (heldKey.isExpiredAt(now)) throw ExpiredKeyException(name, heldKey)
        return keySet.copy(validKeys = keySet.validKeys.filter { now.isBefore(it.expiresAt()) })
    }

    fun initialize() = roll(failFast = true)

    override fun refresh() = roll(failFast = false)

    private fun roll(failFast: Boolean) {
        val keys = scanKeys()
        val primary = keys.findPrimaryKey(Instant.now(clock))
        if (primary == null) {
            check(!failFast) { "$name primary key not found for prefix '$keyPrefix'" }
            logHoldingLastGood()
            return
        }

        val candidate = SymmetricKeySet(keys, primary)
        val previous = held.getAndSet(candidate)
        metricsService.setPrimaryKeyExpiryDate(name, primary.endDate)
        if (previous != null && previous.primaryId != candidate.primaryId) {
            log.info { "$name: rolled over ${previous.primaryId} -> ${candidate.primaryId}" }
        }
    }

    private fun logHoldingLastGood() {
        val heldKey = held.get()?.heldKey()
        if (heldKey != null && heldKey.isExpiredAt(Instant.now(clock))) {
            log.error {
                "$name: held primary ${heldKey.keyId} expired at ${heldKey.expiresAt}; no valid primary in HSM " +
                    "scan for prefix '$keyPrefix' — refusing to sign or verify until a valid primary resolves"
            }
        } else {
            log.warn {
                "$name: no valid primary in HSM scan for prefix '$keyPrefix'; holding still-valid ${heldKey?.keyId}"
            }
        }
    }

    private fun scanKeys(): List<HsmKey> =
        runBlockingWithTelemetry(ioDispatcher) {
            hsmProvider.use("Scan $name keys") { hsm ->
                hsm.findKeysByPrefix(keyPrefix, Instant.now(clock), keyClass)
            }
        }
}

fun stubSymKeySource(): KeySource<SymmetricKeySet> =
    KeySource {
        val stubKey = HsmKey(HsmKeyId(STUB), STUB, LocalDate.MIN, LocalDate.MAX)
        SymmetricKeySet(listOf(stubKey), stubKey)
    }
