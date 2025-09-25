// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.store.db

import org.lfdecentralizedtrust.splice.codegen.java.splice.validatoronboarding.{
  UsedSecret,
  ValidatorOnboarding,
}
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.QueryResult
import org.lfdecentralizedtrust.splice.store.db.DbMultiDomainAcsStore.StoreDescriptor
import org.lfdecentralizedtrust.splice.store.db.{AcsQueries, AcsTables, DbAppStore}
import org.lfdecentralizedtrust.splice.store.{MultiDomainAcsStore, StoreErrors}
import org.lfdecentralizedtrust.splice.sv.store.{SvStore, SvSvStore}
import org.lfdecentralizedtrust.splice.util.{Contract, QualifiedName, TemplateJsonDecoder}
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.ParticipantId
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.store.UpdateHistory.BackfillingRequirement
import org.lfdecentralizedtrust.splice.store.db.AcsQueries.AcsStoreId
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
      interfaceViewsTableNameOpt = None,
      // Any change in the store descriptor will lead to previously deployed applications
      // forgetting all persisted data once they upgrade to the new version.
      acsStoreDescriptor = StoreDescriptor(
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
      enableImportUpdateBackfill = false,
      BackfillingRequirement.BackfillingNotRequired,
    )
    with SvSvStore
    with AcsTables
    with AcsQueries
    with StoreErrors
    with NamedLogging {

  import multiDomainAcsStore.waitUntilAcsIngested
  import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture

  private def acsStoreId: AcsStoreId = multiDomainAcsStore.acsStoreId
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
            acsStoreId,
            domainMigrationId,
            sql"""
            template_id_qualified_name = ${QualifiedName(
                ValidatorOnboarding.TEMPLATE_ID_WITH_PACKAGE_ID
              )}
              and (
                  onboarding_secret = ${lengthLimited(secret)}
                  or (
                    onboarding_secret like '{%'
                    and (onboarding_secret::jsonb ->> 'secret') = ${lengthLimited(secret)}
                  )
                )
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
              acsStoreId,
              domainMigrationId,
              sql"""
                template_id_qualified_name = ${QualifiedName(
                  UsedSecret.TEMPLATE_ID_WITH_PACKAGE_ID
                )}
                and (
                  onboarding_secret = ${lengthLimited(secret)}
                  or (
                    onboarding_secret like '{%'
                    and (onboarding_secret::jsonb ->> 'secret') = ${lengthLimited(secret)}
                  )
                )
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
