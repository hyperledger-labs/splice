// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

/** A class representing a Daml-LF value of a specific type.
  * See https://docs.daml.com/app-dev/daml-lf-translation.html#data-types for the translation
  * from Daml to Daml-LF.
  * @param value The underlying value.
  * @param toValue Conversion to protobuf value. Java codegen does not provide a generic
  *   mechanism for that so we explicitly carry the function around.
  */
final class Value[T](
    val value: T
) {
  // Overridden to avoid equality on toValue. toValue is uniquely defined
  // for codegen values so this is safe.
  override def equals(obj: Any) = obj match {
    case that: Value[?] => this.value == that.value
    case _ => false
  }

  override def hashCode(): Int = this.value.hashCode()
}
