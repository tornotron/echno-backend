package org.tornotron.echno_backend.modules.vision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.springframework.core.io.ClassPathResource;
import org.tornotron.echno_backend.modules.vision.web.VisionModelAdminController;
import org.tornotron.echno_backend.modules.vision.web.VisionModelController;

/**
 * Off under the default configuration means the endpoints are not there, in every entitlement mode:
 * the advisory mode would let a refused feature through a gate, so the gate alone would not do. And
 * nothing else of the module exists either: the scan below finds every bean in the module's packages
 * and, off, only the manifest may be left. A new bean that forgets {@link VisionModuleEnabled} fails
 * here.
 */
@WebMvcTest({VisionModelController.class, VisionModelAdminController.class})
@Import(VisionModuleDarkWebTest.TestSecurityConfig.class)
class VisionModuleDarkWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    @Test
    void theEndpointsAreNotThereUnderTheDefaultConfiguration() throws Exception {
        mockMvc.perform(get("/api/v1/vision/models").with(platformAdmin())).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/vision/admin/models").with(platformAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyTheManifestExistsUnderTheDefaultConfiguration() {
        new ApplicationContextRunner()
                .withInitializer(context -> {
                    try {
                        new YamlPropertySourceLoader()
                                .load("application.yml", new ClassPathResource("application.yml"))
                                .forEach(context.getEnvironment().getPropertySources()::addLast);
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .withUserConfiguration(ScanTheModule.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    List<String> found = new ArrayList<>();
                    for (String name : context.getBeanDefinitionNames()) {
                        Class<?> type = context.getType(name);
                        if (type != null && type.getName().startsWith(VisionModule.class.getPackageName() + ".")
                                && !type.equals(ScanTheModule.class)) {
                            found.add(type.getSimpleName());
                        }
                    }
                    assertThat(found).containsExactly("VisionModule");
                });
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor platformAdmin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_platform-admin"));
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = VisionModule.class,
            excludeFilters = @ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.REGEX,
                    pattern = ".*Test.*"))
    static class ScanTheModule {
    }

    @TestConfiguration
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain testFilterChain(HttpSecurity http) throws Exception {
            http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
            return http.build();
        }
    }
}
