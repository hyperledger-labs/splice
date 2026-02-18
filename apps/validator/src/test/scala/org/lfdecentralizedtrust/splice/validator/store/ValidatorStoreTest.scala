package org.lfdecentralizedtrust.splice.validator.store

import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext, SynchronizerAlias}
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.{
  install as walletCodegen,
  topupstate as topUpCodegen,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.{
  amulet as amuletCodegen,
  amuletrules as amuletrulesCodegen,
}
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import org.lfdecentralizedtrust.splice.environment.{DarResources, RetryProvider}
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.store.db.{AcsJdbcTypes, AcsTables, SplicePostgresTest}
import org.lfdecentralizedtrust.splice.store.{PageLimit, StoreTestBase}
import org.lfdecentralizedtrust.splice.util.{ResourceTemplateDecoder, TemplateJsonDecoder}
import org.lfdecentralizedtrust.splice.validator.config.{
  ValidatorDecentralizedSynchronizerConfig,
  ValidatorSynchronizerConfig,
}
import org.lfdecentralizedtrust.splice.validator.store.db.DbValidatorStore

import java.time.Instant
import scala.concurrent.Future

abstract class ValidatorStoreTest extends StoreTestBase with HasExecutionContext {

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
  }

  private lazy val user1 = userParty(1)
  private lazy val user2 = userParty(2)
  private lazy val validator = mkPartyId(s"validator")
  private lazy val sponsor = mkPartyId(s"sponsor")
  protected lazy val storeKey = ValidatorStore.Key(
    dsoParty = dsoParty,
    validatorParty = validator,
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

  private def validatorTopUpState(synchronizerId: SynchronizerId) = {
    val templateId = topUpCodegen.ValidatorTopUpState.TEMPLATE_ID_WITH_PACKAGE_ID
    val sequencerMemberId = "sequencerMemberId"
    val lastPurchasedAt = Instant.EPOCH
    val template = new topUpCodegen.ValidatorTopUpState(
      dsoParty.toProtoPrimitive,
      validator.toProtoPrimitive,
      sequencerMemberId,
      synchronizerId.toProtoPrimitive,
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
    val templateId = amuletrulesCodegen.ExternalPartySetupProposal.TEMPLATE_ID_WITH_PACKAGE_ID
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

  protected def mkStore(): Future[ValidatorStore]

  lazy val domain = dummyDomain.toProtoPrimitive
  lazy val synchronizerAlias = SynchronizerAlias.tryCreate(domain)
  lazy val domainConfig = ValidatorSynchronizerConfig(
    global = ValidatorDecentralizedSynchronizerConfig(
      synchronizerAlias
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
          DarResources.wallet.all
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
      IngestionConfig(),
    )
    for {
      _ <- store.multiDomainAcsStore.testIngestionSink.initialize()
      _ <- store.multiDomainAcsStore.testIngestionSink
        .ingestAcs(nextOffset(), Seq.empty, Seq.empty, Seq.empty)
      _ <- store.domains.ingestionSink.ingestConnectedDomains(
        Map(synchronizerAlias -> dummyDomain)
      )
    } yield store
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] = resetAllAppTables(storage)
}
