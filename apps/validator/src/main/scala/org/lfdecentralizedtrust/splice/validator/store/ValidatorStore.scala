// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.store

import cats.syntax.traverseFilter.*
import org.lfdecentralizedtrust.splice.automation.MultiDomainExpiredContractTrigger.ListExpiredContracts
import org.lfdecentralizedtrust.splice.codegen.java.splice.appmanager.store as appManagerCodegen
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
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.{
  ConstrainedTemplate,
  QueryResult,
  TemplateFilter,
}
import org.lfdecentralizedtrust.splice.store.{AppStore, Limit, MultiDomainAcsStore, PageLimit}
import org.lfdecentralizedtrust.splice.util.*
import org.lfdecentralizedtrust.splice.validator.store.db.DbValidatorStore
import org.lfdecentralizedtrust.splice.validator.store.db.ValidatorTables.ValidatorAcsStoreRowData
import org.lfdecentralizedtrust.splice.wallet.store.WalletStore
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.crypto.Hash
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.resource.{DbStorage, Storage}
import com.digitalasset.canton.topology.{DomainId, ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

trait ValidatorStore extends WalletStore with AppStore {
  import ValidatorStore.templatesMovedByMyAutomation

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

  def lookupValidatorLicenseWithOffset()(implicit tc: TraceContext): Future[
    QueryResult[Option[ContractWithState[
      validatorLicenseCodegen.ValidatorLicense.ContractId,
      validatorLicenseCodegen.ValidatorLicense,
    ]]]
  ]

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
      domainId: DomainId
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
  ] = { (now: CantonTimestamp, limit: PageLimit) => implicit traceContext =>
    {
      def isReadyForRenewal(preapproval: amuletrulesCodegen.TransferPreapproval): Boolean =
        now.toInstant.isAfter(preapproval.expiresAt.minus(renewalDuration.asJava))

      // TODO(#14568): Move this filter and limit into the DB query
      multiDomainAcsStore
        .listAssignedContracts(
          amuletrulesCodegen.TransferPreapproval.COMPANION
        )
        .map(_.filter(p => isReadyForRenewal(p.payload)).take(limit.limit))
    }
  }

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

  def lookupLatestAppConfiguration(
      provider: PartyId
  )(implicit tc: TraceContext): Future[Option[ContractWithState[
    appManagerCodegen.AppConfiguration.ContractId,
    appManagerCodegen.AppConfiguration,
  ]]]

  def lookupLatestAppConfigurationByName(
      name: String
  )(implicit tc: TraceContext): Future[Option[ContractWithState[
    appManagerCodegen.AppConfiguration.ContractId,
    appManagerCodegen.AppConfiguration,
  ]]]

  def lookupAppConfiguration(
      provider: PartyId,
      version: Long,
  )(implicit tc: TraceContext): Future[QueryResult[Option[ContractWithState[
    appManagerCodegen.AppConfiguration.ContractId,
    appManagerCodegen.AppConfiguration,
  ]]]]

  def lookupAppRelease(
      provider: PartyId,
      version: String,
  )(implicit tc: TraceContext): Future[QueryResult[
    Option[ContractWithState[appManagerCodegen.AppRelease.ContractId, appManagerCodegen.AppRelease]]
  ]]

  def lookupRegisteredApp(
      provider: PartyId
  )(implicit tc: TraceContext): Future[QueryResult[
    Option[
      ContractWithState[appManagerCodegen.RegisteredApp.ContractId, appManagerCodegen.RegisteredApp]
    ]
  ]]

  def lookupInstalledApp(
      provider: PartyId
  )(implicit tc: TraceContext): Future[QueryResult[
    Option[
      ContractWithState[appManagerCodegen.InstalledApp.ContractId, appManagerCodegen.InstalledApp]
    ]
  ]]

  def listRegisteredApps(limit: Limit = Limit.DefaultLimit)(implicit
      traceContext: TraceContext
  ): Future[Seq[ValidatorStore.RegisteredApp]] =
    multiDomainAcsStore.listContracts(appManagerCodegen.RegisteredApp.COMPANION, limit).flatMap {
      apps =>
        apps.toList.traverseFilter { app =>
          lookupLatestAppConfiguration(
            PartyId.tryFromProtoPrimitive(app.contract.payload.provider)
          ).map(config =>
            config.map(
              ValidatorStore.RegisteredApp(
                app,
                _,
              )
            )
          )
        }
    }

  def listInstalledApps(limit: Limit = Limit.DefaultLimit)(implicit
      traceContext: TraceContext
  ): Future[Seq[
    ValidatorStore.InstalledApp
  ]] =
    multiDomainAcsStore
      .listContracts(appManagerCodegen.InstalledApp.COMPANION, limit)
      .flatMap(_.toList.traverseFilter { app =>
        val provider = PartyId.tryFromProtoPrimitive(app.contract.payload.provider)
        for {
          approvedReleaseConfigs <- listApprovedReleaseConfigurations(provider, limit)
          latestConfigO <- lookupLatestAppConfiguration(provider)
        } yield latestConfigO.map(
          ValidatorStore.InstalledApp(
            app,
            _,
            approvedReleaseConfigs,
          )
        )
      })

  protected def listApprovedReleaseConfigurations(
      provider: PartyId,
      limit: Limit = Limit.DefaultLimit,
  )(implicit
      traceContext: TraceContext
  ): Future[Seq[
    ContractWithState[
      appManagerCodegen.ApprovedReleaseConfiguration.ContractId,
      appManagerCodegen.ApprovedReleaseConfiguration,
    ]
  ]]

  def lookupApprovedReleaseConfiguration(
      provider: PartyId,
      releaseConfigurationHash: Hash,
  )(implicit traceContext: TraceContext): Future[QueryResult[Option[ContractWithState[
    appManagerCodegen.ApprovedReleaseConfiguration.ContractId,
    appManagerCodegen.ApprovedReleaseConfiguration,
  ]]]]

  final def listAmuletRulesTransferFollowers(
      amuletRules: AssignedContract[
        amuletrulesCodegen.AmuletRules.ContractId,
        amuletrulesCodegen.AmuletRules,
      ]
  )(implicit tc: TraceContext): Future[Seq[AssignedContract[?, ?]]] =
    multiDomainAcsStore.listAssignedContractsNotOnDomainN(
      amuletRules.domain,
      templatesMovedByMyAutomation(key.appManagerEnabled),
    )
}

