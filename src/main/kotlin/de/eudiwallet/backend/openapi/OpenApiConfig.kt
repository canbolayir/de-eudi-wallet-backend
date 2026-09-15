package de.eudiwallet.backend.openapi

import de.eudiwallet.backend.mdvm.MdvmErrorResponse
import de.eudiwallet.backend.pns.PnsErrorResponse
import de.eudiwallet.backend.rwsca.RwscaErrorResponse
import de.eudiwallet.backend.wpb.WpbErrorResponse
import io.swagger.v3.core.converter.ModelConverters
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springdoc.core.models.GroupedOpenApi
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

private const val REMOTE_WSCA_LABEL = "RWSCA - Remote Wallet Secure Cryptographic Application"
private const val MDVM_LABEL = "MDVM - Mobile Device Vulnerability Management"
private const val WPB_LABEL = "WPB - Wallet Provider Backend"
private const val PNS_LABEL = "PNS - Push Notifications Service"

@Configuration
class OpenApiConfig(
    @Value($$"${info.application.version}") private val appVersion: String,
    @Value($$"${info.application.name}") private val appName: String,
    private val modelConverters: ModelConverters,
) {
    @Bean
    fun customOpenAPI(): OpenAPI = OpenAPI().info(Info().title(appName).version(appVersion))

    @Bean
    @ConditionalOnProperty(prefix = "rwsca", name = ["enabled"], havingValue = "true")
    fun rwscaAPI(): GroupedOpenApi =
        GroupedOpenApi.builder()
            .group("rwsca")
            .displayName(REMOTE_WSCA_LABEL)
            .packagesToScan("de.eudiwallet.backend.rwsca")
            .addOpenApiCustomizer { openApi ->
                openApi.info(Info().title(REMOTE_WSCA_LABEL).version(appVersion))

                val schemas = modelConverters.read(RwscaErrorResponse::class.java)
                if (openApi.components == null) openApi.components = Components()
                schemas.forEach { (name, schema) -> openApi.components.addSchemas(name, schema) }
            }
            .build()

    @Bean
    @ConditionalOnProperty(prefix = "mdvm", name = ["enabled"], havingValue = "true")
    fun mdvmAPI(): GroupedOpenApi =
        GroupedOpenApi.builder()
            .group("mdvm")
            .displayName(MDVM_LABEL)
            .packagesToScan("de.eudiwallet.backend.mdvm")
            .addOpenApiCustomizer { openApi ->
                openApi.info(Info().title(MDVM_LABEL).version(appVersion))

                val schemas = modelConverters.read(MdvmErrorResponse::class.java)
                if (openApi.components == null) openApi.components = Components()
                schemas.forEach { (name, schema) -> openApi.components.addSchemas(name, schema) }
            }
            .build()

    @Bean
    @ConditionalOnProperty(prefix = "pns", name = ["enabled"], havingValue = "true")
    fun pnsAPI(): GroupedOpenApi =
        GroupedOpenApi.builder()
            .group("pns")
            .displayName(PNS_LABEL)
            .packagesToScan("de.eudiwallet.backend.pns")
            .addOpenApiCustomizer { openApi ->
                openApi.info(Info().title(PNS_LABEL).version(appVersion))

                val schemas = modelConverters.read(PnsErrorResponse::class.java)
                if (openApi.components == null) openApi.components = Components()
                schemas.forEach { (name, schema) -> openApi.components.addSchemas(name, schema) }
            }
            .build()

    @Bean
    @ConditionalOnProperty(prefix = "wpb", name = ["enabled"], havingValue = "true")
    fun wpbAPI(): GroupedOpenApi =
        GroupedOpenApi.builder()
            .group("wpb")
            .displayName(WPB_LABEL)
            .packagesToScan("de.eudiwallet.backend.wpb")
            .addOpenApiCustomizer { openApi ->
                openApi.info(Info().title(WPB_LABEL).version(appVersion))

                val schemas = modelConverters.read(WpbErrorResponse::class.java)
                if (openApi.components == null) openApi.components = Components()
                schemas.forEach { (name, schema) -> openApi.components.addSchemas(name, schema) }
            }
            .build()
}
