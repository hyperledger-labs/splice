package com.daml.network.validator.store

import cats.syntax.traverseFilter.*
import com.daml.network.codegen.java.cc.{
  coin as coinCodegen,
  coinrules as coinrulesCodegen,
  validatorlicense as validatorLicenseCodegen,
}
import com.daml.network.codegen.java.cn.appmanager.store as appManagerCodegen
import com.daml.network.codegen.java.cn.wallet.{
  install as walletCodegen,
  topupstate as topUpCodegen,
}
import com.daml.network.environment.RetryProvider
import com.daml.network.environment.ParticipantAdminConnection.HasParticipantId
import com.daml.network.http.v0.definitions
import com.daml.network.store.MultiDomainAcsStore.{ConstrainedTemplate, QueryResult, TemplateFilter}
import com.daml.network.store.{CNNodeAppStoreWithoutHistory, Limit, MultiDomainAcsStore}
import com.daml.network.util.*
import com.daml.network.validator.store.db.DbValidatorStore
import com.daml.network.validator.store.db.ValidatorTables.ValidatorAcsStoreRowData
import com.daml.network.validator.store.memory.InMemoryValidatorStore
import com.daml.network.wallet.store.WalletStore
import com.digitalasset.canton.crypto.Hash
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

trait ValidatorStore extends WalletStore with CNNodeAppStoreWithoutHistory {
  import ValidatorStore.templatesMovedByMyAutomation

  /** The key identifying the parties considered by this store. */
  val key: ValidatorStore.Key

  def lookupWalletInstallByNameWithOffset(
      endUserName: String
  )(implicit tc: TraceContext): Future[QueryResult[
    Option[
      ContractWithState[walletCodegen.WalletAppInstall.ContractId, walletCodegen.WalletAppInstall]
    ]
  ]]

  def lookupValidatorLicenseWithOffset()(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[
      validatorLicenseCodegen.ValidatorLicense.ContractId,
      validatorLicenseCodegen.ValidatorLicense,
    ]]]
  ]

  def lookupValidatorRightByPartyWithOffset(
      party: PartyId
  )(implicit tc: TraceContext): Future[
    QueryResult[
      Option[ContractWithState[coinCodegen.ValidatorRight.ContractId, coinCodegen.ValidatorRight]]
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

  final def listCoinRulesTransferFollowers(
      coinRules: AssignedContract[
        coinrulesCodegen.CoinRules.ContractId,
        coinrulesCodegen.CoinRules,
      ],
      participantIdSource: HasParticipantId,
  )(implicit tc: TraceContext): Future[Seq[AssignedContract[?, ?]]] =
    multiDomainAcsStore.listAssignedContractsNotOnDomainN(
      coinRules.domain,
      participantIdSource,
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
  )(implicit
      ec: ExecutionContext,
      templateJsonDecoder: TemplateJsonDecoder,
      closeContext: CloseContext,
  ): ValidatorStore =
    storage match {
      case _: MemoryStorage =>
        new InMemoryValidatorStore(key, loggerFactory, retryProvider)
      case storage: DbStorage =>
        new DbValidatorStore(key, storage, loggerFactory, retryProvider)
    }

  case class Key(
      /** The validator party. */
      validatorParty: PartyId,
      /** The party-id of the SVC issuing CC managed by this wallet. */
      svcParty: PartyId,
      appManagerEnabled: Boolean,
  ) extends PrettyPrinting {
    override def pretty: Pretty[Key] = prettyOfClass(
      param("validatorParty", _.validatorParty),
      param("svcParty", _.svcParty),
    )
  }

  private[network] def templatesMovedByMyAutomation(
      appManagerEnabled: Boolean
  ): Seq[ConstrainedTemplate] =
    Seq[ConstrainedTemplate](
      walletCodegen.WalletAppInstall.COMPANION,
      coinCodegen.ValidatorRight.COMPANION,
      validatorLicenseCodegen.ValidatorFaucetCoupon.COMPANION,
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
  def contractFilter(key: Key): MultiDomainAcsStore.ContractFilter[ValidatorAcsStoreRowData] = {
    import MultiDomainAcsStore.mkFilter
    val validator = key.validatorParty.toProtoPrimitive
    val svc = key.svcParty.toProtoPrimitive

    MultiDomainAcsStore.SimpleContractFilter(
      key.validatorParty,
      Map[PackageQualifiedName, TemplateFilter[?, ?, ValidatorAcsStoreRowData]](
        mkFilter(walletCodegen.WalletAppInstall.COMPANION)(co =>
          co.payload.validatorParty == validator &&
            co.payload.svcParty == svc
        ) { contract =>
          ValidatorAcsStoreRowData(
            contract = contract,
            userParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.endUserParty)),
            userName = Some(contract.payload.endUserName),
          )
        },
        mkFilter(validatorLicenseCodegen.ValidatorLicense.COMPANION)(co =>
          co.payload.validator == validator && co.payload.svc == svc
        ) { contract =>
          ValidatorAcsStoreRowData(
            contract = contract,
            validatorParty = Some(key.validatorParty),
          )
        },
        mkFilter(validatorLicenseCodegen.ValidatorFaucetCoupon.COMPANION)(co =>
          co.payload.validator == validator && co.payload.svc == svc
        ) { contract =>
          ValidatorAcsStoreRowData(
            contract = contract,
            validatorParty = Some(key.validatorParty),
          )
        },
        mkFilter(coinCodegen.ValidatorRight.COMPANION)(co =>
          co.payload.validator == validator &&
            co.payload.svc == svc
        ) { contract =>
          ValidatorAcsStoreRowData(
            contract = contract,
            userParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.user)),
            validatorParty = Some(key.validatorParty),
          )
        },
        mkFilter(coinCodegen.FeaturedAppRight.COMPANION)(co =>
          co.payload.svc == svc && co.payload.provider == validator
        ) { contract =>
          ValidatorAcsStoreRowData(
            contract = contract,
            contractExpiresAt = None,
            providerParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.provider)),
          )
        },
        mkFilter(topUpCodegen.ValidatorTopUpState.COMPANION)(co =>
          co.payload.validator == validator
        ) { contract =>
          ValidatorAcsStoreRowData(
            contract = contract,
            trafficDomainId = Some(DomainId.tryFromString(contract.payload.domainId)),
          )
        },
        mkFilter(coinCodegen.Coin.COMPANION)(co =>
          co.payload.svc == svc &&
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
