// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.daml.lf.data.Ref.PackageVersion
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.environment.{PackageIdResolver, PackageVettingLookupService}
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ContractState
import org.lfdecentralizedtrust.splice.store.{MultiDomainAcsStore, PageLimit}
import org.lfdecentralizedtrust.splice.util.{AssignedContract, Contract}

import scala.concurrent.{ExecutionContext, Future}

/** A trigger for processing expired contracts whose expiry archives exactly them.
  *
  * Use [[ScheduledTaskTrigger]] for more complex expiry choices.
  */
abstract class BatchedMultiDomainExpiredContractTrigger[
    C,
    TCid <: ContractId[?],
    T,
](
    store: MultiDomainAcsStore,
    batchSize: Int,
    listExpiredContracts: MultiDomainExpiredContractTrigger.ListExpiredContracts[TCid, T],
    companion: C,
    vettingLookupService: PackageVettingLookupService,
    pkg: PackageIdResolver.Package,
    stakeholders: T => Seq[PartyId],
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
    companionClass: MultiDomainAcsStore.ContractCompanion[C, TCid, T],
) extends ScheduledTaskTrigger[BatchedMultiDomainExpiredContractTrigger.Batch[TCid, T]] {

  import BatchedMultiDomainExpiredContractTrigger.Batch

  override final protected def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[Batch[TCid, T]]] =
    listExpiredContracts(now, PageLimit.tryCreate(limit * batchSize))(tc)
      .flatMap(splitBatch(_))

  private def splitBatch(
      expiredContracts: Seq[AssignedContract[TCid, T]]
  )(implicit tc: TraceContext): Future[Seq[Batch[TCid, T]]] =
    vettingLookupService
      .splitBatch(
        PackageIdResolver.Package.SpliceAmulet,
        expiredContracts,
        batchSize,
      )(c => stakeholders(c.payload))
      .map {
        _.toSeq.flatMap {
          case (Some(version), contractBatches) => contractBatches.map(Batch(pkg, version, _))
          case (None, contracts) =>
            logger.warn(
              show"No vetted $pkg version for ${contracts.flatten.map { _.contractId.contractId }}"
            )
            Seq.empty
        }
      }

  override final protected def isStaleTask(
      task: ScheduledTaskTrigger.ReadyTask[Batch[TCid, T]]
  )(implicit tc: TraceContext): Future[Boolean] = {
    Future
      .traverse(task.work.expiredContracts)(co =>
        store
          .lookupContractById(companion)(co.contractId: TCid)
          .map(
            _.forall(_.state != ContractState.Assigned(co.domain))
          )
      )
      .map(_.exists(stale => stale))
  }
}

object BatchedMultiDomainExpiredContractTrigger {
  final case class Batch[TCid, T](
      pkg: PackageIdResolver.Package,
      vettedVersion: PackageVersion,
      expiredContracts: Seq[
        AssignedContract[TCid, T]
      ],
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("pkg", _.pkg),
        param("vettedVersion", _.vettedVersion),
        param("numExpiredContracts", _.expiredContracts.size),
        param("expiredContractCids", _.expiredContracts.map(_.contractId.contractId.unquoted)),
      )
  }

  type Template[TCid <: ContractId[?], T] =
    BatchedMultiDomainExpiredContractTrigger[Contract.Companion.Template[TCid, T], TCid, T]
  type ListExpiredContracts[TCid <: ContractId[?], T] =
    // we use PageLimit because this is always used in the context of a trigger, where the query will be re-run repeatedly
    (CantonTimestamp, PageLimit) => TraceContext => Future[Seq[AssignedContract[TCid, T]]]
}
