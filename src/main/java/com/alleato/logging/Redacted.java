package com.alleato.logging;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field as containing sensitive data. Fields annotated with {@code @Redacted}
 * are replaced with {@code ***} in {@link RedactingToStringBuilder} output,
 * preventing accidental logging of sensitive values.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Redacted {}
