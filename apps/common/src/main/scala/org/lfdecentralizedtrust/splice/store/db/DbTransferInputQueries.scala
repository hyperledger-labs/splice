// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import com.daml.ledger.javaapi.data.codegen.ContractId
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.IssuingMiningRound
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ContractCompanion
import org.lfdecentralizedtrust.splice.store.db.AcsQueries.{AcsStoreId, SelectFromAcsTableResult}
import org.lfdecentralizedtrust.splice.store.{Limit, LimitHelpers, TransferInputStore}
import org.lfdecentralizedtrust.splice.util.{Contract, TemplateJsonDecoder}
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.*
import com.digitalasset.canton.tracing.TraceContext
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import slick.jdbc.canton.SQLActionBuilder

import scala.concurrent.{ExecutionContext, Future}

/** TransferInput related DB queries
  *
  * The store's ACS table must have the index on columns:
  * (store_id, migration_id, package_name, template_id_qualified_name,
  * reward_coupon_round) WHERE (reward_coupon_round IS NOT NULL)
  */
trait DbTransferInputQueries extends AcsQueries with AcsTables with LimitHelpers {
  self: TransferInputStore =>

  protected def acsTableName: String
  protected def acsStoreId: AcsStoreId
  protected def domainMigrationId: Long
  protected def dbStorage: DbStorage

  protected implicit def ec: ExecutionContext
  protected implicit def closeContext: CloseContext
  protected implicit def templateJsonDecoder: TemplateJsonDecoder

  // List reward coupons sorted by round and calculated value.
  protected def listSortedRewardCoupons[C, TCid <: ContractId[?], T](
      companion: C,
      issuingRoundsMap: Map[Round, IssuingMiningRound],
      roundToIssuance: IssuingMiningRound => Option[BigDecimal],
      limit: Limit,
      ccValue: SQLActionBuilder = sql"rti.issuance",
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      tc: TraceContext,
  ): Future[Seq[(Contract[TCid, T], BigDecimal)]] = {
    val packageQualifiedName = companionClass.packageQualifiedName(companion)
    issuingRoundsMap
      .flatMap { case (round, contract) =>
        roundToIssuance(contract).map(round.number.longValue() -> _)
      }
      .map { case (round, issuance) =>
        sql"($round, $issuance)"
      }
      .reduceOption { (acc, next) =>
        (acc ++ sql"," ++ next).toActionBuilder
      } match {
      case None => Future.successful(Seq.empty) // no rounds = no results
      case Some(roundToIssuanceValues) =>
        for {
          result <- dbStorage.query(
            (sql"""
              with round_to_issuance(round, issuance) as (values """ ++ roundToIssuanceValues ++ sql""")
              select
                #${SelectFromAcsTableResult.sqlColumnsCommaSeparated()},""" ++ ccValue ++ sql"""
              from #$acsTableName acs join round_to_issuance rti on acs.reward_coupon_round = rti.round
              where acs.store_id = $acsStoreId
                and migration_id = $domainMigrationId
                and acs.package_name = ${packageQualifiedName.packageName}
                and acs.template_id_qualified_name = ${packageQualifiedName.qualifiedName}
              order by (acs.reward_coupon_round, -""" ++ ccValue ++ sql""")
              limit ${sqlLimit(limit)}""").toActionBuilder
              .as[(SelectFromAcsTableResult, BigDecimal)],
            s"listSorted:$packageQualifiedName",
          )
        } yield applyLimit(s"listSorted:$packageQualifiedName", limit, result).map {
          case (row, issuance) =>
            val contract = contractFromRow(companion)(row)
            contract -> issuance
        }
    }
  }
}
