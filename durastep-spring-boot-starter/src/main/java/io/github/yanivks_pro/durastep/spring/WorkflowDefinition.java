package io.github.yanivks_pro.durastep.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * Marks a {@link io.github.yanivks_pro.durastep.api.Workflow} for the Durastep engine. The class
 * becomes a prototype-scoped Spring bean: every run of the workflow gets a fresh instance, with its
 * dependencies injected.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public @interface WorkflowDefinition {}
