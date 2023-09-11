package com.daml.network.store

import com.daml.ledger.javaapi.data.ContractMetadata
import com.daml.network.codegen.java.cc.{
  coin as coinCodegen,
  validatorlicense as validatorLicenseCodegen,
}
import com.digitalasset.canton.metrics.MetricHandle.NoOpMetricsFactory
import com.digitalasset.canton.topology.DomainId

import java.time.Instant
import com.daml.network.codegen.java.cn.appmanager.store as appManagerCodegen
import com.daml.network.codegen.java.cn.wallet.install as walletCodegen
import com.daml.network.codegen.java.cn.wallet.topupstate as topUpCodegen
import com.daml.network.environment.RetryProvider
import com.daml.network.store.db.{AcsJdbcTypes, AcsTables, CNPostgresTest}
import com.daml.network.util.{Contract, ResourceTemplateDecoder, TemplateJsonDecoder}
import com.daml.network.validator.config.{ValidatorDomainConfig, ValidatorGlobalDomainConfig}
import com.daml.network.validator.store.ValidatorStore
import com.daml.network.validator.store.db.DbValidatorStore
import com.daml.network.validator.store.memory.InMemoryValidatorStore
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.ledger.offset.Offset
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.{DomainAlias, HasActorSystem, HasExecutionContext}
import com.google.protobuf

import scala.concurrent.Future

abstract class ValidatorStoreTest extends StoreTest with HasExecutionContext {

