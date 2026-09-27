package com.guide.web;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI 智能导游数字人平台启动类。
 * scanBasePackages 覆盖 com.guide：service（业务）/common（JWT 校验器等）均在此包下。
 */
@SpringBootApplication(scanBasePackages = "com.guide")
@MapperScan("com.guide.dao.mapper")
@EnableScheduling
public class GuideApplication {

    public static void main(String[] args) {
        SpringApplication.run(GuideApplication.class, args);
    }
}
