package de.eudiwallet.backend.shared.telemetry

import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

const val X_TRACE_ID = "X-Trace-Id"

@Component
class TelemetryResponseHeaderFilter(
    private val telemetryService: TelemetryService,
) : WebFilter {
    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
    ): Mono<Void> {
        exchange.response.headers.add(X_TRACE_ID, telemetryService.getCurrentTraceId())
        return chain.filter(exchange)
    }
}
