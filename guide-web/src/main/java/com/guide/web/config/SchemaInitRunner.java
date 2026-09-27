package com.guide.web.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 建表 Runner：启动时执行 schema.sql（CREATE TABLE IF NOT EXISTS，幂等）。
 * JDBC 连接串带 createDatabaseIfNotExist=true，零手工建库。
 * MySQL 8 不支持 CREATE INDEX IF NOT EXISTS（索引在 CREATE TABLE 内联，重复建表为空操作），
 * 单条语句失败（如重复索引）记录警告后继续，不阻断启动。
 */
@Component
public class SchemaInitRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitRunner.class);

    private final JdbcTemplate jdbcTemplate;

    public SchemaInitRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) throws IOException {
        String sql = new String(
                new ClassPathResource("sql/schema.sql").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        for (String statement : splitStatements(sql)) {
            try {
                jdbcTemplate.execute(statement);
            } catch (Exception e) {
                Throwable cause = e instanceof SQLException ? e : e.getCause();
                String message = cause != null ? cause.getMessage() : e.getMessage();
                if (message != null && (message.contains("Duplicate key name")
                        || message.contains("already exists"))) {
                    log.debug("跳过已存在的 schema 对象: {}", firstLine(statement));
                } else {
                    log.warn("schema 初始化语句执行失败: {}", message);
                }
            }
        }
        log.info("数据库 schema 初始化完成");
    }

    /** 按「; + 换行」切分语句（建表体内含注释与换行，不能用简单 split(";")） */
    private static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : sql.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--")) {
                continue;
            }
            current.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                String stmt = current.toString().trim();
                if (stmt.length() > 1) {
                    statements.add(stmt.substring(0, stmt.length() - 1));
                }
                current.setLength(0);
            }
        }
        return statements;
    }

    private static String firstLine(String statement) {
        int idx = statement.indexOf('\n');
        return (idx > 0 ? statement.substring(0, idx) : statement).trim();
    }
}
