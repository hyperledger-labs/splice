// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.ledger.api.client

import com.daml.ledger.javaapi.data.codegen.{
  Contract,
  ContractCompanion,
  ContractId,
  DamlRecord,
  InterfaceCompanion,
}
import com.daml.ledger.javaapi.data.{
  CreatedEvent => JavaCreatedEvent,
  ExercisedEvent,
  Template,
  Transaction => JavaTransaction,
  TransactionTree,
}

import scala.jdk.CollectionConverters.*

object JavaDecodeUtil {
  def decodeCreated[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      companion: ContractCompanion[TC, TCid, T]
  )(event: JavaCreatedEvent): Option[TC] =
    if (event.getTemplateId == companion.TEMPLATE_ID) {
      Some(companion.fromCreatedEvent(event))
    } else None

  def decodeCreated[I, Id <: ContractId[I], View <: DamlRecord[View]](
      companion: InterfaceCompanion[I, Id, View]
  )(event: JavaCreatedEvent): Option[Contract[Id, View]] =
    if (event.getInterfaceViews.containsKey(companion.TEMPLATE_ID)) {
      Some(companion.fromCreatedEvent(event))
    } else None

  def decodeAllCreated[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      companion: ContractCompanion[TC, TCid, T]
  )(transaction: JavaTransaction): Seq[TC] = {
    for {
      event <- transaction.getEvents.asScala.toList
      eventP = event.toProtoEvent
      created <- if (eventP.hasCreated) Seq(eventP.getCreated) else Seq()
      a <- decodeCreated(companion)(JavaCreatedEvent.fromProto(created)).toList
    } yield a
  }

  def decodeArchivedExercise[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      companion: ContractCompanion[TC, TCid, T]
  )(event: ExercisedEvent): Option[TCid] =
    Option.when(event.getTemplateId == companion.TEMPLATE_ID && event.isConsuming)(
      companion.toContractId(new ContractId[T]((event.getContractId)))
    )

  def treeToCreated(transaction: TransactionTree): Seq[JavaCreatedEvent] =
    for {
      event <- transaction.getEventsById.values.asScala.toSeq
      created <- event match {
        case created: JavaCreatedEvent => Seq(created)
        case _ => Seq.empty
      }
    } yield created

  def decodeAllCreatedTree[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      companion: ContractCompanion[TC, TCid, T]
  )(transaction: TransactionTree): Seq[TC] =
    for {
      created <- treeToCreated(transaction)
      a <- decodeCreated(companion)(created).toList
    } yield a
}