object ValidatorStore {

  final case class RegisteredApp(
      registered: ContractWithState[
        appManagerCodegen.RegisteredApp.ContractId,
        appManagerCodegen.RegisteredApp,
      ],
      configuration: ContractWithState[
        appManagerCodegen.AppConfiguration.ContractId,
        appManagerCodegen.AppConfiguration,
      ],
  )

  final case class InstalledApp(
      installed: ContractWithState[
        appManagerCodegen.InstalledApp.ContractId,
        appManagerCodegen.InstalledApp,
      ],
      latestConfiguration: ContractWithState[
        appManagerCodegen.AppConfiguration.ContractId,
        appManagerCodegen.AppConfiguration,
      ],
      approvedReleaseConfigurations: Seq[ContractWithState[
        appManagerCodegen.ApprovedReleaseConfiguration.ContractId,
        appManagerCodegen.ApprovedReleaseConfiguration,
      ]],
  )

  def apply(
      key: Key,
      storage: Storage,
      loggerFactory: NamedLoggerFactory,
      retryProvider: RetryProvider,
      domainMigrationInfo: DomainMigrationInfo,
      participantId: ParticipantId,
  )(implicit
      ec: ExecutionContext,
      templateJsonDecoder: TemplateJsonDecoder,
      closeContext: CloseContext,
  ): ValidatorStore =
    storage match {
      case storage: DbStorage =>
        new DbValidatorStore(
          key,
          storage,
          loggerFactory,
          retryProvider,
          domainMigrationInfo,
          participantId,
        )
      case storageType => throw new RuntimeException(s"Unsupported storage type $storageType")
    }

