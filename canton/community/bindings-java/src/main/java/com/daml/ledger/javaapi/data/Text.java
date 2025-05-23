// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.javaapi.data;

import com.daml.ledger.api.v2.ValueOuterClass;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.NonNull;

public final class Text extends Value {

  private final String value;

  public Text(@NonNull String value) {
    this.value = value;
  }

  @NonNull
  public String getValue() {
    return value;
  }

  @Override
  public ValueOuterClass.Value toProto() {
    return ValueOuterClass.Value.newBuilder().setText(this.value).build();
  }

  @Override
  public String toString() {
    return "Text{" + "value='" + value + '\'' + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Text text = (Text) o;
    return Objects.equals(value, text.value);
  }

  @Override
  public int hashCode() {

    return Objects.hash(value);
  }
}
