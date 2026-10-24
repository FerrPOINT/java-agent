package com.azhukov.agent.persistence.service;

import com.azhukov.agent.api.IdentityDeletionController;
import com.azhukov.agent.api.filter.ApiKeyAuthFilter;
import com.azhukov.agent.api.filter.IdentityDeletionBoundaryFilter;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.ports.IdentityDeletionOwner;
import com.azhukov.agent.core.security.IdentityCoordinatorVerifier;
import com.azhukov.agent.service.UserAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Real servlet/filter/controller HTTP and an isolated Auth server, without product bootstrap/JIT. */
final class IdentityOwnerHttpFixture implements AutoCloseable {
    static final String ISSUER="https://auth.example.test";
    static final String PAT="sdlc_pat_public_synthetic_owner_test";
    final UUID coordinator=UUID.randomUUID();
    final AtomicInteger status=new AtomicInteger(200);
    final AtomicReference<String> role=new AtomicReference<>("service_account");
    final AtomicReference<String> subject=new AtomicReference<>();
    final AtomicReference<List<String>> scopes=new AtomicReference<>(List.of("java-agent:identity-lifecycle"));
    final AtomicReference<Instant> expiry=new AtomicReference<>();
    final AtomicInteger authDelayMillis=new AtomicInteger();
    final AtomicInteger authCalls=new AtomicInteger();
    final AtomicInteger activeStoreCalls=new AtomicInteger();
    private final ObjectMapper mapper=new ObjectMapper();
    private final java.util.concurrent.ExecutorService callbacks=Executors.newVirtualThreadPerTaskExecutor();
    private HttpServer auth;
    private Tomcat tomcat;
    private GenericWebApplicationContext spring;
    private Path directory;
    String baseUrl;

    @Configuration(proxyBeanMethods=false)
    @EnableWebMvc
    static class WebConfiguration {}

    IdentityOwnerHttpFixture(IdentityDeletionOwner owner)throws Exception {
        try {
            subject.set(coordinator.toString());
            auth=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);auth.setExecutor(callbacks);
            auth.createContext("/auth/tokens/introspect",exchange->{
                authCalls.incrementAndGet();
                try {
                    if(authDelayMillis.get()>0)Thread.sleep(authDelayMillis.get());
                    Instant observed=Instant.now(),expires=expiry.get();
                    byte[] payload=mapper.writeValueAsBytes(Map.of("sub",subject.get(),"email","coordinator@example.test",
                        "subject_role",role.get(),"scopes",scopes.get(),"observed_at",observed.toString(),
                        "expires_at",(expires==null?observed.plusSeconds(60):expires).toString()));
                    exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.sendResponseHeaders(status.get(),payload.length);
                    try(var output=exchange.getResponseBody()){output.write(payload);}
                }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
                finally{exchange.close();}
            });auth.start();
            var environment=new MockEnvironment().withProperty("agent.central-auth.issuer",ISSUER)
                .withProperty("agent.central-auth.session-uri","http://127.0.0.1:"+auth.getAddress().getPort()+"/auth/me")
                .withProperty("agent.identity-deletion.coordinator-subjects",coordinator.toString());
            directory=Files.createTempDirectory("java-identity-http-");
            tomcat=new Tomcat();tomcat.setBaseDir(directory.toString());tomcat.setPort(0);tomcat.getConnector();
            var servletContext=tomcat.addContext("",directory.toString());
            spring=new GenericWebApplicationContext();spring.setServletContext(servletContext.getServletContext());
            new AnnotatedBeanDefinitionReader(spring).register(WebConfiguration.class);
            spring.registerBean(ObjectMapper.class,()->mapper);
            spring.registerBean(IdentityDeletionOwner.class,()->new IdentityDeletionOwner(){
                @Override public com.azhukov.agent.core.model.IdentityDeletionProtocol.Summary check(com.azhukov.agent.core.model.IdentityDeletionProtocol.Request request,com.azhukov.agent.core.model.IdentityDeletionProtocol.Window window){activeStoreCalls.incrementAndGet();try{return owner.check(request,window);}finally{activeStoreCalls.decrementAndGet();}}
                @Override public com.azhukov.agent.core.model.IdentityDeletionProtocol.Freeze freeze(com.azhukov.agent.core.model.IdentityDeletionProtocol.Request request,com.azhukov.agent.core.model.IdentityDeletionProtocol.Window window){activeStoreCalls.incrementAndGet();try{return owner.freeze(request,window);}finally{activeStoreCalls.decrementAndGet();}}
                @Override public com.azhukov.agent.core.model.IdentityDeletionProtocol.Release release(com.azhukov.agent.core.model.IdentityDeletionProtocol.Request request,com.azhukov.agent.core.model.IdentityDeletionProtocol.Window window){activeStoreCalls.incrementAndGet();try{return owner.release(request,window);}finally{activeStoreCalls.decrementAndGet();}}
            });
            spring.registerBean(IdentityCoordinatorVerifier.class,()->new IdentityCoordinatorVerifier(environment,mapper));
            spring.registerBean(IdentityDeletionController.class);
            var dispatcher=Tomcat.addServlet(servletContext,"owner",new DispatcherServlet(spring));
            dispatcher.setLoadOnStartup(1);dispatcher.setAsyncSupported(true);servletContext.addServletMappingDecoded("/","owner");
            FilterDef boundary=new FilterDef();boundary.setFilterName("boundary");boundary.setFilter(new IdentityDeletionBoundaryFilter());boundary.setAsyncSupported("true");servletContext.addFilterDef(boundary);
            FilterMap boundaryMap=new FilterMap();boundaryMap.setFilterName("boundary");boundaryMap.addURLPattern("/*");servletContext.addFilterMap(boundaryMap);
            // The actual ordinary native filter must skip only the private
            // surface; its development/global key cannot authorize the owner.
            AgentProperties properties=new AgentProperties();properties.getSecurity().setApiKey("public-synthetic-native-key");
            FilterDef ordinary=new FilterDef();ordinary.setFilterName("ordinary");ordinary.setFilter(new ApiKeyAuthFilter(properties,org.mockito.Mockito.mock(UserAccessService.class)));ordinary.setAsyncSupported("true");servletContext.addFilterDef(ordinary);
            FilterMap ordinaryMap=new FilterMap();ordinaryMap.setFilterName("ordinary");ordinaryMap.addURLPattern("/*");servletContext.addFilterMap(ordinaryMap);
            tomcat.start();baseUrl="http://127.0.0.1:"+tomcat.getConnector().getLocalPort();
        } catch(Exception failed){close();throw failed;}
    }
    @Override public void close()throws Exception {
        try{if(tomcat!=null){tomcat.stop();tomcat.destroy();}}
        finally {
            if(spring!=null && spring.isActive())spring.close();
            if(auth!=null)auth.stop(0);callbacks.shutdownNow();
            if(directory!=null)try(var paths=Files.walk(directory)){for(Path item:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(item);}
        }
    }
}
