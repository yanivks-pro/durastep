package io.github.yanivks_pro.durastep.activity;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ActivityRegistry {

  record RegisteredActivity(Object implementation, Method method) {}

  private final Map<String, RegisteredActivity> activities = new HashMap<>();
  private final Map<String, Class<?>> interfacesByName = new HashMap<>();

  public <T> ActivityRegistry register(Class<T> activityInterface, T implementation) {
    Objects.requireNonNull(activityInterface, "activityInterface");
    Objects.requireNonNull(implementation, "implementation");
    if (!activityInterface.isInterface()) {
      throw new IllegalArgumentException(
          "activities must be declared on an interface, but "
              + activityInterface.getName()
              + " is a class");
    }
    if (!activityInterface.isInstance(implementation)) {
      throw new IllegalArgumentException(
          implementation.getClass().getName()
              + " does not implement "
              + activityInterface.getName());
    }
    String interfaceName = activityInterface.getSimpleName();
    Class<?> existing = interfacesByName.putIfAbsent(interfaceName, activityInterface);
    if (existing != null) {
      throw new IllegalArgumentException(
          "activity interface name "
              + interfaceName
              + " is used by both "
              + existing.getName()
              + " and "
              + activityInterface.getName());
    }
    Map<String, RegisteredActivity> found = new HashMap<>();
    for (Method method : activityInterface.getMethods()) {
      if (Modifier.isStatic(method.getModifiers()) || method.isDefault()) {
        continue;
      }
      String name = interfaceName + "." + method.getName();
      method.setAccessible(true);
      if (found.putIfAbsent(name, new RegisteredActivity(implementation, method)) != null) {
        interfacesByName.remove(interfaceName);
        throw new IllegalArgumentException(
            "activity " + name + " is overloaded; give each activity method a unique name");
      }
    }
    activities.putAll(found);
    return this;
  }

  Optional<RegisteredActivity> find(String activity) {
    return Optional.ofNullable(activities.get(activity));
  }
}
