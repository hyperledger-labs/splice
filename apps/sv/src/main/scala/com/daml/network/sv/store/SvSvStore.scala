// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.store

import com.digitalasset.daml.lf.data.Time.Timestamp
import com.daml.network.automation.MultiDomainExpiredContractTrigger.ListExpiredContracts
import com.daml.network.automation.TransferFollowTrigger.Task as FollowTask
import com.daml.network.codegen.java.splice.validatoronboarding.ValidatorOnboarding
import com.daml.network.codegen.java.splice.{svonboarding as so, validatoronboarding as vo}
import com.daml.network.environment.RetryProvider
import com.daml.network.migration.DomainMigrationInfo
import com.daml.network.store.MultiDomainAcsStore.{ConstrainedTemplate, QueryResult}
import com.daml.network.store.{AppStore, Limit, MultiDomainAcsStore, PageLimit}
import com.daml.network.sv.store.db.DbSvSvStore
import com.daml.network.sv.store.db.SvTables.SvAcsStoreRowData
import com.daml.network.util.{AssignedContract, Contract, TemplateJsonDecoder}
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, Storage}
import com.digitalasset.canton.topology.{DomainId, ParticipantId}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

/* Store used by the SV app for filtering contracts visible to the SV party. */
trait SvSvStore extends AppStore {
  import SvSvStore.templatesMovedByMyAutomation

  protected val outerLoggerFactory: NamedLoggerFactory

  override protected lazy val loggerFactory: NamedLoggerFactory =
    outerLoggerFactory.append("store", "svParty")

  override lazy val acsContractFilter = SvSvStore.contractFilter(key)

  def lookupValidatorOnboardingBySecretWithOffset(
      secret: String
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[vo.ValidatorOnboarding.ContractId, vo.ValidatorOnboarding]]]
  ]

  def lookupValidatorOnboardingBySecret(
      secret: String
  )(implicit tc: TraceContext): Future[
    Option[Contract[vo.ValidatorOnboarding.ContractId, vo.ValidatorOnboarding]]
  ] =
    lookupValidatorOnboardingBySecretWithOffset(secret).map(_.value)

  def lookupUsedSecretWithOffset(
      secret: String
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[vo.UsedSecret.ContractId, vo.UsedSecret]]]
  ]

  def lookupUsedSecret(
      secret: String
  )(implicit tc: TraceContext): Future[
    Option[Contract[vo.UsedSecret.ContractId, vo.UsedSecret]]
  ] =
    lookupUsedSecretWithOffset(secret).map(_.value)

  def listValidatorOnboardings(limit: Limit = Limit.DefaultLimit)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[?, vo.ValidatorOnboarding]]] =
    multiDomainAcsStore
      .listContracts(vo.ValidatorOnboarding.COMPANION, limit)
      .map(_ map (_.contract))

  def listExpiredValidatorOnboardings()
      : ListExpiredContracts[ValidatorOnboarding.ContractId, ValidatorOnboarding] =
    multiDomainAcsStore.listExpiredFromPayloadExpiry(ValidatorOnboarding.COMPANION)

  def lookupSvOnboardingConfirmed()(implicit tc: TraceContext): Future[
    Option[Contract[so.SvOnboardingConfirmed.ContractId, so.SvOnboardingConfirmed]]
  ] =
    multiDomainAcsStore
      .listContracts(
        so.SvOnboardingConfirmed.COMPANION,
        PageLimit.tryCreate(1),
      )
      .map(_.headOption map (_.contract))

  private[this] def listLaggingDsoRulesFollowers(
      targetDomain: DomainId
  )(implicit tc: TraceContext): Future[Seq[AssignedContract[?, ?]]] =
    multiDomainAcsStore.listAssignedContractsNotOnDomainN(
      targetDomain,
      templatesMovedByMyAutomation,
    )

  final def listDsoRulesTransferFollowers[SrCid, Sr](
      dsoRules: AssignedContract[SrCid, Sr]
  )(implicit tc: TraceContext): Future[Seq[FollowTask[SrCid, Sr, ?, ?]]] =
    listLaggingDsoRulesFollowers(dsoRules.domain)
      .map(_ map (FollowTask(dsoRules, _)))

  def key: SvStore.Key
}

object SvSvStore {
  def apply(
      key: SvStore.Key,
      storage: Storage,
      loggerFactory: NamedLoggerFactory,
      retryProvider: RetryProvider,
      domainMigrationInfo: DomainMigrationInfo,
      participantId: ParticipantId,
  )(implicit
      ec: ExecutionContext,
      templateJsonDecoder: TemplateJsonDecoder,
      closeContext: CloseContext,
  ): SvSvStore =
    storage match {
      case db: DbStorage =>
        new DbSvSvStore(key, db, loggerFactory, retryProvider, domainMigrationInfo, participantId)
      case storageType => throw new RuntimeException(s"Unsupported storage type $storageType")
    }

  private[network] val templatesMovedByMyAutomation: Seq[ConstrainedTemplate] =
    Seq[ConstrainedTemplate](
      vo.UsedSecret.COMPANION,
      vo.ValidatorOnboarding.COMPANION,
    )

  /** Contract filter of an sv acs store for a specific acs party. */
  def contractFilter(key: SvStore.Key): MultiDomainAcsStore.ContractFilter[SvAcsStoreRowData] = {
    import MultiDomainAcsStore.mkFilter
    val sv = key.svParty.toProtoPrimitive

    MultiDomainAcsStore.SimpleContractFilter(
      key.svParty,
      Map(
        mkFilter(vo.ValidatorOnboarding.COMPANION)(co => co.payload.sv == sv) { contract =>
          SvAcsStoreRowData(
            contract,
            contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.expiresAt)),
            onboardingSecret = Some(contract.payload.candidateSecret),
          )
        },
        mkFilter(vo.UsedSecret.COMPANION)(co => co.payload.sv == sv) { contract =>
          SvAcsStoreRowData(
            contract,
            onboardingSecret = Some(contract.payload.secret),
          )
        },
        mkFilter(so.SvOnboardingConfirmed.COMPANION)(co => co.payload.svParty == sv) { contract =>
          SvAcsStoreRowData(
            contract,
            contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.expiresAt)),
            svCandidateName = Some(contract.payload.svName),
          )
        },
      ),
    )
  }
}
