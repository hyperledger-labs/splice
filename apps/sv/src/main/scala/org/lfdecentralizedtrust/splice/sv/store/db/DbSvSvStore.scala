// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.store.db

import org.lfdecentralizedtrust.splice.codegen.java.splice.validatoronboarding.{
  UsedSecret,
  ValidatorOnboarding,
}
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.QueryResult
import org.lfdecentralizedtrust.splice.store.db.StoreDescriptor
import org.lfdecentralizedtrust.splice.store.db.{AcsQueries, AcsTables, DbAppStore}
import org.lfdecentralizedtrust.splice.store.{Limit, MultiDomainAcsStore, StoreErrors}
import org.lfdecentralizedtrust.splice.sv.store.{SvStore, SvSvStore}
import org.lfdecentralizedtrust.splice.util.{Contract, TemplateJsonDecoder}
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import org.lfdecentralizedtrust.splice.store.db.AcsQueries.AcsStoreId
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.{ExecutionContext, Future}

class DbSvSvStore(
    override val key: SvStore.Key,
    storage: DbStorage,
    override protected val outerLoggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
    val domainMigrationId: Long,
    participantId: ParticipantId,
    ingestionConfig: IngestionConfig,
    acsStoreDescriptorUserVersion: Option[Long] = None,
    override val defaultLimit: Limit,
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
      // WARNING: Reinitializing the acs store is a very expensive operation, as it currently fetches the full
      // unfiltered ACS from the participant, irrespective of the filter defined by `acsContractFilter`.
      // This may lead to the entire app being unavailable or not working properly until the full ACS has been ingested.
      // Do not modify any part of the store descriptor unless you are sure that the resulting downtime is acceptable.
      // If you do modify it, make sure to very clearly document in the release notes that there will be planned downtime,
      // and notify the person coordinating the deployment.
      acsStoreDescriptor = StoreDescriptor(
        version = 2,
        name = "DbSvSvStore",
        party = key.svParty,
        participant = participantId,
        key = Map(
          "svParty" -> key.svParty.toProtoPrimitive,
          "dsoParty" -> key.dsoParty.toProtoPrimitive,
        ),
        userVersion = acsStoreDescriptorUserVersion,
      ),
      migrationId = domainMigrationId,
      ingestionConfig,
    )
    with SvSvStore
    with AcsTables
    with AcsQueries
    with StoreErrors
    with NamedLogging {

  import multiDomainAcsStore.waitUntilAcsIngested
  import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture

  private def acsStoreId: AcsStoreId = multiDomainAcsStore.acsStoreId
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
            ValidatorOnboarding.COMPANION,
            where = sql"""
              onboarding_secret = ${lengthLimited(secret)}
                or (
                  onboarding_secret like '{%'
                  and (onboarding_secret::jsonb ->> 'secret') = ${lengthLimited(secret)}
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

  import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.install.WalletAppInstall

  override def lookupWalletAppInstallByEndUserWithOffset(
      endUserParty: PartyId
  )(implicit tc: TraceContext): Future[MultiDomainAcsStore.QueryResult[
    Option[Contract[WalletAppInstall.ContractId, WalletAppInstall]]
  ]] = waitUntilAcsIngested {
    (for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            DbSvSvStore.tableName,
            acsStoreId,
            domainMigrationId,
            WalletAppInstall.COMPANION,
            where = sql"""
              create_arguments->>'endUserParty' = ${lengthLimited(endUserParty.toProtoPrimitive)}
            """,
          ).headOption,
          "lookupWalletAppInstallByEndUserWithOffset",
        )
    } yield QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(contractFromRow(WalletAppInstall.COMPANION)(_)),
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
              UsedSecret.COMPANION,
              where = sql"""
                  onboarding_secret = ${lengthLimited(secret)}
                  or (
                    onboarding_secret like '{%'
                    and (onboarding_secret::jsonb ->> 'secret') = ${lengthLimited(secret)}
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
