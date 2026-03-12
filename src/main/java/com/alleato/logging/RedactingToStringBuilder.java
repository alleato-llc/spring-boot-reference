package com.alleato.logging;

import java.lang.reflect.Modifier;

/**
 * Reflection-based toString builder that replaces {@link Redacted} fields with {@code ***}.
 *
 * <p>Usage: override {@code toString()} in domain objects that may contain sensitive data:
 *
 * <pre>{@code
 * @Override
 * public String toString() {
 *     return RedactingToStringBuilder.toString(this);
 * }
 * }</pre>
 */
public final class RedactingToStringBuilder {

  private RedactingToStringBuilder() {}

  public static String toString(Object obj) {
    if (obj == null) {
      return "null";
    }
    var clazz = obj.getClass();
    var fields = clazz.getDeclaredFields();
    var sb = new StringBuilder(clazz.getSimpleName()).append('{');
    boolean first = true;
    for (var field : fields) {
      if (Modifier.isStatic(field.getModifiers())) {
        continue;
      }
      if (!first) {
        sb.append(", ");
      }
      first = false;
      field.setAccessible(true);
      sb.append(field.getName()).append('=');
      try {
        sb.append(field.isAnnotationPresent(Redacted.class) ? "***" : field.get(obj));
      } catch (IllegalAccessException e) {
        sb.append("<inaccessible>");
      }
    }
    return sb.append('}').toString();
  }
}
