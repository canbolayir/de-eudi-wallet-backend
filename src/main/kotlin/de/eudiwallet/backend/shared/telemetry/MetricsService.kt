package de.eudiwallet.backend.shared.telemetry

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.Meter
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

private const val METRICS_PREFIX = "wallet_backend_"

enum class PushMetricOutcome {
    DELIVERED,
    TERMINAL,
    TRANSIENT,
    NO_REGISTRATION,
}

enum class HsmRetryOutcome {
    RECOVERED,
    EXHAUSTED,
    NO_FREE_SESSION,
}

@Component
class MetricsService(
    private val openTelemetry: OpenTelemetry,
    @Value($$"${info.application.version}") private val applicationVersion: String,
) {
    private val meter: Meter get() = openTelemetry.getMeter("de.eudiwallet.backend")

    private val primaryKeyTimeToExpiryByLineage = ConcurrentHashMap<String, LocalDate>()

    private val pushNotificationCounter by lazy {
        meter.counterBuilder("${METRICS_PREFIX}push_notification_delivery")
            .setDescription("Push notifications handled by PNS, by delivery outcome")
            .build()
    }

    private val pushPublishFailureCounter by lazy {
        meter.counterBuilder("${METRICS_PREFIX}push_notification_publish_failure")
            .setDescription("Push notifications dropped because the publish to the topic failed")
            .build()
    }

    private val walletRevocationConsumedCounter by lazy {
        meter.counterBuilder("${METRICS_PREFIX}wallet_revocation_consumed")
            .setDescription("Wallet Instance revocation events consumed, by module and what they hit")
            .build()
    }

    private val hsmPkcs11ErrorCounter by lazy {
        meter.counterBuilder("${METRICS_PREFIX}hsm_pkcs11_errors")
            .setDescription("Failed PKCS#11 calls, including ones a retry recovered from, by slot, function and rv")
            .build()
    }

    private val hsmSessionRetryCounter by lazy {
        meter.counterBuilder("${METRICS_PREFIX}hsm_session_retries")
            .setDescription(
                "HSM operations retried on another pooled session after a session-level failure, by outcome",
            )
            .build()
    }

    init {
        meter.gaugeBuilder("${METRICS_PREFIX}primary_key_time_to_expiry")
            .ofLongs()
            .setUnit("d")
            .setDescription("Days until the primary key of a lineage expires")
            .buildWithCallback { measurement ->
                primaryKeyTimeToExpiryByLineage.forEach { (lineage, expiryDate) ->
                    measurement.record(
                        ChronoUnit.DAYS.between(LocalDate.now(), expiryDate),
                        Attributes.of(stringKey("lineage"), lineage),
                    )
                }
            }

        meter.gaugeBuilder("${METRICS_PREFIX}application_version")
            .ofLongs()
            .setDescription("Deployed backend version")
            .buildWithCallback { measurement ->
                measurement.record(1, Attributes.of(stringKey("version"), applicationVersion))
            }
    }

    fun countPushNotification(outcome: PushMetricOutcome) =
        pushNotificationCounter.add(1, Attributes.of(stringKey("outcome"), outcome.name.lowercase()))

    fun countPushPublishFailure() = pushPublishFailureCounter.add(1)

    fun countWalletRevocationConsumed(
        module: String,
        outcome: String,
    ) = walletRevocationConsumedCounter.add(
        1,
        Attributes.of(stringKey("module"), module, stringKey("outcome"), outcome),
    )

    fun countHsmPkcs11Error(
        slot: String,
        function: String,
        returnValue: String,
    ) = hsmPkcs11ErrorCounter.add(
        1,
        Attributes.of(stringKey("slot"), slot, stringKey("function"), function, stringKey("rv"), returnValue),
    )

    fun countHsmSessionRetry(
        slot: String,
        outcome: HsmRetryOutcome,
    ) = hsmSessionRetryCounter.add(
        1,
        Attributes.of(stringKey("slot"), slot, stringKey("outcome"), outcome.name.lowercase()),
    )

    fun setPrimaryKeyExpiryDate(
        lineage: String,
        expiryDate: LocalDate,
    ) {
        if (expiryDate != LocalDate.MAX) {
            primaryKeyTimeToExpiryByLineage[lineage] = expiryDate
        } else {
            primaryKeyTimeToExpiryByLineage.remove(lineage)
        }
    }
}
