// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.store

import org.lfdecentralizedtrust.splice.automation.MultiDomainExpiredContractTrigger.ListExpiredContracts
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.{
  install as walletCodegen,
  topupstate as topUpCodegen,
  transferpreapproval as preapprovalCodegen,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.{
  amulet as amuletCodegen,
  amuletrules as amuletrulesCodegen,
  externalpartyamuletrules as externalpartyamuletrulesCodegen,
  validatorlicense as validatorLicenseCodegen,
}
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.{QueryResult, TemplateFilter}
import org.lfdecentralizedtrust.splice.store.{AppStore, Limit, MultiDomainAcsStore}
import org.lfdecentralizedtrust.splice.util.*
import org.lfdecentralizedtrust.splice.validator.store.db.DbValidatorStore
import org.lfdecentralizedtrust.splice.validator.store.db.ValidatorTables.ValidatorAcsStoreRowData
import org.lfdecentralizedtrust.splice.wallet.store.{ValidatorLicenseStore, WalletStore}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{ParticipantId, PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.daml.lf.data.Time.Timestamp
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import org.lfdecentralizedtrust.splice.store.db.AcsInterfaceViewRowData

import scala.concurrent.{ExecutionContext, Future}

trait ValidatorStore extends WalletStore with ValidatorLicenseStore with AppStore {

  /** The key identifying the parties considered by this store. */
  val key: ValidatorStore.Key

  def domainMigrationId: Long

  def lookupWalletInstallByNameWithOffset(
      endUserName: String
  )(implicit tc: TraceContext): Future[QueryResult[
    Option[
      ContractWithState[walletCodegen.WalletAppInstall.ContractId, walletCodegen.WalletAppInstall]
    ]
  ]]

  def lookupValidatorRightByPartyWithOffset(
      party: PartyId
  )(implicit tc: TraceContext): Future[
    QueryResult[
      Option[
        ContractWithState[amuletCodegen.ValidatorRight.ContractId, amuletCodegen.ValidatorRight]
      ]
    ]
  ]

  def lookupValidatorTopUpStateWithOffset(
      synchronizerId: SynchronizerId
  )(implicit traceContext: TraceContext): Future[
    QueryResult[
      Option[
        Contract[topUpCodegen.ValidatorTopUpState.ContractId, topUpCodegen.ValidatorTopUpState]
      ]
    ]
  ]

  def listUsers(
      limit: Limit = Limit.DefaultLimit
  )(implicit tc: TraceContext): Future[Seq[String]] = {
    for {
      installs <- multiDomainAcsStore.listContracts(
        walletCodegen.WalletAppInstall.COMPANION,
        limit,
      )
    } yield installs.map(i => i.payload.endUserName)
  }

  def listExternalPartySetupProposals()(implicit
      tc: TraceContext
  ): Future[
    Seq[ContractWithState[
      amuletrulesCodegen.ExternalPartySetupProposal.ContractId,
      amuletrulesCodegen.ExternalPartySetupProposal,
    ]]
  ] = {
    for {
      proposals <- multiDomainAcsStore.listContracts(
        amuletrulesCodegen.ExternalPartySetupProposal.COMPANION
      )
    } yield proposals
  }

  def listTransferPreapprovals()(implicit
      tc: TraceContext
  ): Future[
    Seq[
      ContractWithState[
        amuletrulesCodegen.TransferPreapproval.ContractId,
        amuletrulesCodegen.TransferPreapproval,
      ]
    ]
  ] = {
    for {
      preapprovals <- multiDomainAcsStore.listContracts(
        amuletrulesCodegen.TransferPreapproval.COMPANION
      )
    } yield preapprovals
  }

  def listExpiringTransferPreapprovals(
      renewalDuration: NonNegativeFiniteDuration
  ): ListExpiredContracts[
    amuletrulesCodegen.TransferPreapproval.ContractId,
    amuletrulesCodegen.TransferPreapproval,
  ]

  def lookupExternalPartySetupProposalByUserPartyWithOffset(
      partyId: PartyId
  )(implicit tc: TraceContext): Future[
    QueryResult[
      Option[ContractWithState[
        amuletrulesCodegen.ExternalPartySetupProposal.ContractId,
        amuletrulesCodegen.ExternalPartySetupProposal,
      ]]
    ]
  ]

  def lookupTransferPreapprovalByReceiverPartyWithOffset(
      partyId: PartyId
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[
      ContractWithState[
        amuletrulesCodegen.TransferPreapproval.ContractId,
        amuletrulesCodegen.TransferPreapproval,
      ]
    ]]
  ]
}

object ValidatorStore {

  def apply(
      key: Key,
      storage: DbStorage,
      loggerFactory: NamedLoggerFactory,
      retryProvider: RetryProvider,
      domainMigrationInfo: DomainMigrationInfo,
      participantId: ParticipantId,
      ingestionConfig: IngestionConfig,
  )(implicit
      ec: ExecutionContext,
      templateJsonDecoder: TemplateJsonDecoder,
      closeContext: CloseContext,
  ): ValidatorStore =
    new DbValidatorStore(
      key,
      storage,
      loggerFactory,
      retryProvider,
      domainMigrationInfo,
      participantId,
      ingestionConfig,
    )

  case class Key(
      /** The validator party. */
      validatorParty: PartyId,
      /** The party-id of the DSO issuing CC managed by this wallet. */
      dsoParty: PartyId,
  ) extends PrettyPrinting {
    override def pretty: Pretty[Key] = prettyOfClass(
      param("validatorParty", _.validatorParty),
      param("dsoParty", _.dsoParty),
    )
  }

  /** Contract of a wallet store for a specific validator party. */
  def contractFilter(
      key: Key,
      domainMigrationId: Long,
  ): MultiDomainAcsStore.ContractFilter[
    ValidatorAcsStoreRowData,
    AcsInterfaceViewRowData.NoInterfacesIngested,
  ] = {
    import MultiDomainAcsStore.mkFilter
    val validator = key.validatorParty.toProtoPrimitive
    val dso = key.dsoParty.toProtoPrimitive

    MultiDomainAcsStore.SimpleContractFilter(
      key.validatorParty,
      Map[PackageQualifiedName, TemplateFilter[?, ?, ValidatorAcsStoreRowData]](
        mkFilter(walletCodegen.WalletAppInstall.COMPANION)(co =>
          co.payload.validatorParty == validator &&
            co.payload.dsoParty == dso
        ) { contract =>
          ValidatorAcsStoreRowData(
            contract = contract,
            userParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.endUserParty)),
            userName = Some(contract.payload.endUserName),
          )
        },
        mkFilter(validatorLicenseCodegen.ValidatorLicense.COMPANION)(co =>
          co.payload.validator == validator && co.payload.dso == dso
        ) { contract =>
          ValidatorAcsStoreRowData(
            contract = contract,
            validatorParty = Some(key.validatorParty),
          )
        },
        mkFilter(amuletCodegen.ValidatorRight.COMPANION)(co =>
          co.payload.validator == validator &&
            co.payload.dso == dso
        ) { contract =>
          ValidatorAcsStoreRowData(
            contract = contract,
            userParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.user)),
            validatorParty = Some(key.validatorParty),
          )
        },
        mkFilter(amuletCodegen.FeaturedAppRight.COMPANION)(co =>
          co.payload.dso == dso && co.payload.provider == validator
        ) { contract =>
          ValidatorAcsStoreRowData(
            contract = contract,
            contractExpiresAt = None,
            providerParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.provider)),
          )
        },
        mkFilter(topUpCodegen.ValidatorTopUpState.COMPANION)(co =>
          co.payload.validator == validator && co.payload.migrationId == domainMigrationId
        ) { contract =>
          ValidatorAcsStoreRowData(
            contract = contract,
            trafficSynchronizerId =
              Some(SynchronizerId.tryFromString(contract.payload.synchronizerId)),
          )
        },
        mkFilter(amuletrulesCodegen.ExternalPartySetupProposal.COMPANION)(co =>
          co.payload.validator == validator && co.payload.dso == dso
        ) { contract =>
          ValidatorAcsStoreRowData(
            contract = contract,
            userParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.user)),
          )
        },
        mkFilter(preapprovalCodegen.TransferPreapprovalProposal.COMPANION)(co =>
          co.payload.provider == validator
        ) { contract =>
          ValidatorAcsStoreRowData(
            contract = contract,
            userParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.receiver)),
          )
        },
        mkFilter(amuletrulesCodegen.TransferPreapproval.COMPANION)(co =>
          co.payload.provider == validator && co.payload.dso == dso
        ) { contract =>
          ValidatorAcsStoreRowData(
            contract = contract,
            userParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.receiver)),
            contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.expiresAt)),
          )
        },
        mkFilter(externalpartyamuletrulesCodegen.TransferCommand.COMPANION)(co =>
          co.payload.delegate == validator && co.payload.dso == dso
        ) { ValidatorAcsStoreRowData(_) },
        mkFilter(amuletCodegen.Amulet.COMPANION)(co =>
          co.payload.dso == dso &&
            co.payload.owner == validator
        )(ValidatorAcsStoreRowData(_)),
      ),
    )
  }

}
