// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.util

import com.daml.network.util.QualifiedName
import com.daml.ledger.javaapi.data.codegen.{Contract, ContractCompanion, InterfaceCompanion}
import com.daml.ledger.javaapi.data.{
  CreatedEvent as JavaCreatedEvent,
  Transaction as JavaTransaction,
  TransactionTree as JavaTransactionTree,
}

import scala.jdk.CollectionConverters.*

object JavaDecodeUtil {
  def decodeCreated[TC](
      companion: ContractCompanion[TC, ?, ?]
  )(event: JavaCreatedEvent): Option[TC] =
    if (QualifiedName(event.getTemplateId) == QualifiedName(companion.TEMPLATE_ID)) {
      Some(companion.fromCreatedEvent(event))
    } else None

  def decodeCreated[Id, View](
      companion: InterfaceCompanion[?, Id, View]
  )(event: JavaCreatedEvent): Option[Contract[Id, View]] =
    if (event.getInterfaceViews.containsKey(companion.TEMPLATE_ID)) {
      Some(companion.fromCreatedEvent(event))
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

  def treeToCreated(transaction: JavaTransactionTree): Seq[JavaCreatedEvent] =
    for {
      event <- transaction.getEventsById.values.asScala.toSeq
      created <- event match {
        case created: JavaCreatedEvent => Seq(created)
        case _ => Seq.empty
      }
    } yield created

  def decodeAllCreatedTree[TC](
      companion: ContractCompanion[TC, ?, ?]
  )(transaction: JavaTransactionTree): Seq[TC] =
    for {
      created <- treeToCreated(transaction)
      a <- decodeCreated(companion)(created).toList
    } yield a
}
