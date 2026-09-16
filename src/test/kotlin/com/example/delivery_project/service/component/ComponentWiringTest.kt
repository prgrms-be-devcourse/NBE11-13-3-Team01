package com.example.delivery_project.service.component

import com.example.delivery_project.config.DeliveryClaimProperties
import com.example.delivery_project.config.DriverRecommendationProperties
import com.example.delivery_project.config.PriorityWindowProperties
import com.example.delivery_project.config.RestControllerConfig
import com.example.delivery_project.domain.repository.DeliveryPlanPriorityDriverRepository
import com.example.delivery_project.domain.repository.DriverLocationRepository
import com.example.delivery_project.domain.repository.UserRepository
import com.example.delivery_project.domain.repository.WeatherCacheRepository
import com.example.delivery_project.domain.repository.WeatherRepository
import com.example.delivery_project.service.component.recommendation.DriverCandidateLoader
import com.example.delivery_project.service.component.recommendation.DriverRecommender
import com.example.delivery_project.service.component.recommendation.PriorityWindowAssigner
import com.example.delivery_project.service.component.route.DijkstraRouteOptimizer
import com.example.delivery_project.service.component.route.RouteOptimizer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.aop.framework.ProxyFactory
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.test.util.ReflectionTestUtils

class ComponentWiringTest {

    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(ComponentConfiguration::class.java)
        .withPropertyValues(
            "kakao.local.key=test-kakao-key",
            "weather.api.key=test-weather-key",
        )

    @Test
    fun `컴포넌트를 스캔하고 생성자와 설정값을 주입한다`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(GeocodingClient::class.java)
            assertThat(context).hasSingleBean(DrivingDirectionsClient::class.java)
            assertThat(context).hasSingleBean(RouteOptimizer::class.java)
            assertThat(context).hasSingleBean(LocationMapper::class.java)
            assertThat(context).hasSingleBean(WeatherProvider::class.java)
            assertThat(context).hasSingleBean(WeatherUpdater::class.java)
            assertThat(context).hasSingleBean(RiskFactorCalculator::class.java)
            assertThat(context).hasSingleBean(DemoRiskScenarioPolicy::class.java)
            assertThat(context).hasSingleBean(DriverRecommender::class.java)
            assertThat(context).hasSingleBean(DriverCandidateLoader::class.java)
            assertThat(context).hasSingleBean(PriorityWindowAssigner::class.java)

            assertThat(context.getBean(RouteOptimizer::class.java))
                .isInstanceOf(DijkstraRouteOptimizer::class.java)
            assertThat(ReflectionTestUtils.getField(context.getBean(KakaoGeocodingClient::class.java), "restApiKey"))
                .isEqualTo("test-kakao-key")
            assertThat(ReflectionTestUtils.getField(context.getBean(KakaoDirectionsClient::class.java), "restApiKey"))
                .isEqualTo("test-kakao-key")
            assertThat(ReflectionTestUtils.getField(context.getBean(WeatherProvider::class.java), "apiKey"))
                .isEqualTo("test-weather-key")

            // Kotlin 클래스/메서드가 final이 되어 Spring 클래스 프록시를 막지 않는지 검증한다.
            val proxyFactory = ProxyFactory(context.getBean(WeatherUpdater::class.java))
            proxyFactory.isProxyTargetClass = true
            val proxy = proxyFactory.proxy as WeatherUpdater
            assertThat(proxy.resolveLatestBaseDateTime().baseDate).isNotBlank()
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = [WeatherProvider::class])
    @Import(RestControllerConfig::class)
    class ComponentConfiguration {
        @Bean
        fun weatherRepository(): WeatherRepository = mock(WeatherRepository::class.java)

        @Bean
        fun weatherCacheRepository(): WeatherCacheRepository = mock(WeatherCacheRepository::class.java)

        // service.component.recommendation 하위 패키지도 함께 스캔되므로
        // 추천·우선권 컴포넌트가 요구하는 설정과 리포지토리 빈을 함께 등록한다.
        @Bean
        fun driverRecommendationProperties(): DriverRecommendationProperties = DriverRecommendationProperties()

        @Bean
        fun priorityWindowProperties(): PriorityWindowProperties = PriorityWindowProperties()

        @Bean
        fun deliveryClaimProperties(): DeliveryClaimProperties = DeliveryClaimProperties()

        @Bean
        fun userRepository(): UserRepository = mock(UserRepository::class.java)

        @Bean
        fun driverLocationRepository(): DriverLocationRepository = mock(DriverLocationRepository::class.java)

        @Bean
        fun deliveryPlanPriorityDriverRepository(): DeliveryPlanPriorityDriverRepository =
            mock(DeliveryPlanPriorityDriverRepository::class.java)
    }
}
