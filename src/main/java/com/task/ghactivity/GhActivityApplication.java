package com.task.ghactivity;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GhActivityApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(GhActivityApplication.class);
        app.setBannerMode(Banner.Mode.OFF); // CLI vibe
        app.run(args);
    }
}
