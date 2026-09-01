package dev.paymentlab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PaymentStatusCacheLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentStatusCacheLabApplication.class, args);
    }
}
