package com.azhukov.agent.config;

import com.azhukov.agent.api.ProfilesDashboardController;
import com.azhukov.agent.api.SessionCrudController;
import com.azhukov.agent.core.security.ProfileDatabaseScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class ProfileRequestScope implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ProfileInterceptor());
    }

    private static final class ProfileInterceptor implements AsyncHandlerInterceptor {

        private static final String BINDING = ProfileInterceptor.class.getName() + ".scope";

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            if (!(handler instanceof HandlerMethod method)) {
                return true;
            }
            Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
            Object pathProfile = variables instanceof Map<?, ?> names ? names.get("profile") : null;
            var binding = pathProfile instanceof String profile
                ? directScope(profile) : selectHttpScope(request, method);
            if (binding != null) {
                request.setAttribute(BINDING, binding);
            }
            return true;
        }

        private ProfileDatabaseScope.Binding selectHttpScope(HttpServletRequest request, HandlerMethod method) {
            if ("GET".equals(request.getMethod())
                && ProfilesDashboardController.class.isAssignableFrom(method.getBeanType())) {
                return switch (method.getMethod().getName()) {
                    case "profileSessions" -> dashboardScope(request.getParameter("profile"));
                    case "sidebarSessions" -> dashboardScope(request.getParameter("recents_profile"));
                    case "profileProjectsTree" -> ProfileDatabaseScope.enterHistoryOverview();
                    default -> ProfileDatabaseScope.enter("default");
                };
            }
            if (SessionCrudController.class.isAssignableFrom(method.getBeanType())
                && "pruneSessions".equals(method.getMethod().getName())) {
                // The controller scopes the operation using its validated body.
                return null;
            }
            for (var parameter : method.getMethodParameters()) {
                var query = parameter.getParameterAnnotation(RequestParam.class);
                if (query != null && (query.name().equals("profile")
                    || (query.name().isEmpty() && parameter.getParameter().getName().equals("profile")))) {
                    return directScope(request.getParameter("profile"));
                }
            }
            return ProfileDatabaseScope.enter("default");
        }

        private ProfileDatabaseScope.Binding directScope(String value) {
            String profile = value == null || value.isBlank() ? "default" : value.trim().toLowerCase(Locale.ROOT);
            return ProfileDatabaseScope.enter(ProfileDatabaseScope.validProfile(profile) ? profile : "default");
        }

        private ProfileDatabaseScope.Binding dashboardScope(String parameter) {
            if (parameter == null || parameter.isBlank() || "all".equalsIgnoreCase(parameter.trim())) {
                return ProfileDatabaseScope.enterHistoryOverview();
            }
            return directScope(parameter);
        }

        @Override
        public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception error) {
            release(request);
        }

        @Override
        public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response, Object handler) {
            release(request);
        }

        private void release(HttpServletRequest request) {
            if (request.getAttribute(BINDING) instanceof ProfileDatabaseScope.Binding binding) {
                request.removeAttribute(BINDING);
                binding.close();
            }
        }
    }
}
