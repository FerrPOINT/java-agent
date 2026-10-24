package com.azhukov.agent.config;

import com.azhukov.agent.core.security.NativeIdentityOrigin;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.stereotype.Component;

/**
 * Binds actual native authentication provenance to an existing JDBC transaction.
 * This is not an identity inference or a new authentication mechanism: only the
 * native authentication filter supplies the request-owned origin. Central
 * requests have no native origin. Transaction-local PostgreSQL settings are
 * consumed by reference triggers and disappear on commit or rollback.
 *
 * Binding is independent of SQL text and therefore covers all transactional JPA
 * writers, including newly named native memory scopes. Existing central aliases
 * are immutable and cannot be changed by this provenance. Raw operator SQL has
 * no authenticated request origin and retains the normal unknown-reference guard.
 */
@Component
@ConditionalOnProperty(prefix="agent.identity-deletion",name="enabled",havingValue="true")
public class NativeIdentityDataSource implements BeanPostProcessor {
    @Override
    public Object postProcessAfterInitialization(Object bean,String name) {
        return bean instanceof DataSource source && !(source instanceof ProvenanceDataSource)
            ? new ProvenanceDataSource(source) : bean;
    }

    /** Wraps the real JDBC transaction without changing pool or transaction ownership. */
    public static final class ProvenanceDataSource extends DelegatingDataSource {
        public ProvenanceDataSource(DataSource source) { super(source); }
        @Override public Connection getConnection() throws SQLException {return connection(super.getConnection());}
        @Override public Connection getConnection(String user,String password) throws SQLException {return connection(super.getConnection(user,password));}
    }
    private static Connection connection(Connection delegate) {
        final class Binding {
            NativeIdentityOrigin.Origin bound;
            boolean initialized;
            Connection proxy;
            void bind() throws SQLException {
                if(delegate.getAutoCommit())return;
                NativeIdentityOrigin.Origin current=NativeIdentityOrigin.current();
                if(initialized && java.util.Objects.equals(current,bound))return;
                try(PreparedStatement q=delegate.prepareStatement("SELECT set_config('pdlc.java.native_kind',?,true),set_config('pdlc.java.native_principal',?,true),set_config('pdlc.java.native_namespace',?,true)")) {
                    q.setString(1,current==null?"":current.kind().name());
                    q.setString(2,current==null?"":current.principal());
                    q.setString(3,current!=null && current.mayNameNativeScope()?"true":"false");
                    q.execute();
                }
                bound=current;
                initialized=true;
            }
            Object statement(Statement statement) {
                Class<?> type=statement instanceof CallableStatement?CallableStatement.class:
                    statement instanceof PreparedStatement?PreparedStatement.class:Statement.class;
                return Proxy.newProxyInstance(NativeIdentityDataSource.class.getClassLoader(),new Class<?>[]{type},(proxy,method,args)->{
                    if(method.getName().startsWith("execute") || method.getName().equals("addBatch"))bind();
                    if(method.getName().equals("getConnection"))return this.proxy;
                    return invoke(statement,method,args);
                });
            }
        }
        Binding binding=new Binding();
        binding.proxy=(Connection)Proxy.newProxyInstance(NativeIdentityDataSource.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            Object result=invoke(delegate,method,args);
            if(result instanceof Statement statement)return binding.statement(statement);
            if(method.getName().equals("commit") || method.getName().equals("rollback") || method.getName().equals("close")
                || (method.getName().equals("setAutoCommit") && Boolean.TRUE.equals(args[0]))) {
                binding.bound=null;binding.initialized=false;
            }
            return result;
        });
        return binding.proxy;
    }
    private static Object invoke(Object target,Method method,Object[] args)throws Throwable {
        try{return method.invoke(target,args);}catch(InvocationTargetException wrapped){throw wrapped.getCause();}
    }
}
