// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import org.lfdecentralizedtrust.splice.util.QualifiedName
import com.daml.ledger.javaapi.data.codegen.{Contract, ContractCompanion, InterfaceCompanion}
import com.daml.ledger.javaapi.data.{
  CreatedEvent as JavaCreatedEvent,
  Transaction as JavaTransaction,
}

import scala.jdk.CollectionConverters.*

object JavaDecodeUtil {
  def decodeCreated[TC](
      companion: ContractCompanion[TC, ?, ?]
  )(event: JavaCreatedEvent): Option[TC] =
    if (QualifiedName(event.getTemplateId) == QualifiedName(companion.getTemplateIdWithPackageId)) {
      Some(companion.fromCreatedEvent(event))
    } else None

  def decodeCreated[Id, View](
      companion: InterfaceCompanion[?, Id, View]
  )(event: JavaCreatedEvent): Option[Contract[Id, View]] =
    if (
      event.getInterfaceViews
        .keySet()
        .asScala
        .contains(companion.getTemplateIdWithPackageId)
    ) {
      val result = Some(companion.fromCreatedEvent(event))
      result
    } else None

  def decodeAllCreated[TC](
      companion: ContractCompanion[TC, ?, ?]
  )(transaction: JavaTransaction): Seq[TC] = {
    for {
      event <- transaction.getEvents.asScala.toList
      eventP = event.toProtoEvent
      created <- if (eventP.hasCreated) Seq(eventP.getCreated) else Seq()
      a <- decodeCreated(companion)(JavaCreatedEvent.fromProto(created)).toList
    } yield a
  }
}
