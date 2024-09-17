// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.store.db

import com.daml.network.codegen.java.splice.validatoronboarding.{UsedSecret, ValidatorOnboarding}
import com.daml.network.environment.RetryProvider
import com.daml.network.migration.DomainMigrationInfo
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.store.db.DbMultiDomainAcsStore.StoreDescriptor
import com.daml.network.store.db.{AcsQueries, AcsTables, DbAppStore}
import com.daml.network.store.{MultiDomainAcsStore, StoreErrors}
import com.daml.network.sv.store.{SvStore, SvSvStore}
import com.daml.network.util.{Contract, QualifiedName, TemplateJsonDecoder}
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.ParticipantId
import com.digitalasset.canton.tracing.TraceContext
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.{ExecutionContext, Future}

class DbSvSvStore(
    override val key: SvStore.Key,
    storage: DbStorage,
    override protected val outerLoggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
    domainMigrationInfo: DomainMigrationInfo,
    participantId: ParticipantId,
)(implicit
    override protected val ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbAppStore(
      storage,
      DbSvSvStore.tableName,
      // Any change in the store descriptor will lead to previously deployed applications
      // forgetting all persisted data once they upgrade to the new version.
      storeDescriptor = StoreDescriptor(
        version = 1,
        name = "DbSvSvStore",
        party = key.svParty,
        participant = participantId,
        key = Map(
          "svParty" -> key.svParty.toProtoPrimitive,
          "dsoParty" -> key.dsoParty.toProtoPrimitive,
        ),
      ),
      domainMigrationInfo = domainMigrationInfo,
      participantId = participantId,
      enableissue12777Workaround = false,
    )
    with SvSvStore
    with AcsTables
    with AcsQueries
    with StoreErrors
    with NamedLogging {

  import multiDomainAcsStore.waitUntilAcsIngested

  def storeId: Int = multiDomainAcsStore.storeId
  def domainMigrationId: Long = domainMigrationInfo.currentMigrationId
  override def lookupValidatorOnboardingBySecretWithOffset(
      secret: String
  )(implicit tc: TraceContext): Future[MultiDomainAcsStore.QueryResult[
    Option[Contract[ValidatorOnboarding.ContractId, ValidatorOnboarding]]
  ]] = waitUntilAcsIngested {
    (for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            DbSvSvStore.tableName,
            storeId,
            domainMigrationId,
            sql"""
            template_id_qualified_name = ${QualifiedName(ValidatorOnboarding.TEMPLATE_ID)}
              and onboarding_secret = ${lengthLimited(secret)}
          """,
          ).headOption,
          "lookupValidatorOnboardingBySecretWithOffset",
        )
    } yield QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(contractFromRow(ValidatorOnboarding.COMPANION)(_)),
    )).getOrRaise(offsetExpectedError())
  }

  override def lookupUsedSecretWithOffset(secret: String)(implicit
      tc: TraceContext
  ): Future[MultiDomainAcsStore.QueryResult[Option[Contract[UsedSecret.ContractId, UsedSecret]]]] =
    waitUntilAcsIngested {
      (for {
        resultWithOffset <- storage
          .querySingle(
            selectFromAcsTableWithOffset(
              DbSvSvStore.tableName,
              storeId,
              domainMigrationId,
              sql"""
                  template_id_qualified_name = ${QualifiedName(UsedSecret.TEMPLATE_ID)}
                    and onboarding_secret = ${lengthLimited(secret)}
                """,
            ).headOption,
            "lookupUsedSecretWithOffset",
          )
      } yield QueryResult(
        resultWithOffset.offset,
        resultWithOffset.row.map(contractFromRow(UsedSecret.COMPANION)(_)),
      )).getOrRaise(offsetExpectedError())
    }
}

object DbSvSvStore {

  val tableName = "sv_acs_store"

}
