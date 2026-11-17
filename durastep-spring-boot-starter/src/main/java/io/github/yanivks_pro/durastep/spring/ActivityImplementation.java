package io.github.yanivks_pro.durastep.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.stereotype.Component;

/**
 * Marks a Spring bean that implements activity interfaces. The bean is registered with the Durastep
 * engine for every interface it implements, or only for those listed in {@link #value()}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface ActivityImplementation {

  /**
   * The activity interfaces to register the bean for; by default, every interface it implements.
   *
   * @return the interfaces
   */
  Class<?>[] value() default {};
}
