package org.lfdecentralizedtrust.splice.store

import com.daml.metrics.api.noop.NoOpMetricsFactory
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet as amuletCodegen
import com.digitalasset.canton.topology.DomainId

import java.time.Instant
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules as amuletrulesCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.appmanager.store as appManagerCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.install as walletCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.topupstate as topUpCodegen
import org.lfdecentralizedtrust.splice.environment.{DarResources, RetryProvider}
import org.lfdecentralizedtrust.splice.store.db.{AcsJdbcTypes, AcsTables, SplicePostgresTest}
import org.lfdecentralizedtrust.splice.util.{
  AssignedContract,
  ResourceTemplateDecoder,
  TemplateJsonDecoder,
}
import org.lfdecentralizedtrust.splice.validator.config.{
  ValidatorDecentralizedSynchronizerConfig,
  ValidatorSynchronizerConfig,
}
import org.lfdecentralizedtrust.splice.validator.store.ValidatorStore
import org.lfdecentralizedtrust.splice.validator.store.db.DbValidatorStore
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.{DomainAlias, HasActorSystem, HasExecutionContext}

import scala.concurrent.Future
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.tracing.TraceContext
import io.circe.syntax.*

abstract class ValidatorStoreTest extends StoreTest with HasExecutionContext {

  "ValidatorStore" should {

    "lookupWalletInstallByNameWithOffset" should {

      "return correct results" in {
        val signatories = Seq(dsoParty, validator)
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
        val signatories = Seq(dsoParty, validator) // actually, validator is only observer
        for {
          store <- mkStore()
          license1 = validatorLicense(validator, sponsor)
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
        val signatories = Seq(dsoParty, validator)
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

    "listExternalPartySetupProposals" should {
      "return correct result" in {
        for {
          store <- mkStore()
          proposal1 = externalPartySetupProposal(user1)
          proposal2 = externalPartySetupProposal(user2)
          _ <- dummyDomain.create(proposal1, createdEventSignatories = Seq(validator))(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(proposal2, createdEventSignatories = Seq(validator))(
            store.multiDomainAcsStore
          )
          result <- store.listExternalPartySetupProposals()
        } yield {
          result.map(_.contract) should contain theSameElementsAs Seq(proposal1, proposal2)
        }
      }
    }

    "listTransferPreapprovals" should {
      "return correct results" in {
        for {
          store <- mkStore()
          signatories = Seq(dsoParty, validator, user1)
          preapproval1 = transferPreapproval(user1, validator, time(0), time(1))
          preapproval2 = transferPreapproval(user2, validator, time(0), time(1))
          _ <- dummyDomain.create(
            preapproval1,
            createdEventSignatories = Seq(dsoParty, validator, user1),
          )(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(
            preapproval2,
            createdEventSignatories = Seq(dsoParty, validator, user2),
          )(
            store.multiDomainAcsStore
          )
          result <- store.listTransferPreapprovals()
        } yield {
          result.map(_.contract) should contain theSameElementsAs Seq(preapproval1, preapproval2)
        }
      }
    }

    "listExpiringTransferPreapprovals" should {
      "return correct results" in {
        for {
          store <- mkStore()
          signatories = Seq(dsoParty, validator, user1)
          preapproval1 = transferPreapproval(user1, validator, time(0), expiresAt = time(2))
          preapproval2 = transferPreapproval(user2, validator, time(0), expiresAt = time(3))
          _ <- dummyDomain.create(preapproval1, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(preapproval2, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
        } yield {
          def expiringContractsAt(timestamp: CantonTimestamp) = store
            .listExpiringTransferPreapprovals(NonNegativeFiniteDuration.ofSeconds(1))(
              timestamp,
              PageLimit.tryCreate(10),
            )(TraceContext.empty)
            .futureValue
            .map(_.contract)

          expiringContractsAt(time(1)) should be(empty)
          expiringContractsAt(time(2)) should contain theSameElementsAs Seq(preapproval1)
          expiringContractsAt(time(3)) should contain theSameElementsAs Seq(
            preapproval1,
            preapproval2,
          )
        }
      }
    }

    "lookupExternalPartySetupProposalByUserPartyWithOffset" should {
      "return correct results" in {
        for {
          store <- mkStore()
          proposal1 = externalPartySetupProposal(user1)
          proposal2 = externalPartySetupProposal(user2)
          _ <- dummyDomain.create(proposal1, createdEventSignatories = Seq(validator))(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(proposal2, createdEventSignatories = Seq(validator))(
            store.multiDomainAcsStore
          )
          result <- store.lookupExternalPartySetupProposalByUserPartyWithOffset(user1)
        } yield {
          result.value.value.contract shouldBe proposal1
        }
      }
    }

    "lookupTransferPreapprovalByReceiverPartyWithOffset" should {
      "return correct results" in {
        for {
          store <- mkStore()
          preapproval1 = transferPreapproval(user1, validator, time(0), time(1))
          preapproval2 = transferPreapproval(user2, validator, time(0), time(1))
          _ <- dummyDomain.create(
            preapproval1,
            createdEventSignatories = Seq(dsoParty, validator, user1),
          )(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(
            preapproval2,
            createdEventSignatories = Seq(dsoParty, validator, user2),
          )(
            store.multiDomainAcsStore
          )
          result <- store.lookupTransferPreapprovalByReceiverPartyWithOffset(user1)
        } yield {
          result.value.value.contract shouldBe preapproval1
        }
      }
    }

    "lookup*AppConfiguration" should {

      "return correct results" in {
        val signatories = Seq(validator)
        for {
          store <- mkStore()
          contractP1V1 = appConfiguration(provider1, 1, "a")
          contractP1V2 = appConfiguration(provider1, 2, "a")
          contractP2V1 = appConfiguration(provider2, 1, "b")
          contractP2V2 = appConfiguration(provider2, 2, "b")
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
          nameResult <- store.lookupLatestAppConfigurationByName("a")
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
          nameResult.value.contract.contractId should be(
            contractP1V2.contractId
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
          configP1V1 = appConfiguration(provider1, 1, "a")
          configP1V2 = appConfiguration(provider1, 2, "a")
          app2 = registeredApp(provider2)
          app3 = registeredApp(provider3)
          configP3V1 = appConfiguration(provider3, 1, "b")
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
          configP1V1 = appConfiguration(provider1, 1, "a")
          configP1V2 = appConfiguration(provider1, 2, "a")
          approvedConfigP1V1 = approvedReleaseConfig(provider1, 1)
          approvedConfigP1V2 = approvedReleaseConfig(provider1, 2)
          installedApp1 = installedApp(provider1)
          app2 = registeredApp(provider2)
          configP2V1 = appConfiguration(provider2, 1, "b")
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

    "listAmuletRulesTransferFollowers" should {
      "return correct results" in {
        val signatories = Seq(validator)
        for {
          store <- mkStore()
          amuletRules1 = amuletRules()
          walletInstall1 = walletInstall(user1, "user1")
          // TODO (#7822) move to UserWallet
          amulet1 = amulet(validator, 1, 1L, 0.1)
          validatorRight1 = validatorRight(user1)
          appConfiguration1 = appConfiguration(provider1, 1L, "config")
          appRelease1 = appRelease(provider1, "version")
          registeredApp1 = registeredApp(provider1)
          installedApp1 = installedApp(provider1)
          approvedReleaseConfig1 = approvedReleaseConfig(provider1, 1L)
          validatorFaucetCoupon1 = validatorFaucetCoupon(validator)
          externalPartySetupProposal1 = externalPartySetupProposal(user1)
          _ <- dummyDomain.create(walletInstall1, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(amulet1, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(validatorRight1, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(appConfiguration1, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(appRelease1, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(registeredApp1, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(installedApp1, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(approvedReleaseConfig1, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(validatorFaucetCoupon1, createdEventSignatories = signatories)(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(
            externalPartySetupProposal1,
            createdEventSignatories = signatories,
          )(
            store.multiDomainAcsStore
          )
          _ <- dummy2Domain.create(amuletRules1)(
            store.multiDomainAcsStore
          )
          tfResult <- store.listAmuletRulesTransferFollowers(
            AssignedContract(amuletRules1, dummy2Domain)
          )
        } yield {
          val actual = tfResult.map(_.contract.identifier.getEntityName)
          val expected =
            ValidatorStore
              .templatesMovedByMyAutomation(true)
              .map(_.getTemplateIdWithPackageId.getEntityName)
          actual should contain theSameElementsAs expected
        }
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
    dsoParty = dsoParty,
    validatorParty = validator,
    appManagerEnabled = true,
  )

  private def walletInstall(endUserParty: PartyId, endUserName: String) = {
    val templateId = walletCodegen.WalletAppInstall.TEMPLATE_ID_WITH_PACKAGE_ID
    val template = new walletCodegen.WalletAppInstall(
      dsoParty.toProtoPrimitive,
      validator.toProtoPrimitive,
      endUserName,
      endUserParty.toProtoPrimitive,
    )
    contract(
      identifier = templateId,
      contractId = new walletCodegen.WalletAppInstall.ContractId(nextCid()),
      payload = template,
    )
  }

  private def validatorRight(user: PartyId) = {
    val templateId = amuletCodegen.ValidatorRight.TEMPLATE_ID_WITH_PACKAGE_ID
    val template = new amuletCodegen.ValidatorRight(
      dsoParty.toProtoPrimitive,
      user.toProtoPrimitive,
      validator.toProtoPrimitive,
    )
    contract(
      identifier = templateId,
      contractId = new amuletCodegen.ValidatorRight.ContractId(nextCid()),
      payload = template,
    )
  }

  private def validatorTopUpState(domainId: DomainId) = {
    val templateId = topUpCodegen.ValidatorTopUpState.TEMPLATE_ID_WITH_PACKAGE_ID
    val sequencerMemberId = "sequencerMemberId"
    val lastPurchasedAt = Instant.EPOCH
    val template = new topUpCodegen.ValidatorTopUpState(
      dsoParty.toProtoPrimitive,
      validator.toProtoPrimitive,
      sequencerMemberId,
      domainId.toProtoPrimitive,
      domainMigrationId,
      lastPurchasedAt,
    )
    contract(
      identifier = templateId,
      contractId = new topUpCodegen.ValidatorTopUpState.ContractId(nextCid()),
      payload = template,
    )
  }

  private def externalPartySetupProposal(user: PartyId) = {
    val templateId = amuletrulesCodegen.ExternalPartySetupProposal.TEMPLATE_ID
    val template = new amuletrulesCodegen.ExternalPartySetupProposal(
      validator.toProtoPrimitive,
      user.toProtoPrimitive,
      dsoParty.toProtoPrimitive,
      Instant.EPOCH,
      Instant.EPOCH,
    )
    contract(
      identifier = templateId,
      contractId = new amuletrulesCodegen.ExternalPartySetupProposal.ContractId(nextCid()),
      payload = template,
    )
  }

  private def appConfiguration(provider: PartyId, version: Long, name: String) = {
    val templateId = appManagerCodegen.AppConfiguration.TEMPLATE_ID_WITH_PACKAGE_ID
    val json = new definitions.AppConfiguration(
      version,
      name,
      "https://example.com/ui",
      Vector("https://example.com/ui"),
    ).asJson
    val template = new appManagerCodegen.AppConfiguration(
      validator.toProtoPrimitive,
      provider.toProtoPrimitive,
      version,
      json.noSpaces,
    )
    contract(
      identifier = templateId,
      contractId = new appManagerCodegen.AppConfiguration.ContractId(nextCid()),
      payload = template,
    )
  }

  private def appRelease(provider: PartyId, version: String) = {
    val templateId = appManagerCodegen.AppRelease.TEMPLATE_ID_WITH_PACKAGE_ID
    val json = "{}"
    val template = new appManagerCodegen.AppRelease(
      validator.toProtoPrimitive,
      provider.toProtoPrimitive,
      version,
      json,
    )
    contract(
      identifier = templateId,
      contractId = new appManagerCodegen.AppRelease.ContractId(nextCid()),
      payload = template,
    )
  }

  private def registeredApp(provider: PartyId) = {
    val templateId = appManagerCodegen.RegisteredApp.TEMPLATE_ID_WITH_PACKAGE_ID
    val template = new appManagerCodegen.RegisteredApp(
      validator.toProtoPrimitive,
      provider.toProtoPrimitive,
    )
    contract(
      identifier = templateId,
      contractId = new appManagerCodegen.RegisteredApp.ContractId(nextCid()),
      payload = template,
    )
  }

  private def installedApp(provider: PartyId) = {
    val templateId = appManagerCodegen.InstalledApp.TEMPLATE_ID_WITH_PACKAGE_ID
    val url = "https://app.canton.network/install"
    val template = new appManagerCodegen.InstalledApp(
      validator.toProtoPrimitive,
      provider.toProtoPrimitive,
      url,
    )
    contract(
      identifier = templateId,
      contractId = new appManagerCodegen.InstalledApp.ContractId(nextCid()),
      payload = template,
    )
  }

  private def approvedReleaseConfig(provider: PartyId, version: Long) = {
    val templateId = appManagerCodegen.ApprovedReleaseConfiguration.TEMPLATE_ID_WITH_PACKAGE_ID
    val json = "{}"
    val jsonHash = "abcd"
    val template = new appManagerCodegen.ApprovedReleaseConfiguration(
      validator.toProtoPrimitive,
      provider.toProtoPrimitive,
      version,
      json,
      jsonHash,
    )
    contract(
      identifier = templateId,
      contractId = new appManagerCodegen.ApprovedReleaseConfiguration.ContractId(nextCid()),
      payload = template,
    )
  }

  protected def mkStore(): Future[ValidatorStore]

  lazy val domain = dummyDomain.toProtoPrimitive
  lazy val domainAlias = DomainAlias.tryCreate(domain)
  lazy val domainConfig = ValidatorSynchronizerConfig(
    global = ValidatorDecentralizedSynchronizerConfig(
      domainAlias
    )
  )
}

class DbValidatorStoreTest
    extends ValidatorStoreTest
    with HasActorSystem
    with SplicePostgresTest
    with AcsJdbcTypes
    with AcsTables {

  override protected def mkStore(): Future[DbValidatorStore] = {
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(
        DarResources.amulet.all ++
          DarResources.wallet.all ++
          DarResources.appManager.all
      )
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    val store = new DbValidatorStore(
      key = storeKey,
      storage = storage,
      loggerFactory = loggerFactory,
      retryProvider =
        RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
      DomainMigrationInfo(
        domainMigrationId,
        None,
      ),
      participantId = mkParticipantId("ValidatorStoreTest"),
    )
    for {
      _ <- store.multiDomainAcsStore.testIngestionSink.initialize()
      _ <- store.multiDomainAcsStore.testIngestionSink
        .ingestAcs(Some(nextOffset()), Seq.empty, Seq.empty, Seq.empty)
      _ <- store.domains.ingestionSink.ingestConnectedDomains(
        Map(domainAlias -> dummyDomain)
      )
    } yield store
  }

  override protected def cleanDb(storage: DbStorage): Future[?] =
    for {
      _ <- resetAllAppTables(storage)
    } yield ()
}
