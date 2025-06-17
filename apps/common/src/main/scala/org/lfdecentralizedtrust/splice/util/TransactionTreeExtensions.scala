// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import org.lfdecentralizedtrust.splice.codegen.java.da.internal.template.Archive
import com.daml.ledger.javaapi.data as j
import j.codegen as jcg
import scala.jdk.CollectionConverters.*

private[splice] object TransactionTreeExtensions {
  implicit final class `TransactionTree AN extensions`(private val self: j.Transaction)
      extends AnyVal {
    def findCreation[TCid <: jcg.ContractId[?], T <: jcg.DamlRecord[?]](
        tpl: Contract.Companion.Template[TCid, T],
        contractId: jcg.ContractId[T],
    ): Option[Contract[TCid, T]] = self.getEventsById.asScala.collectFirst {
      case (_, c: j.CreatedEvent) if c.getContractId == contractId.contractId =>
        Contract.fromCreatedEvent(tpl)(c)
    }.flatten

    def findArchive[Marker](
        descendantOf: j.Event,
        contractId: jcg.ContractId[Marker],
        archive: jcg.Choice[Marker, Archive, j.Unit],
    ): Option[j.ExercisedEvent] =
      self.collectFirstDescendant(descendantOf) {
        case e: j.ExercisedEvent
            if e.isConsuming && e.getContractId == contractId.contractId && e.getChoice == archive.name =>
          e
      }

    def firstDescendantExercise[Marker, Res](
        event: j.Event,
        tpl: jcg.ContractCompanion[?, ?, Marker],
        choice: jcg.Choice[Marker, ?, Res],
    ): Option[(j.ExercisedEvent, Res)] =
      self.collectFirstDescendant(event)(Function unlift {
        case e: j.ExercisedEvent =>
          extractExerciseResult(e, tpl, choice).map((e, _))
        case _ => None
      })

    def collectFirstDescendant[Z](of: j.Event)(p: j.Event PartialFunction Z): Option[Z] =
      self.preorderDescendants(of) collectFirst p

    def preorderDescendants(of: j.Event): Iterator[j.Event] = {
      of match {
        case _: j.CreatedEvent => Iterator.empty
        case ex: j.ExercisedEvent =>
          val evs = self.getEventsById.asScala
          self.getChildNodeIds(ex).asScala.iterator flatMap { childId =>
            evs
              .get(childId)
              .fold(Iterator.empty[j.Event])(child =>
                Seq(child).iterator ++ self.preorderDescendants(child)
              )
          }
        case _ =>
          throw new UnsupportedOperationException(
            s"TransactionTree contains node that is not a Create or Exercise: $of"
          )
      }
    }
  }

  // None if the template/choice names don't match, but
  @throws[IllegalArgumentException] // if name matches but result type is structurally incompatible
  private def extractExerciseResult[Marker, Res](
      event: j.ExercisedEvent,
      tpl: jcg.ContractCompanion[?, ?, Marker],
      choice: jcg.Choice[Marker, ?, Res],
  ): Option[Res] =
    Option.when(
      QualifiedName(event.getTemplateId) == QualifiedName(
        tpl.getTemplateIdWithPackageId
      ) && event.getChoice == choice.name
    )(choice.returnTypeDecoder.decode(event.getExerciseResult))
}
