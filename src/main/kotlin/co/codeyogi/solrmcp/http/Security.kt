package co.codeyogi.solrmcp.http

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import org.http4k.core.Filter
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.filter.AnyOf
import org.http4k.filter.CorsPolicy
import org.http4k.filter.OriginPolicy
import org.http4k.filter.ServerFilters
import java.net.URI

/**
 * HTTP-mode security as plain http4k filters (no Spring Security, no @PreAuthorize).
 */
object Security {

    /** CORS scoped to the methods/headers used by the MCP Streamable HTTP + SSE transport. */
    fun cors(allowedOrigins: List<String>): Filter = ServerFilters.Cors(
        CorsPolicy(
            originPolicy = OriginPolicy.AnyOf(allowedOrigins),
            headers = listOf("Authorization", "Content-Type", "Mcp-Session-Id", "MCP-Protocol-Version", "Last-Event-ID"),
            methods = listOf(Method.GET, Method.POST, Method.DELETE, Method.OPTIONS),
            credentials = true,
        ),
    )

    /**
     * Validates a bearer JWT against the issuer's JWKS (OAuth2 Resource Server).
     * If no issuer is configured the server runs unsecured (no-op filter), matching
     * the reference behavior where security only activates when an issuer is set.
     */
    fun jwtAuth(issuerUri: String?): Filter {
        if (issuerUri.isNullOrBlank()) return Filter { next -> next } // unsecured: pass through

        val issuer = if (issuerUri.endsWith("/")) issuerUri else "$issuerUri/"
        val jwksUrl = URI("${issuer}.well-known/jwks.json").toURL()
        val jwkSource = JWKSourceBuilder.create<SecurityContext>(jwksUrl).build()
        val processor = DefaultJWTProcessor<SecurityContext>().apply {
            jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, jwkSource)
        }

        return Filter { next ->
            { request ->
                val token = request.header("Authorization")
                    ?.takeIf { it.startsWith("Bearer ") }
                    ?.removePrefix("Bearer ")
                    ?.trim()
                if (token != null && isValid(processor, token, issuer)) {
                    next(request)
                } else {
                    Response(Status.UNAUTHORIZED).body("Missing or invalid bearer token")
                }
            }
        }
    }

    private fun isValid(processor: DefaultJWTProcessor<SecurityContext>, token: String, issuer: String): Boolean =
        try {
            val claims = processor.process(token, null)
            claims.issuer == issuer || claims.issuer == issuer.trimEnd('/')
        } catch (_: Exception) {
            false
        }
}
