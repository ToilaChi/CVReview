//package org.example.authservice.config;
//
//import io.github.cdimascio.dotenv.Dotenv;
//import org.springframework.context.ApplicationContextInitializer;
//import org.springframework.context.ConfigurableApplicationContext;
//import org.springframework.core.env.ConfigurableEnvironment;
//import org.springframework.core.env.MapPropertySource;
//
//import java.util.HashMap;
//import java.util.Map;
//
//public class EnvironmentInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
//
//    @Override
//    public void initialize(ConfigurableApplicationContext applicationContext) {
//        try {
//            // Load .env file
//            Dotenv dotenv = Dotenv.configure()
//                    .directory("./")
//                    .ignoreIfMalformed()
//                    .ignoreIfMissing()
//                    .load();
//
//            // Convert to Map for Spring PropertySource
//            Map<String, Object> envMap = new HashMap<>();
//            dotenv.entries().forEach(entry -> {
//                envMap.put(entry.getKey(), entry.getValue());
//            });
//
//            // Add to Spring Environment với priority cao
//            ConfigurableEnvironment environment = applicationContext.getEnvironment();
//            environment.getPropertySources().addFirst(
//                    new MapPropertySource("dotenv", envMap)
//            );
//
//            System.out.println("✅ Đã load .env file thành công vào Spring Environment!");
//            System.out.println("🔍 DB_URL = " + environment.getProperty("DB_URL"));
//
//        } catch (Exception e) {
//            System.out.println("⚠️ Không thể load file .env: " + e.getMessage());
//            e.printStackTrace();
//        }
//    }
//}
