package ro.alacrity.kina;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class KinaApplication {

    public static void main(String[] args) {
        SpringApplication.run(KinaApplication.class, args);
    }
}
