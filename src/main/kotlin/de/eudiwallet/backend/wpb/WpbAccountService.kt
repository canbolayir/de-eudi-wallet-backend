package de.eudiwallet.backend.wpb

import de.eudiwallet.backend.shared.crypto.ecPublicKeyFromX509
import de.eudiwallet.backend.shared.crypto.jwkThumbprint
import de.eudiwallet.backend.shared.crypto.toCanonicalP256
import de.eudiwallet.backend.shared.mdvmtoken.MdvmAccountId
import de.eudiwallet.backend.shared.messaging.Module
import de.eudiwallet.backend.shared.messaging.WalletInstanceRevocationEvent
import de.eudiwallet.backend.shared.messaging.WalletInstanceRevocationOutcome
import de.eudiwallet.backend.shared.telemetry.TelemetryService
import de.eudiwallet.backend.statuslist.StatusListEntryException
import de.eudiwallet.backend.statuslist.StatusListService
import de.eudiwallet.backend.statuslist.StatusReference
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.interfaces.ECPublicKey
import java.time.Instant
import java.util.UUID

data class WpbRegistration(
    val account: WpbAccount,
    val revocationCode: String,
)

@Service
class WpbAccountService(
    private val repository: WpbAccountRepository,
    private val statusListService: StatusListService,
    private val revocationCodeGenerator: RevocationCodeGenerator,
    private val telemetryService: TelemetryService,
) {
    suspend fun createAccount(
        wiMdvmAuthPubk: ECPublicKey,
        mdvmWiId: MdvmAccountId,
    ): WpbRegistration =
        telemetryService.withSpan("WpbAccountService.createAccount") {
            val canonicalPubk = wiMdvmAuthPubk.toCanonicalP256()
            val wpbAccountId = UUID.randomUUID()
            val revocationCode = revocationCodeGenerator.generate()
            val savedEntity =
                try {
                    repository.save(
                        WpbAccountEntity(
                            id = UUID.randomUUID(),
                            wpbAccountId = wpbAccountId,
                            wiMdvmAuthPubkDer = canonicalPubk.encoded,
                            wpbWiRevocationCodeHash = revocationCode.hash,
                            wiHandle = canonicalPubk.jwkThumbprint().toString(),
                            mdvmWiId = mdvmWiId.id,
                        ),
                    )
                } catch (ex: DuplicateKeyException) {
                    throw KeyAlreadyRegisteredException(ex)
                }
            WpbRegistration(WpbAccount.fromEntity(savedEntity), revocationCode.code)
        }

    suspend fun buildRevocationEvent(revocationCode: String): WalletInstanceRevocationEvent? =
        telemetryService.withSpan("WpbAccountService.buildRevocationEvent") {
            val hash = revocationCodeToHash(revocationCode)
            val entity = repository.findByRevocationHash(hash) ?: throw RevocationCodeNotFoundException()
            if (entity.revokedAt != null) {
                return@withSpan null
            }
            WalletInstanceRevocationEvent(
                eventId = UUID.randomUUID().toString(),
                wiHandle = entity.wiMdvmAuthPubkDer.ecPublicKeyFromX509().jwkThumbprint().toString(),
                occurredAt = Instant.now().toString(),
                source = Module.WPB.name,
            )
        }

    @Transactional
    suspend fun revokeByWiHandle(wiHandle: String): WalletInstanceRevocationOutcome =
        telemetryService.withSpan("WpbAccountService.revokeByWiHandle") {
            val revokedAccountId = repository.revokeByWiHandleReturningId(wiHandle)
            when {
                revokedAccountId != null -> {
                    statusListService.revokeAccountEntries(revokedAccountId)
                    WalletInstanceRevocationOutcome.APPLIED
                }

                repository.existsByWiHandleAndRevokedAtIsNotNull(wiHandle) -> {
                    WalletInstanceRevocationOutcome.ALREADY_REVOKED
                }

                else -> {
                    WalletInstanceRevocationOutcome.UNKNOWN_HANDLE
                }
            }
        }

    suspend fun findActiveAccount(
        id: WpbAccountId,
        authPubKey: ECPublicKey,
    ): WpbAccount =
        telemetryService.withSpan("WpbAccountService.findActiveAccount") {
            val entity = repository.findByWpbAccountId(id.id) ?: throw AccountNotFoundException()
            WpbAccount.fromEntity(entity.verifyAuthPubKey(authPubKey).requireNotRevoked())
        }

    suspend fun provisionWiaEntry(
        account: WpbAccount,
        clientInstanceId: UUID?,
    ): StatusReference =
        telemetryService.withSpan("WpbAccountService.provisionWiaEntry") {
            val accountId = account.wpbAccountId.id
            val reference =
                clientInstanceId?.let {
                    try {
                        statusListService.reuse(it, accountId, WPB_WIA_POOL)
                    } catch (_: StatusListEntryException) {
                        null
                    }
                } ?: statusListService.allocate(accountId, WPB_WIA_POOL)
            (repository.findByWpbAccountIdForShare(accountId) ?: throw AccountNotFoundException()).requireNotRevoked()
            reference
        }

    private fun WpbAccountEntity.verifyAuthPubKey(authPubKey: ECPublicKey): WpbAccountEntity {
        if (wiMdvmAuthPubkDer.ecPublicKeyFromX509().jwkThumbprint() != authPubKey.jwkThumbprint()) {
            throw AccountNotFoundException()
        }
        return this
    }

    private fun WpbAccountEntity.requireNotRevoked(): WpbAccountEntity {
        if (revokedAt != null) {
            throw AccountRevokedException()
        }
        return this
    }

    @Transactional
    suspend fun deleteAccount(
        id: WpbAccountId,
        authPubKey: ECPublicKey,
    ) = telemetryService.withSpan("WpbAccountService.deleteAccount") {
        val entity = repository.findByWpbAccountIdForUpdate(id.id) ?: throw AccountNotFoundException()
        entity.verifyAuthPubKey(authPubKey).requireNotRevoked()

        statusListService.revokeAccountEntries(entity.wpbAccountId)
        repository.deleteByWpbAccountId(entity.wpbAccountId)
    }
}
