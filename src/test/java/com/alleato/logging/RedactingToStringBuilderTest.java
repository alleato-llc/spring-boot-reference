package com.alleato.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RedactingToStringBuilderTest {

  @Test
  void redactsAnnotatedFields() {
    var obj = new WithRedactedFields("visible", "secret");
    assertThat(RedactingToStringBuilder.toString(obj))
        .isEqualTo("WithRedactedFields{visible=visible, secret=***}");
  }

  @Test
  void includesAllFieldsWhenNoneRedacted() {
    var obj = new WithNoRedactedFields("a", "b");
    assertThat(RedactingToStringBuilder.toString(obj))
        .isEqualTo("WithNoRedactedFields{first=a, second=b}");
  }

  @Test
  void handlesNullFieldValues() {
    var obj = new WithRedactedFields(null, "secret");
    assertThat(RedactingToStringBuilder.toString(obj))
        .isEqualTo("WithRedactedFields{visible=null, secret=***}");
  }

  @Test
  void handlesNullInput() {
    assertThat(RedactingToStringBuilder.toString(null)).isEqualTo("null");
  }

  @Test
  void skipsStaticFields() {
    var obj = new WithStaticField("value");
    assertThat(RedactingToStringBuilder.toString(obj)).isEqualTo("WithStaticField{instance=value}");
  }

  static class WithRedactedFields {
    private final String visible;
    @Redacted private final String secret;

    WithRedactedFields(String visible, String secret) {
      this.visible = visible;
      this.secret = secret;
    }
  }

  static class WithNoRedactedFields {
    private final String first;
    private final String second;

    WithNoRedactedFields(String first, String second) {
      this.first = first;
      this.second = second;
    }
  }

  static class WithStaticField {
    private static final String CONSTANT = "ignored";
    private final String instance;

    WithStaticField(String instance) {
      this.instance = instance;
    }
  }
}
