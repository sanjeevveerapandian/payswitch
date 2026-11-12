package com.payswitch.switchservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SwitchServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SwitchServiceApplication.class, args);
    }
}
