package com.azhukov.agent.core.security;

import com.azhukov.agent.core.model.IdentityDeletionProtocol.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Private machine capability verification. Human JWT, native gateway keys and
 * an unconfirmed role hint cannot enter the identity deletion owner boundary.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix="agent.identity-deletion",name="enabled",havingValue="true")
public class IdentityCoordinatorVerifier {
    private final Environment environment;
    private final ObjectMapper mapper;
    private HttpClient client;
    private URI introspection;
    private String issuer;
    private Set<String> coordinators;

    @PostConstruct
    void init() {
        issuer=environment.getRequiredProperty("agent.central-auth.issuer");
        URI session=URI.create(environment.getRequiredProperty("agent.central-auth.session-uri"));
        if(session.getHost()==null || session.getUserInfo()!=null || session.getQuery()!=null || session.getFragment()!=null
            || !("http".equals(session.getScheme()) || "https".equals(session.getScheme()))) throw unavailable();
        introspection=session.resolve("/auth/tokens/introspect?include_lifetime=true");
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        coordinators=Arrays.stream(environment.getProperty("agent.identity-deletion.coordinator-subjects","").split(","))
            .map(String::trim).filter(v->!v.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }
    /** Confirms a registered service PAT and attenuates its original lifetime across all owner IO. */
    public Window verify(String authorization,Request request,long originalDeadline) {
        if(authorization==null || !authorization.startsWith("Bearer ")) throw new Failure(401,"identity_coordinator_required");
        String token=authorization.substring(7);
        if(token.length()>16384 || !(token.startsWith("sdlc_pat_") || token.startsWith("pdlc_pat_"))) throw new Failure(403,"identity_coordinator_capability_required");
        long started=System.nanoTime();
        if(originalDeadline-started<=0) throw new Failure(401,"identity_authority_expired");
        HttpRequest query=HttpRequest.newBuilder(introspection).timeout(Duration.ofSeconds(5))
            .header("Authorization","Bearer "+token).header("Accept","application/json").GET().build();
        try {
            HttpResponse<byte[]> response=client.send(query,HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(),65536));
            if(response.statusCode()==401 || response.statusCode()==403) throw new Failure(401,"identity_coordinator_rejected");
            if(response.statusCode()!=200) throw unavailable();
            JsonNode body=mapper.readTree(response.body());
            String subject=text(body,"sub");
            if(text(body,"email").isBlank())throw unavailable();
            try {if(!UUID.fromString(subject).toString().equals(subject) || UUID.fromString(subject).equals(new UUID(0,0))) throw unavailable();}
            catch(IllegalArgumentException invalid){throw unavailable();}
            if(!"service_account".equals(text(body,"subject_role")) || !coordinators.contains(subject)
                || !issuer.equals(request.identity().issuer())) throw new Failure(403,"identity_coordinator_capability_required");
            JsonNode scopes=body.get("scopes");if(scopes==null || !scopes.isArray()) throw unavailable();
            boolean allowed=false;for(JsonNode scope:scopes){if(!scope.isTextual())throw unavailable();if("java-agent:identity-lifecycle".equals(scope.asText()))allowed=true;}
            if(!allowed)throw new Failure(403,"identity_coordinator_capability_required");
            Instant observed=Instant.parse(text(body,"observed_at")),expires=Instant.parse(text(body,"expires_at"));
            if(!expires.isAfter(observed) || observed.isAfter(Instant.now().plusSeconds(1)))throw new Failure(401,"identity_authority_expired");
            long lifetime=Duration.between(observed,expires).toNanos();
            long deadline=started+Math.min(lifetime,Duration.ofSeconds(9).toNanos());
            Window window=new Window(Math.min(originalDeadline,deadline),expires);window.requireCurrent();return window;
        } catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw unavailable();}
        catch(IOException|java.time.DateTimeException|ArithmeticException malformed){throw unavailable();}
    }
    private static String text(JsonNode body,String key) {
        JsonNode value=body==null?null:body.get(key);if(value==null || !value.isTextual())throw unavailable();return value.asText();
    }
    private static Failure unavailable(){return new Failure(503,"identity_owner_confirmation_unavailable");}
}
