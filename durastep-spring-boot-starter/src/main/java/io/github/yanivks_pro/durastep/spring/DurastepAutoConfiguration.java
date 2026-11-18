package io.github.yanivks_pro.durastep.spring;

import io.github.yanivks_pro.durastep.api.DurastepClient;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.EngineListener;
import io.github.yanivks_pro.durastep.api.PayloadCodec;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowTracing;
import io.github.yanivks_pro.durastep.micrometer.DurastepMetrics;
import io.github.yanivks_pro.durastep.opentelemetry.DurastepTracing;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ClassUtils;

@AutoConfiguration(
    afterName = {
      "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
      "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
      "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration",
      "org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration",
      "org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration"
    })
@ConditionalOnBean(DataSource.class)
@EnableConfigurationProperties(DurastepProperties.class)
public final class DurastepAutoConfiguration {

  @Bean
  @ConditionalOnBooleanProperty(name = "durastep.worker.enabled", matchIfMissing = true)
  DurastepEngine durastepEngine(
      DataSource dataSource,
      DurastepProperties properties,
      ApplicationContext context,
      ObjectProvider<EngineListener> listeners,
      ObjectProvider<PayloadCodec> codec,
      ObjectProvider<WorkflowTracing> tracing) {
    DurastepEngine.Builder builder =
        DurastepEngine.builder()
            .dataSource(dataSource)
            .schema(properties.schema())
            .applyMigrations(properties.applyMigrations())
            .leaseDuration(properties.leaseDuration())
            .workflowConcurrency(properties.workflowConcurrency())
            .activityConcurrency(properties.activityConcurrency())
            .activityQueues(properties.activityQueues().toArray(String[]::new))
            .shutdownGrace(properties.shutdownGrace())
            .maxPayloadSize(Math.toIntExact(properties.maxPayloadSize().toBytes()))
            .historyLimits(properties.history().warnAt(), properties.history().limit())
            .listener(CompositeEngineListener.of(listeners.orderedStream().toList()));
    properties.retentionPeriod().ifPresentOrElse(builder::retention, builder::noRetention);
    codec.ifAvailable(builder::payloadCodec);
    tracing.ifAvailable(builder::tracing);
    registerWorkflows(builder, context);
    registerActivities(builder, context);
    return builder.build();
  }

  @Bean
  DurastepClient durastepClient(
      ObjectProvider<DurastepEngine> engine,
      DataSource dataSource,
      DurastepProperties properties,
      ObjectProvider<PayloadCodec> codec,
      ObjectProvider<WorkflowTracing> tracing) {
    DurastepEngine running = engine.getIfAvailable();
    if (running != null) {
      return running.client();
    }
    PayloadCodec found = codec.getIfAvailable();
    DurastepClient client =
        found != null
            ? DurastepClient.connect(dataSource, properties.schema(), found)
            : DurastepClient.connect(dataSource, properties.schema());
    WorkflowTracing traced = tracing.getIfAvailable();
    return traced != null ? client.withTracing(traced) : client;
  }

  @Bean
  DurastepLifecycle durastepLifecycle(
      ObjectProvider<DurastepEngine> engine,
      ObjectProvider<MetricsWatcher> metrics,
      DurastepClient client) {
    return new DurastepLifecycle(
        Optional.ofNullable(engine.getIfAvailable()),
        Optional.ofNullable(metrics.getIfAvailable()).map(MetricsWatcher::watch),
        client);
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.springframework.boot.health.contributor.HealthIndicator")
  static final class Health {
    @Bean
    DurastepHealthIndicator durastepHealthIndicator(DurastepClient client) {
      return new DurastepHealthIndicator(client);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass({DurastepMetrics.class, MeterRegistry.class})
  @ConditionalOnBean(MeterRegistry.class)
  static final class Metrics {
    @Bean(destroyMethod = "close")
    DurastepMetrics durastepMetrics(MeterRegistry registry) {
      return DurastepMetrics.bindTo(registry);
    }

    @Bean
    MetricsWatcher durastepMetricsWatcher(DurastepMetrics metrics) {
      return new MetricsWatcher(metrics::watch);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass({DurastepTracing.class, OpenTelemetry.class})
  @ConditionalOnBean(OpenTelemetry.class)
  static final class Tracing {
    @Bean
    @ConditionalOnMissingBean
    WorkflowTracing durastepTracing(OpenTelemetry openTelemetry) {
      return DurastepTracing.of(openTelemetry);
    }
  }

  record MetricsWatcher(Consumer<DurastepClient> watch) {}

  private static void registerWorkflows(
      DurastepEngine.Builder builder, ApplicationContext context) {
    for (String name : context.getBeanNamesForAnnotation(WorkflowDefinition.class)) {
      Class<?> type = context.getType(name);
      if (type == null || !Workflow.class.isAssignableFrom(type)) {
        throw new IllegalStateException(
            "@WorkflowDefinition bean " + name + " does not implement " + Workflow.class.getName());
      }
      registerWorkflow(builder, ClassUtils.getUserClass(type), () -> context.getBean(name));
    }
  }

  private static void registerActivities(
      DurastepEngine.Builder builder, ApplicationContext context) {
    context
        .getBeansWithAnnotation(ActivityImplementation.class)
        .forEach(
            (name, bean) -> {
              Class<?> target = AopUtils.getTargetClass(bean);
              ActivityImplementation annotation =
                  AnnotationUtils.findAnnotation(target, ActivityImplementation.class);
              List<Class<?>> interfaces = activityInterfaces(target, annotation);
              if (interfaces.isEmpty()) {
                throw new IllegalStateException(
                    "@ActivityImplementation bean " + name + " implements no activity interface");
              }
              interfaces.forEach(
                  activityInterface -> registerActivity(builder, activityInterface, bean));
            });
  }

  private static List<Class<?>> activityInterfaces(
      Class<?> target, ActivityImplementation annotation) {
    if (annotation != null && annotation.value().length > 0) {
      return List.of(annotation.value());
    }
    List<Class<?>> interfaces = new ArrayList<>();
    for (Class<?> candidate : ClassUtils.getAllInterfacesForClassAsSet(target)) {
      String name = candidate.getName();
      if (!name.startsWith("java.")
          && !name.startsWith("javax.")
          && !name.startsWith("org.springframework.")) {
        interfaces.add(candidate);
      }
    }
    return interfaces;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static void registerWorkflow(
      DurastepEngine.Builder builder, Class<?> type, Supplier<?> factory) {
    builder.workflow((Class) type, (Supplier) factory);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static void registerActivity(
      DurastepEngine.Builder builder, Class<?> activityInterface, Object implementation) {
    builder.activities((Class) activityInterface, implementation);
  }
}
