package com.example.delivery_project.batch

import org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer
import org.springframework.boot.sql.init.DatabaseInitializationMode
import org.springframework.boot.sql.init.DatabaseInitializationSettings
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import javax.sql.DataSource

/**
 * 배치 메타데이터 테이블(BATCH_*)만 생성한다.
 *
 * spring.sql.init 을 쓰지 않는 이유가 있다. 그 설정은 지정한 스키마 파일뿐 아니라
 * 기본 위치의 data.sql 도 함께 실행한다. 이 프로젝트의 data.sql 은 배송 테이블을
 * DROP 후 재생성하므로, 배치 서버를 띄울 때마다 운영 데이터가 날아간다.
 *
 * 그래서 배치 스키마 전용 초기화 빈을 따로 둔다.
 * DDL 은 Spring Batch 라이브러리가 제공하는 공식 스크립트를 그대로 쓴다.
 */
@Configuration
@Profile("batch")
@ConditionalOnProperty(name = ["batch.metadata.initialize-schema"], havingValue = "true", matchIfMissing = true)
class BatchMetadataSchemaConfig {

    @Bean
    fun batchMetadataInitializer(dataSource: DataSource): DataSourceScriptDatabaseInitializer {
        val settings = DatabaseInitializationSettings().apply {
            schemaLocations = listOf("classpath:org/springframework/batch/core/schema-mysql.sql")
            // 기본값은 EMBEDDED 라 MySQL 같은 외부 DB 에서는 건너뛴다. 반드시 명시해야 한다.
            mode = DatabaseInitializationMode.ALWAYS
            isContinueOnError = true // 이미 존재하면 넘어간다
        }
        return DataSourceScriptDatabaseInitializer(dataSource, settings)
    }
}