  "ValidatorStore" should {

    "lookupWalletInstallByNameWithOffset" should {

      "return correct results" in {
        val signatories = Seq(svcParty, validator)
        for {
          store <- mkStore()
          unwantedContract = walletInstall(user2, "user2")
          wantedContract = walletInstall(user1, "user1")
          _ <- dummyDomain.create(unwantedContract, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(wantedContract, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          result <- store.lookupWalletInstallByNameWithOffset("user1")
        } yield {
          result.value.value.contractId should be(
            wantedContract.contractId
          )
        }
      }
    }

    "lookupValidatorLicenseWithOffset" should {

      "return correct results" in {
        val signatories = Seq(svcParty, validator) // actually, validator is only observer
        for {
          store <- mkStore()
          license1 = validatorLicense()
          _ <- dummyDomain.create(license1, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          result <- store.lookupValidatorLicenseWithOffset()
        } yield {
          result.value.value.contractId should be(license1.contractId)
        }
      }

    }

    "lookupValidatorRightByPartyWithOffset" should {

      "return correct results" in {
        for {
          store <- mkStore()
          right2 = validatorRight(user2)
          right1 = validatorRight(user1)
          _ <- dummyDomain.create(right2, createdEventSignatories = Seq(user2, validator))(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(right1, createdEventSignatories = Seq(user1, validator))(
            store.multiDomainAcsStore
          )
          result <- store.lookupValidatorRightByPartyWithOffset(user1)
        } yield {
          result.value.value.contractId should be(
            right1.contractId
          )
        }
      }
    }

    "lookupValidatorTopUpStateWithOffset" should {

      "return correct results" in {
        val signatories = Seq(validator)
        for {
          store <- mkStore()
          topUpState2 = validatorTopUpState(dummy2Domain)
          topUpState1 = validatorTopUpState(dummyDomain)
          _ <- dummyDomain.create(topUpState2, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(topUpState1, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          result <- store.lookupValidatorTopUpStateWithOffset(dummyDomain)
        } yield {
          result.value.value.contractId should be(
            topUpState1.contractId
          )
        }
      }
    }

    "listUsers" should {

      "return correct results" in {
        val signatories = Seq(svcParty, validator)
        for {
          store <- mkStore()
          user1Contract = walletInstall(user1, "user1")
          user2Contract = walletInstall(user2, "user2")
          _ <- dummyDomain.create(user1Contract, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(user2Contract, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          result <- store.listUsers()
        } yield {
          result should contain theSameElementsAs Seq(
            "user1",
            "user2",
          )
        }
      }
    }

    "lookup*AppConfiguration" should {

      "return correct results" in {
        val signatories = Seq(validator)
        for {
          store <- mkStore()
          contractP1V1 = appConfiguration(provider1, 1)
          contractP1V2 = appConfiguration(provider1, 2)
          contractP2V1 = appConfiguration(provider2, 1)
          contractP2V2 = appConfiguration(provider2, 2)
          _ <- dummyDomain.create(contractP1V1, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(contractP1V2, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(contractP2V1, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(contractP2V2, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          latest1 <- store.lookupLatestAppConfiguration(provider1)
          latest2 <- store.lookupLatestAppConfiguration(provider2)
          resultP1V1 <- store.lookupAppConfiguration(provider1, 1)
          resultP2V2 <- store.lookupAppConfiguration(provider2, 2)
        } yield {
          latest1.value.contract.contractId should be(
            contractP1V2.contractId
          )
          latest2.value.contract.contractId should be(
            contractP2V2.contractId
          )
          resultP1V1.value.value.contractId should be(
            contractP1V1.contractId
          )
          resultP2V2.value.value.contractId should be(
            contractP2V2.contractId
          )
        }
      }
    }

    "lookupAppRelease" should {

      "return correct results" in {
        val signatories = Seq(validator)
        for {
          store <- mkStore()
          releaseP1V1 = appRelease(provider1, "v1")
          releaseP1V2 = appRelease(provider1, "v2")
          releaseP2V1 = appRelease(provider2, "v1")
          releaseP2V2 = appRelease(provider2, "v2")
          _ <- dummyDomain.create(releaseP1V1, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(releaseP1V2, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(releaseP2V1, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(releaseP2V2, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          resultP1V2 <- store.lookupAppRelease(provider1, "v2")
          resultP2V1 <- store.lookupAppRelease(provider2, "v1")
        } yield {
          resultP1V2.value.value.contractId should be(
            releaseP1V2.contractId
          )
          resultP2V1.value.value.contractId should be(
            releaseP2V1.contractId
          )
        }
      }
    }

    "lookupRegisteredApp" should {

      "return correct results" in {
        val signatories = Seq(validator)
        for {
          store <- mkStore()
          app1 = registeredApp(provider1)
          app2 = registeredApp(provider2)
          _ <- dummyDomain.create(app1, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(app2, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          result1 <- store.lookupRegisteredApp(provider1)
        } yield {
          result1.value.value.contractId should be(
            app1.contractId
          )
        }
      }
    }

    "lookupInstalledApp" should {

      "return correct results" in {
        val signatories = Seq(validator)
        for {
          store <- mkStore()
          app1 = installedApp(provider1)
          app2 = installedApp(provider2)
          _ <- dummyDomain.create(app1, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(app2, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          result1 <- store.lookupInstalledApp(provider1)
        } yield {
          result1.value.value.contractId should be(
            app1.contractId
          )
        }
      }
    }

    "listRegisteredApps" should {

      "return correct results" in {
        val signatories = Seq(validator)
        for {
          store <- mkStore()
          app1 = registeredApp(provider1)
          configP1V1 = appConfiguration(provider1, 1)
          configP1V2 = appConfiguration(provider1, 2)
          app2 = registeredApp(provider2)
          app3 = registeredApp(provider3)
          configP3V1 = appConfiguration(provider3, 1)
          _ <- dummyDomain.create(app1, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(app2, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(app3, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(configP1V1, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(configP1V2, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(configP3V1, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          result <- store.listRegisteredApps()
        } yield {
          result.map(a =>
            (a.registered.contractId, a.configuration.contractId)
          ) should contain theSameElementsAs Seq(
            (app1.contractId, configP1V2.contractId),
            (app3.contractId, configP3V1.contractId),
          )
        }
      }
    }

    "listInstalledApps" should {

      "return correct results" in {
        val signatories = Seq(validator)
        for {
          store <- mkStore()
          app1 = registeredApp(provider1)
          configP1V1 = appConfiguration(provider1, 1)
          configP1V2 = appConfiguration(provider1, 2)
          approvedConfigP1V1 = approvedReleaseConfig(provider1, 1)
          approvedConfigP1V2 = approvedReleaseConfig(provider1, 2)
          installedApp1 = installedApp(provider1)
          app2 = registeredApp(provider2)
          configP2V1 = appConfiguration(provider2, 1)
          installedApp2 = installedApp(provider2)
          _ <- dummyDomain.create(app1, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(configP1V1, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(configP1V2, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(approvedConfigP1V1, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(approvedConfigP1V2, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(installedApp1, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(app2, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(configP2V1, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(installedApp2, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          result <- store.listInstalledApps()
        } yield {
          result.map(a =>
            (
              a.installed.contractId,
              a.latestConfiguration.contractId,
              a.approvedReleaseConfigurations.map(_.contractId).toSet,
            )
          ) should contain theSameElementsAs Seq(
            (
              installedApp1.contractId,
              configP1V2.contractId,
              Set(approvedConfigP1V1.contractId, approvedConfigP1V2.contractId),
            ),
            (
              installedApp2.contractId,
              configP2V1.contractId,
              Set.empty,
            ),
          )
        }
      }
    }

    "listCoinRulesTransferFollowers" should {
      val filtered = ValidatorStore.contractFilter(storeKey)
      val handled = ValidatorStore.templatesMovedByMyAutomation.view.map(_.TEMPLATE_ID).toSet
      // "not handled" usually means "handled by a separate automation"
      val knownNotHandled =
        Seq(
          validatorLicenseCodegen.ValidatorLicense.COMPANION -> "handled by SvSvc",
          coinCodegen.FeaturedAppRight.COMPANION -> "handled by SvSvc",
          topUpCodegen.ValidatorTopUpState.COMPANION -> "tied to a specific domainId, never migrated",
        ).view.map { case (c, reason) => (c.TEMPLATE_ID, reason) }.toMap

      "handle every listened-to contract type not handled elsewhere" in {
        filtered.ingestionFilter.templateIds should contain allElementsOf handled
      }

      "either handle or not handle each template" in {
        forEvery(Table(("template", "reason"), knownNotHandled.toSeq: _*)) { (templateId, reason) =>
          handled shouldNot contain(templateId) withClue reason
        }
        // ^ does part of v but with better error messages
        handled should contain noElementsOf knownNotHandled.keySet
      }

      // How do we ensure that new templates get migration added somewhere?  If
      // they are queried in an app, they need to be added to *some* ingestion
      // filter, so the author will naturally pick one.  The idea is that we
      // compare
      //
      //  1. the list the system effectively forces everyone to update, the
      //     ingestion filters, to
      //  2. the lists that are *not* checked except at domain migration time,
      //
      // and (hopefully) forestall overlooked required changes that way.

      "give reason for all listened-to contract types not handled" in {
        val observablyUnhandled = filtered.ingestionFilter.templateIds diff handled
        val mistakes = observablyUnhandled diff knownNotHandled.keySet
        (observablyUnhandled should contain theSameElementsAs knownNotHandled.keySet
          withClue s"did you add $mistakes?")
      }
    }
  }

  private lazy val user1 = userParty(1)
  private lazy val user2 = userParty(2)
  private lazy val provider1 = providerParty(1)
  private lazy val provider2 = providerParty(2)
  private lazy val provider3 = providerParty(3)
  private lazy val validator = mkPartyId(s"validator")
  private lazy val sponsor = mkPartyId(s"sponsor")
  protected lazy val storeKey = ValidatorStore.Key(
    svcParty = svcParty,
    validatorParty = validator,
  )

  private def walletInstall(endUserParty: PartyId, endUserName: String) = {
    val templateId = walletCodegen.WalletAppInstall.TEMPLATE_ID
    val template = new walletCodegen.WalletAppInstall(
      svcParty.toProtoPrimitive,
      validator.toProtoPrimitive,
      endUserName,
      endUserParty.toProtoPrimitive,
    )
    Contract(
      identifier = templateId,
      contractId = new walletCodegen.WalletAppInstall.ContractId(nextCid()),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
  }

  private def validatorLicense() = {
    val templateId = validatorLicenseCodegen.ValidatorLicense.TEMPLATE_ID
    val template = new validatorLicenseCodegen.ValidatorLicense(
      validator.toProtoPrimitive,
      sponsor.toProtoPrimitive,
      svcParty.toProtoPrimitive,
    )
    Contract(
      identifier = templateId,
      contractId = new validatorLicenseCodegen.ValidatorLicense.ContractId(nextCid()),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
  }

  private def validatorRight(user: PartyId) = {
    val templateId = coinCodegen.ValidatorRight.TEMPLATE_ID
    val template = new coinCodegen.ValidatorRight(
      svcParty.toProtoPrimitive,
      user.toProtoPrimitive,
      validator.toProtoPrimitive,
    )
    Contract(
      identifier = templateId,
      contractId = new coinCodegen.ValidatorRight.ContractId(nextCid()),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
  }

  private def validatorTopUpState(domainId: DomainId) = {
    val templateId = topUpCodegen.ValidatorTopUpState.TEMPLATE_ID
    val sequencerMemberId = "sequencerMemberId"
    val lastPurchasedAt = Instant.EPOCH
    val template = new topUpCodegen.ValidatorTopUpState(
      validator.toProtoPrimitive,
      sequencerMemberId,
      domainId.toProtoPrimitive,
      lastPurchasedAt,
    )
    Contract(
      identifier = templateId,
      contractId = new topUpCodegen.ValidatorTopUpState.ContractId(nextCid()),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
  }

  private def appConfiguration(provider: PartyId, version: Long) = {
    val templateId = appManagerCodegen.AppConfiguration.TEMPLATE_ID
    val json = "{}"
    val template = new appManagerCodegen.AppConfiguration(
      validator.toProtoPrimitive,
      provider.toProtoPrimitive,
      version,
      json,
    )
    Contract(
      identifier = templateId,
      contractId = new appManagerCodegen.AppConfiguration.ContractId(nextCid()),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
  }

  private def appRelease(provider: PartyId, version: String) = {
    val templateId = appManagerCodegen.AppRelease.TEMPLATE_ID
    val json = "{}"
    val template = new appManagerCodegen.AppRelease(
      validator.toProtoPrimitive,
      provider.toProtoPrimitive,
      version,
      json,
    )
    Contract(
      identifier = templateId,
      contractId = new appManagerCodegen.AppRelease.ContractId(nextCid()),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
  }

  private def registeredApp(provider: PartyId) = {
    val templateId = appManagerCodegen.RegisteredApp.TEMPLATE_ID
    val template = new appManagerCodegen.RegisteredApp(
      validator.toProtoPrimitive,
      provider.toProtoPrimitive,
    )
    Contract(
      identifier = templateId,
      contractId = new appManagerCodegen.RegisteredApp.ContractId(nextCid()),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
  }

  private def installedApp(provider: PartyId) = {
    val templateId = appManagerCodegen.InstalledApp.TEMPLATE_ID
    val url = "https://app.canton.network/install"
    val template = new appManagerCodegen.InstalledApp(
      validator.toProtoPrimitive,
      provider.toProtoPrimitive,
      url,
    )
    Contract(
      identifier = templateId,
      contractId = new appManagerCodegen.InstalledApp.ContractId(nextCid()),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
  }

  private def approvedReleaseConfig(provider: PartyId, version: Long) = {
    val templateId = appManagerCodegen.ApprovedReleaseConfiguration.TEMPLATE_ID
    val json = "{}"
    val jsonHash = "abcd"
    val template = new appManagerCodegen.ApprovedReleaseConfiguration(
      validator.toProtoPrimitive,
      provider.toProtoPrimitive,
      version,
      json,
      jsonHash,
    )
    Contract(
      identifier = templateId,
      contractId = new appManagerCodegen.ApprovedReleaseConfiguration.ContractId(nextCid()),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
  }

  protected def mkStore(): Future[ValidatorStore]

  lazy val offset = Offset.fromByteArray(Array(1, 2, 3).map(_.toByte))
  lazy val domain = dummyDomain.toProtoPrimitive
  lazy val domainAlias = DomainAlias.tryCreate(domain)
  lazy val domainConfig = ValidatorDomainConfig(
    global = ValidatorGlobalDomainConfig(
      domainAlias,
      "",
    )
  )
}

class InMemoryValidatorStoreTest extends ValidatorStoreTest {
  override protected def mkStore(): Future[InMemoryValidatorStore] = {
    val store = new InMemoryValidatorStore(
      key = storeKey,
      domainConfig = domainConfig,
      loggerFactory = loggerFactory,
      retryProvider =
        RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
    )
    for {
      _ <- store.multiDomainAcsStore.ingestionSink.initialize()
      _ <- store.multiDomainAcsStore.ingestionSink
        .ingestAcs(offset.toHexString, Seq.empty, Seq.empty, Seq.empty)
      _ <- store.domains.ingestionSink.ingestConnectedDomains(
        Map(domainAlias -> dummyDomain)
      )
    } yield store
  }
}

class DbValidatorStoreTest
    extends ValidatorStoreTest
    with HasActorSystem
    with CNPostgresTest
    with AcsJdbcTypes
    with AcsTables {

  override protected def mkStore(): Future[DbValidatorStore] = {
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(
        Seq(
          "dar/canton-coin-0.1.0.dar",
          "dar/wallet-0.1.0.dar",
          "dar/app-manager-0.1.0.dar",
        )
      )
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    val store = new DbValidatorStore(
      key = storeKey,
      domainConfig = domainConfig,
      storage = storage,
      loggerFactory = loggerFactory,
      retryProvider =
        RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
    )
    for {
      _ <- store.multiDomainAcsStore.ingestionSink.initialize()
      _ <- store.multiDomainAcsStore.ingestionSink
        .ingestAcs(offset.toHexString, Seq.empty, Seq.empty, Seq.empty)
      _ <- store.domains.ingestionSink.ingestConnectedDomains(
        Map(domainAlias -> dummyDomain)
      )
    } yield store
  }

  override protected def cleanDb(storage: DbStorage): Future[?] =
    for {
      _ <- resetAllCnAppTables(storage)
    } yield ()
}
