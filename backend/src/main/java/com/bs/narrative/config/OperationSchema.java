package com.bs.narrative.config;

import javax.sql.DataSource;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.annotation.PostConstruct;

/** Additive, idempotent schema; no existing tables or saves are rewritten. */
@Configuration
public class OperationSchema {
    private final DataSource source;
    public OperationSchema(DataSource source) { this.source=source; }
    @PostConstruct public void initialize() {
        new ResourceDatabasePopulator(
            new ClassPathResource("sql/operation-runtime.sql"),
            new ClassPathResource("sql/migrate-execution-checkpoint-20261015.sql"),
            new ClassPathResource("sql/migrate-operation-usage-20261015.sql"),
            new ClassPathResource("sql/migrate-story-plot-20261021.sql"),
            new ClassPathResource("sql/migrate-session-world-20261025.sql"),
            new ClassPathResource("sql/migrate-model-reply-journal-20261029.sql"),
            new ClassPathResource("sql/migrate-main-model-20261030.sql")
        ).execute(source);
        // Pending NPC work includes frozen UTF-8 model inputs. TEXT's 64 KiB byte
        // limit is smaller than a normal long-session packet, even below 64k chars.
        // Widen only legacy small columns; never narrow an existing larger type.
        var jdbc=new JdbcTemplate(source);
        widenText(jdbc,"role_knowledge","content");
        // A chapter joins multiple committed actions and voices; individual process
        // limits do not bound its total UTF-8 size. Preserve the full player prose.
        widenText(jdbc,"interaction_log","processed_dialog");
        widenText(jdbc,"world_book","event_description");
    }
    private void widenText(JdbcTemplate jdbc,String table,String column) {
        var types=jdbc.queryForList("SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?",String.class,table,column);
        if(types.stream().anyMatch(type->java.util.Set.of("tinytext","text").contains(type.toLowerCase(java.util.Locale.ROOT))))
            jdbc.execute("ALTER TABLE "+table+" MODIFY COLUMN "+column+" MEDIUMTEXT NOT NULL");
    }
}
