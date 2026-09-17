package com.example.delivery_project.batch

import org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer
import org.springframework.boot.sql.init.DatabaseInitializationMode
import org.springframework.boot.sql.init.DatabaseInitializationSettings
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import javax.sql.DataSource

@Configuration
@Profile("batch")
@ConditionalOnProperty(name = ["batch.metadata.initialize-schema"], havingValue = "true", matchIfMissing = true)
class BatchMetadataSchemaConfig {

    @Bean
    fun batchMetadataInitializer(dataSource: DataSource): DataSourceScriptDatabaseInitializer {
        val settings = DatabaseInitializationSettings().apply {
            schemaLocations = listOf("classpath:org/springframework/batch/core/schema-mysql.sql")
            mode = DatabaseInitializationMode.ALWAYS
            isContinueOnError = true // 이미 존재하면 넘어간다
        }
        return DataSourceScriptDatabaseInitializer(dataSource, settings)
    }
}
