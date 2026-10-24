package com.azhukov.agent.api;

import com.azhukov.agent.core.model.IdentityDeletionProtocol.*;
import com.azhukov.agent.core.ports.IdentityDeletionOwner;
import com.azhukov.agent.core.security.IdentityCoordinatorVerifier;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.WebAsyncTask;

/** Private durable identity-owner RPCs; they never enter native or human UserContext. */
@RestController
@RequestMapping("/api/internal/v1/user-deletions")
@RequiredArgsConstructor
@Slf4j
@Tag(name="Identity lifecycle",description="Private registered coordinator only")
public class IdentityDeletionController {
    private final ObjectProvider<IdentityDeletionOwner> owners;
    private final ObjectProvider<IdentityCoordinatorVerifier> verifiers;
    private final ObjectMapper mapper;

    @PostMapping("/{operation}/check")
    @Operation(summary="Count all retained identity references")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(schema=@Schema(implementation=Request.class)))
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=Summary.class)))
    public WebAsyncTask<ResponseEntity<Object>> check(@PathVariable UUID operation,HttpServletRequest servlet) { return command(operation,"check",servlet); }
    @PostMapping("/{operation}/freeze")
    @Operation(summary="Commit a durable identity reference fence")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(schema=@Schema(implementation=Request.class)))
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=Freeze.class)))
    public WebAsyncTask<ResponseEntity<Object>> freeze(@PathVariable UUID operation,HttpServletRequest servlet) { return command(operation,"freeze",servlet); }
    @PostMapping("/{operation}/release")
    @Operation(summary="Release or persist cancellation before a late freeze")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(schema=@Schema(implementation=Request.class)))
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=Release.class)))
    public WebAsyncTask<ResponseEntity<Object>> release(@PathVariable UUID operation,HttpServletRequest servlet) { return command(operation,"release",servlet); }

    private WebAsyncTask<ResponseEntity<Object>> command(UUID operation,String action,HttpServletRequest servlet) {
        long deadline=System.nanoTime()+Duration.ofSeconds(9).toNanos();
        Object started=servlet.getAttribute(com.azhukov.agent.api.filter.IdentityDeletionBoundaryFilter.DEADLINE_ATTRIBUTE);
        if(started instanceof Long original)deadline=Math.min(deadline,original);
        final long originalDeadline=deadline;
        WebAsyncTask<ResponseEntity<Object>> task=new WebAsyncTask<>(9000L,()->{
            try {
                byte[] bytes=servlet.getInputStream().readNBytes(16385);
                if(bytes.length>16384)throw new Failure(413,"identity_owner_body_too_large");
                Request request=mapper.readerFor(Request.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readValue(bytes);
                if(request==null || !request.valid() || !operation.equals(request.operationId()))throw new Failure(400,"invalid_identity_deletion_request");
                IdentityDeletionOwner owner=owners.getIfAvailable();IdentityCoordinatorVerifier verifier=verifiers.getIfAvailable();
                if(owner==null || verifier==null)throw new Failure(503,"identity_owner_not_configured");
                var credentials=servlet.getHeaders("Authorization");
                if(credentials==null || !credentials.hasMoreElements())throw new Failure(401,"identity_coordinator_required");
                String authorization=credentials.nextElement();
                if(credentials.hasMoreElements())throw new Failure(400,"identity_coordinator_header_ambiguous");
                Window window=verifier.verify(authorization,request,originalDeadline);
                Object result=switch(action){case "check"->owner.check(request,window);case "freeze"->owner.freeze(request,window);case "release"->owner.release(request,window);default->throw new Failure(404,"identity_owner_route_unknown");};
                window.requireCurrent();return ResponseEntity.ok().header("Cache-Control","no-store").body(result);
            }catch(Failure failure){return error(failure.status(),failure.code());}
            catch(IOException|IllegalArgumentException invalid){return error(400,"invalid_identity_deletion_request");}
        });
        task.onTimeout(()->error(503,"identity_owner_timeout"));
        task.onError(()->error(503,"identity_owner_unavailable"));
        return task;
    }
    private static ResponseEntity<Object> error(int status,String code){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",Map.of("code",code)));}
}