  case class Key(
      /** The validator party. */
      validatorParty: PartyId,
      /** The party-id of the DSO issuing CC managed by this wallet. */
      dsoParty: PartyId,
      appManagerEnabled: Boolean,
  ) extends PrettyPrinting {
    override def pretty: Pretty[Key] = prettyOfClass(
      param("validatorParty", _.validatorParty),
      param("dsoParty", _.dsoParty),
    )
  }

  private[splice] def templatesMovedByMyAutomation(
      appManagerEnabled: Boolean
  ): Seq[ConstrainedTemplate] =
    Seq[ConstrainedTemplate](
      walletCodegen.WalletAppInstall.COMPANION,
      amuletCodegen.ValidatorRight.COMPANION,
      amuletrulesCodegen.ExternalPartySetupProposal.COMPANION,
    ) ++ (if (appManagerEnabled)
            Seq[ConstrainedTemplate](
              appManagerCodegen.AppConfiguration.COMPANION,
              appManagerCodegen.AppRelease.COMPANION,
              appManagerCodegen.RegisteredApp.COMPANION,
              appManagerCodegen.InstalledApp.COMPANION,
              appManagerCodegen.ApprovedReleaseConfiguration.COMPANION,
            )
          else Seq.empty)

  /** Contract of a wallet store for a specific validator party. */
  def contractFilter(
      key: Key,
      domainMigrationId: Long,
  ): MultiDomainAcsStore.ContractFilter[ValidatorAcsStoreRowData] = {
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
            trafficDomainId = Some(DomainId.tryFromString(contract.payload.synchronizerId)),
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
          )
        },
        mkFilter(externalpartyamuletrulesCodegen.TransferCommand.COMPANION)(co =>
          co.payload.delegate == validator && co.payload.dso == dso
        ) { ValidatorAcsStoreRowData(_) },
        mkFilter(amuletCodegen.Amulet.COMPANION)(co =>
          co.payload.dso == dso &&
            co.payload.owner == validator
        )(ValidatorAcsStoreRowData(_)),
      ) ++ (if (key.appManagerEnabled)
              Map[PackageQualifiedName, TemplateFilter[?, ?, ValidatorAcsStoreRowData]](
                mkFilter(appManagerCodegen.AppConfiguration.COMPANION)(co =>
                  co.payload.validatorOperator == validator
                ) { contract =>
                  val name = io.circe.parser
                    .decode[definitions.AppConfiguration](contract.payload.json)
                    .map(_.name)
                    .getOrElse(
                      throw new IllegalArgumentException(
                        s"Failed to extract name from ${contract.payload.json}"
                      )
                    )
                  ValidatorAcsStoreRowData(
                    contract = contract,
                    contractExpiresAt = None,
                    providerParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.provider)),
                    appConfigurationVersion = Some(contract.payload.version),
                    appConfigurationName = Some(name),
                  )
                },
                mkFilter(appManagerCodegen.AppRelease.COMPANION)(co =>
                  co.payload.validatorOperator == validator
                ) { contract =>
                  ValidatorAcsStoreRowData(
                    contract = contract,
                    providerParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.provider)),
                    appReleaseVersion = Some(contract.payload.version),
                  )
                },
                mkFilter(appManagerCodegen.RegisteredApp.COMPANION)(co =>
                  co.payload.validatorOperator == validator
                ) { contract =>
                  ValidatorAcsStoreRowData(
                    contract = contract,
                    providerParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.provider)),
                  )
                },
                mkFilter(appManagerCodegen.InstalledApp.COMPANION)(co =>
                  co.payload.validatorOperator == validator
                ) { contract =>
                  ValidatorAcsStoreRowData(
                    contract = contract,
                    providerParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.provider)),
                  )
                },
                mkFilter(appManagerCodegen.ApprovedReleaseConfiguration.COMPANION)(co =>
                  co.payload.validatorOperator == validator
                ) { contract =>
                  ValidatorAcsStoreRowData(
                    contract = contract,
                    providerParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.provider)),
                    jsonHash = Some(contract.payload.jsonHash),
                  )
                },
              )
            else Map.empty[PackageQualifiedName, TemplateFilter[?, ?, ValidatorAcsStoreRowData]]),
    )
  }

}
