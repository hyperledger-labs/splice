package com.daml.network.store

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.ledger.javaapi.data.{
  ContractMetadata,
  CreatedEvent,
  ExercisedEvent,
  Identifier,
  LedgerOffset,
  TransactionTree,
  TreeEvent,
  Unit as damlUnit,
  Value as damlValue,
}
import com.google.protobuf
import com.daml.network.codegen.java.cc.{
  coin as coinCodegen,
  expiry as expiryCodegen,
  fees as feesCodegen,
  round as roundCodegen,
  schedule as scheduleCodegen,
}
import com.daml.network.codegen.java.cc.round.types.Round
import com.daml.network.codegen.java.cn.cns as cnsCodegen
import com.daml.network.codegen.java.cn.wallet.subscriptions as subCodegen
import com.daml.network.codegen.java.cn.wallet.payment as paymentCodegen
import com.daml.network.codegen.java.cn.svcrules as svcCodegen
import com.daml.network.environment.ledger.api.{
  ActiveContract,
  IncompleteReassignmentEvent,
  Reassignment,
  ReassignmentEvent,
  ReassignmentUpdate,
  TransactionTreeUpdate,
}
import com.daml.network.util.{CNNodeUtil, Contract, Trees}
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.{DomainId, ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import org.scalatest.wordspec.AsyncWordSpec
import com.daml.lf.data.Numeric
import com.daml.network.codegen.java.cc.coin.FeaturedAppRight
import com.daml.network.codegen.java.cc.coinconfig.{CoinConfig, USD}
import com.daml.network.codegen.java.da.time.types.RelTime
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.protocol.LfContractId

import java.time.{Duration, Instant}
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

abstract class StoreTest extends AsyncWordSpec with BaseTest {

  protected def mkPartyId(name: String) = PartyId.tryFromProtoPrimitive(name + "::dummy")

  protected def mkParticipantId(name: String) =
    ParticipantId.tryFromProtoPrimitive("PAR::" + name + "::dummy")

  protected val svcParty: PartyId = mkPartyId("svc")

  protected def userParty(i: Int) = mkPartyId(s"user-$i")

  protected def providerParty(i: Int) = mkPartyId(s"provider-$i")

  /** @param n must 0-9
    * @param suffix must be a hex string
    */
  protected def validContractId(n: Int, suffix: String = "00"): String = "00" + s"0$n" * 31 + suffix

  private var cIdCounter = 0

  protected def nextCid() = {
    cIdCounter += 1
    // Note: contract ids that appear in contract payloads need to pass contract id validation,
    // otherwise JSON serialization will fail when storing contracts in the database.
    LfContractId.assertFromString("00" + f"$cIdCounter%064x").coid
  }

  private val schedule: scheduleCodegen.Schedule[Instant, CoinConfig[USD]] =
    CNNodeUtil.defaultCoinConfigSchedule(
      NonNegativeFiniteDuration(Duration.ofMinutes(10)),
      10,
      dummyDomain,
    )
  protected def coinRules() = {
    val templateId = coinCodegen.CoinRules.TEMPLATE_ID

    val template = new coinCodegen.CoinRules(
      svcParty.toProtoPrimitive,
      schedule,
      false,
      false,
    )
    Contract(
      identifier = templateId,
      contractId = new coinCodegen.CoinRules.ContractId(nextCid()),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
  }

  protected def cnsRules() = {
    val templateId = cnsCodegen.CnsRules.TEMPLATE_ID

    val template = new cnsCodegen.CnsRules(
      svcParty.toProtoPrimitive,
      new cnsCodegen.CnsRulesConfig(
        new RelTime(1_000_000),
        new RelTime(1_000_000),
        new java.math.BigDecimal(1.0).setScale(10),
      ),
    )
    Contract(
      identifier = templateId,
      contractId = new cnsCodegen.CnsRules.ContractId(nextCid()),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
  }

  protected val holdingFee = 1.0

  protected def openMiningRound(svc: PartyId, round: Long, coinPrice: Double) = {
    val template = new roundCodegen.OpenMiningRound(
      svc.toProtoPrimitive,
      new Round(round),
      numeric(coinPrice),
      Instant.now(),
      Instant.now().plusSeconds(600),
      new RelTime(1_000_000),
      CNNodeUtil.defaultTransferConfig(10, holdingFee),
      CNNodeUtil.issuanceConfig(10.0, 10.0, 10.0),
      new RelTime(1_000_000),
    )

    Contract(
      roundCodegen.OpenMiningRound.TEMPLATE_ID,
      new roundCodegen.OpenMiningRound.ContractId(round.toString),
      template,
      ContractMetadata.Empty(),
      protobuf.Any.getDefaultInstance,
    )
  }

  protected def closedMiningRound(svc: PartyId, round: Long) = {
    val template = new roundCodegen.ClosedMiningRound(
      svc.toProtoPrimitive,
      new Round(round),
      numeric(1),
      numeric(1),
      numeric(1),
    )

    Contract(
      roundCodegen.ClosedMiningRound.TEMPLATE_ID,
      new roundCodegen.ClosedMiningRound.ContractId(nextCid()),
      template,
      ContractMetadata.Empty(),
      protobuf.Any.getDefaultInstance,
    )
  }

  protected def coin(owner: PartyId, amount: Double, createdAtRound: Long, ratePerRound: Double) = {
    val templateId = coinCodegen.Coin.TEMPLATE_ID
    val template = new coinCodegen.Coin(
      svcParty.toProtoPrimitive,
      owner.toProtoPrimitive,
      new feesCodegen.ExpiringAmount(
        numeric(amount),
        new Round(createdAtRound),
        new feesCodegen.RatePerRound(numeric(ratePerRound)),
      ),
    )
    Contract(
      identifier = templateId,
      contractId = new coinCodegen.Coin.ContractId(nextCid()),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
  }

  protected def lockedCoin(
      owner: PartyId,
      amount: Double,
      createdAtRound: Long,
      ratePerRound: Double,
  ) = {
    val templateId = coinCodegen.LockedCoin.TEMPLATE_ID
    val coinTemplate = coin(owner, amount, createdAtRound, ratePerRound).payload
    val template = new coinCodegen.LockedCoin(
      coinTemplate,
      new expiryCodegen.TimeLock(java.util.List.of(), Instant.now()),
    )
    Contract(
      identifier = templateId,
      contractId = new coinCodegen.LockedCoin.ContractId(nextCid()),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
  }

  protected def appRewardCoupon(
      round: Int,
      provider: PartyId,
      featured: Boolean = false,
      amount: Numeric.Numeric = numeric(1.0),
      contractId: String = nextCid(),
  ): Contract[coinCodegen.AppRewardCoupon.ContractId, coinCodegen.AppRewardCoupon] =
    Contract(
      identifier = coinCodegen.AppRewardCoupon.TEMPLATE_ID,
      contractId = new coinCodegen.AppRewardCoupon.ContractId(contractId),
      payload = new coinCodegen.AppRewardCoupon(
        svcParty.toProtoPrimitive,
        provider.toProtoPrimitive,
        featured,
        amount,
        new Round(round),
      ),
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )

  protected def numeric(value: BigDecimal, scale: Int = 10) = {
    Numeric.assertFromBigDecimal(Numeric.Scale.assertFromInt(scale), value)
  }

  protected def validatorRewardCoupon(
      round: Int,
      user: PartyId,
      amount: Numeric.Numeric = numeric(1.0),
  ): Contract[
    coinCodegen.ValidatorRewardCoupon.ContractId,
    coinCodegen.ValidatorRewardCoupon,
  ] =
    Contract(
      identifier = coinCodegen.ValidatorRewardCoupon.TEMPLATE_ID,
      contractId = new coinCodegen.ValidatorRewardCoupon.ContractId(nextCid()),
      payload = new coinCodegen.ValidatorRewardCoupon(
        svcParty.toProtoPrimitive,
        user.toProtoPrimitive,
        amount,
        new Round(round),
      ),
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )

  protected def subscriptionInitialPayment(
      reference: subCodegen.SubscriptionRequest.ContractId,
      paymentId: subCodegen.SubscriptionInitialPayment.ContractId,
      userParty: PartyId,
      providerParty: PartyId,
      amount: BigDecimal,
  ) = {
    val subscriptionData =
      new subCodegen.SubscriptionData(
        userParty.toProtoPrimitive,
        providerParty.toProtoPrimitive,
        providerParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        "description",
      )
    val payData = new subCodegen.SubscriptionPayData(
      new paymentCodegen.PaymentAmount(numeric(amount.bigDecimal), paymentCodegen.Currency.CC),
      new RelTime(1L),
      new RelTime(1L),
    )
    val template = new subCodegen.SubscriptionInitialPayment(
      subscriptionData,
      payData,
      numeric(amount.bigDecimal),
      new coinCodegen.LockedCoin.ContractId(nextCid()),
      new Round(1L),
      reference,
    )
    Contract(
      subCodegen.SubscriptionInitialPayment.TEMPLATE_ID,
      paymentId,
      template,
      ContractMetadata.Empty(),
      protobuf.Any.getDefaultInstance,
    )
  }

  protected def featuredAppRight(
      providerParty: PartyId,
      contractId: String = nextCid(),
  ) = {
    val template = new FeaturedAppRight(svcParty.toProtoPrimitive, providerParty.toProtoPrimitive)
    Contract(
      FeaturedAppRight.TEMPLATE_ID,
      new FeaturedAppRight.ContractId(contractId),
      template,
      ContractMetadata.Empty(),
      protobuf.Any.getDefaultInstance,
    )
  }

  protected def svReward(
      svParty: PartyId,
      round: Int,
      amount: Numeric.Numeric = numeric(1.0),
      contractId: String = nextCid(),
  ): Contract[svcCodegen.SvReward.ContractId, svcCodegen.SvReward] =
    Contract(
      identifier = svcCodegen.SvReward.TEMPLATE_ID,
      contractId = new svcCodegen.SvReward.ContractId(contractId),
      payload = new svcCodegen.SvReward(
        svcParty.toProtoPrimitive,
        svParty.toProtoPrimitive,
        new Round(round),
        amount,
      ),
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )

  protected def svcReward(
      round: Int,
      amount: Numeric.Numeric = numeric(1.0),
      contractId: String = nextCid(),
  ): Contract[coinCodegen.SvcReward.ContractId, coinCodegen.SvcReward] =
    Contract(
      identifier = coinCodegen.SvcReward.TEMPLATE_ID,
      contractId = new coinCodegen.SvcReward.ContractId(contractId),
      payload = new coinCodegen.SvcReward(
        svcParty.toProtoPrimitive,
        new Round(round),
        amount,
      ),
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )

  protected def toCreatedEvent[TCid <: ContractId[T], T](
      contract: Contract[TCid, T],
      signatories: Seq[PartyId] = Seq.empty,
  ): CreatedEvent = {
    new CreatedEvent(
      eventId = "dummyEventId",
      contractId = contract.contractId.contractId,
      interfaceViews = Map.empty.asJava,
      failedInterfaceViews = Map.empty.asJava,
      templateId = contract.identifier,
      arguments = contract.payload.toValue,
      createArgumentsBlob = contract.createArgumentsBlob,
      contractMetadata = contract.metadata,
      witnessParties = Seq.empty.asJava,
      signatories = signatories.map(_.toProtoPrimitive).asJava,
      observers = Seq.empty.asJava,
      agreementText = None.toJava,
      contractKey = None.toJava,
    )
  }

  protected def toArchivedEvent[TCid <: ContractId[T], T](
      contract: Contract[TCid, T]
  ): ExercisedEvent = {
    new ExercisedEvent(
      eventId = "dummyEventId",
      contractId = contract.contractId.contractId,
      templateId = contract.identifier,
      interfaceId = None.toJava,
      witnessParties = Seq.empty.asJava,
      consuming = true,
      choice = "DummyChoiceName",
      choiceArgument = damlUnit.getInstance(),
      exerciseResult = damlUnit.getInstance(),
      actingParties = Seq.empty.asJava,
      childEventIds = Seq.empty.asJava,
    )
  }

  protected def toActiveContract[TCid <: ContractId[T], T](
      domain: DomainId,
      contract: Contract[TCid, T],
      counter: Long,
  ): ActiveContract =
    ActiveContract(domain, toCreatedEvent(contract, Seq(svcParty)), counter)

  protected def exercisedEvent[TCid <: ContractId[T], T](
      contractId: String,
      templateId: Identifier,
      interfaceId: Option[Identifier],
      choice: String,
      consuming: Boolean,
      argument: damlValue,
      result: damlValue,
  ): ExercisedEvent = {
    new ExercisedEvent(
      eventId = "dummyEventId",
      contractId = contractId,
      templateId = templateId,
      interfaceId = interfaceId.toJava,
      witnessParties = Seq.empty.asJava,
      consuming = consuming,
      choice = choice,
      choiceArgument = argument,
      exerciseResult = result,
      actingParties = Seq.empty.asJava,
      childEventIds = Seq.empty.asJava,
    )
  }

  protected def withEventId(
      event: TreeEvent,
      eventId: String,
  ): TreeEvent = event match {
    case created: CreatedEvent =>
      new CreatedEvent(
        eventId = eventId,
        contractId = created.getContractId,
        interfaceViews = created.getInterfaceViews,
        failedInterfaceViews = created.getFailedInterfaceViews,
        templateId = created.getTemplateId,
        arguments = created.getArguments,
        createArgumentsBlob = created.getCreateArgumentsBlob,
        contractMetadata = created.getContractMetadata,
        witnessParties = created.getWitnessParties,
        signatories = created.getSignatories,
        observers = created.getObservers,
        agreementText = created.getAgreementText,
        contractKey = created.getContractKey,
      )
    case exercised: ExercisedEvent =>
      new ExercisedEvent(
        eventId = eventId,
        contractId = exercised.getContractId,
        templateId = exercised.getTemplateId,
        interfaceId = exercised.getInterfaceId,
        witnessParties = exercised.getWitnessParties,
        consuming = exercised.isConsuming,
        choice = exercised.getChoice,
        choiceArgument = exercised.getChoiceArgument,
        exerciseResult = exercised.getExerciseResult,
        actingParties = exercised.getActingParties,
        childEventIds = exercised.getChildEventIds,
      )
    case _ => sys.error("Catch-all required because of no exhaustiveness checks with Java")
  }

  protected def withChildren(exercised: ExercisedEvent, childEventIds: Seq[String]) =
    new ExercisedEvent(
      eventId = exercised.getEventId,
      contractId = exercised.getContractId,
      templateId = exercised.getTemplateId,
      interfaceId = exercised.getInterfaceId,
      witnessParties = exercised.getWitnessParties,
      consuming = exercised.isConsuming,
      choice = exercised.getChoice,
      choiceArgument = exercised.getChoiceArgument,
      exerciseResult = exercised.getExerciseResult,
      actingParties = exercised.getActingParties,
      childEventIds = childEventIds.asJava,
    )

  protected lazy val dummyDomain = StoreTest.dummyDomain

  protected val dummy2Domain = DomainId.tryFromString("dummy2::domain")

  protected val defaultEffectiveAt: Instant = CantonTimestamp.Epoch.toInstant

  protected def toIncompleteUnassign[TCid <: ContractId[T], T](
      contract: Contract[TCid, T],
      unassignId: String,
      source: DomainId,
      target: DomainId,
      counter: Long,
  ): IncompleteReassignmentEvent.Unassign = IncompleteReassignmentEvent.Unassign(
    toUnassignEvent(
      contract.contractId,
      unassignId,
      source,
      target,
      counter,
    ),
    toCreatedEvent(contract, Seq(svcParty)),
  )

  protected def toIncompleteAssign[TCid <: ContractId[T], T](
      contract: Contract[TCid, T],
      unassignId: String,
      source: DomainId,
      target: DomainId,
      counter: Long,
  ): IncompleteReassignmentEvent.Assign = IncompleteReassignmentEvent.Assign(
    toAssignEvent(
      contract,
      unassignId,
      source,
      target,
      counter,
    )
  )

  protected def toUnassignEvent(
      contractId: ContractId[_],
      unassignId: String,
      source: DomainId,
      target: DomainId,
      counter: Long,
  ): ReassignmentEvent.Unassign =
    ReassignmentEvent.Unassign(
      unassignId = unassignId,
      submitter = userParty(1),
      contractId = contractId,
      source = source,
      target = target,
      counter = counter,
    )

  protected def toAssignEvent[TCid <: ContractId[T], T](
      contract: Contract[TCid, T],
      unassignId: String,
      source: DomainId,
      target: DomainId,
      counter: Long,
  ): ReassignmentEvent.Assign = ReassignmentEvent.Assign(
    unassignId = unassignId,
    submitter = userParty(1),
    source = source,
    target = target,
    createdEvent = toCreatedEvent(contract, Seq(svcParty)),
    counter = counter,
  )

  protected def mkValidatorRewardCoupon(i: Int) = validatorRewardCoupon(i, userParty(i))

  private var offsetCounter = 0

  private def nextOffset: String = {
    val offset = "%08d".format(offsetCounter)
    offsetCounter += 1
    offset
  }

  protected def mkCreateTx[TCid <: ContractId[T], T](
      offset: String,
      createRequests: Seq[Contract[TCid, T]],
      effectiveAt: Instant,
      createdEventSignatories: Seq[PartyId],
  ) = mkTx(
    offset,
    createRequests.map[TreeEvent](toCreatedEvent(_, createdEventSignatories)),
    effectiveAt,
  )

  protected def acs[TCid <: ContractId[T], T](
      acs: Seq[(Contract[TCid, T], DomainId, Long)] = Seq.empty,
      incompleteOut: Seq[(Contract[TCid, T], DomainId, DomainId, String, Long)] = Seq.empty,
      incompleteIn: Seq[(Contract[TCid, T], DomainId, DomainId, String, Long)] = Seq.empty,
      acsOffset: String = nextOffset,
  )(implicit store: MultiDomainAcsStore): Future[Unit] = for {
    _ <- store.ingestionSink.initialize()
    _ <- store.ingestionSink.ingestAcs(
      acsOffset,
      acs.map { case (contract, domain, counter) =>
        ActiveContract(domain, toCreatedEvent(contract, Seq(svcParty)), counter)
      },
      incompleteOut.map { case (c, sourceDomain, targetDomain, tfid, counter) =>
        toIncompleteUnassign(
          c,
          tfid,
          sourceDomain,
          targetDomain,
          counter,
        )
      },
      incompleteIn.map { case (c, sourceDomain, targetDomain, tfid, counter) =>
        toIncompleteAssign(
          c,
          tfid,
          sourceDomain,
          targetDomain,
          counter,
        )
      },
    )
  } yield ()

  // Convenient syntax to make the tests easy to read.
  protected implicit class DomainSyntax(private val domain: DomainId) {

    def create[TCid <: ContractId[T], T](
        c: Contract[TCid, T],
        offset: String = nextOffset,
        txEffectiveAt: Instant = defaultEffectiveAt,
        createdEventSignatories: Seq[PartyId] = Seq(svcParty),
    )(implicit store: MultiDomainAcsStore): Future[TransactionTree] = {
      val tx = mkCreateTx(
        offset,
        Seq(c),
        txEffectiveAt,
        createdEventSignatories,
      )

      store.ingestionSink
        .ingestUpdate(
          domain,
          TransactionTreeUpdate(tx),
        )
        .map(_ => tx)
    }

    def createMulti[TCid <: ContractId[T], T](
        c: Contract[TCid, T],
        offset: String = nextOffset,
        txEffectiveAt: Instant = defaultEffectiveAt,
        createdEventSignatories: Seq[PartyId] = Seq(svcParty),
    )(implicit stores: Seq[MultiDomainAcsStore]): Future[TransactionTree] = {
      val tx = mkCreateTx(
        offset,
        Seq(c),
        txEffectiveAt,
        createdEventSignatories,
      )
      val txUpdate = TransactionTreeUpdate(tx)
      // Note: runs the futures sequentially in order to get deterministic tests
      stores
        .foldLeft(Future.unit) { (acc, store) =>
          for {
            _ <- acc
            _ <- store.ingestionSink.ingestUpdate(domain, txUpdate)
          } yield ()
        }
        .map(_ => tx)
    }

    def archive[TCid <: ContractId[T], T](
        c: Contract[TCid, T],
        txEffectiveAt: Instant = defaultEffectiveAt,
    )(implicit store: MultiDomainAcsStore): Future[TransactionTree] = {
      val tx = mkTx(nextOffset, Seq(toArchivedEvent(c)), txEffectiveAt)
      store.ingestionSink
        .ingestUpdate(
          domain,
          TransactionTreeUpdate(
            tx
          ),
        )
        .map(_ => tx)
    }

    def ingest(
        makeTx: String => TransactionTree
    )(implicit store: MultiDomainAcsStore): Future[TransactionTree] = {
      val tx = makeTx(nextOffset)
      store.ingestionSink
        .ingestUpdate(
          domain,
          TransactionTreeUpdate(
            tx
          ),
        )
        .map(_ => tx)
    }

    def ingestMulti(
        makeTx: String => TransactionTree
    )(implicit stores: Seq[MultiDomainAcsStore]): Future[TransactionTree] = {
      val tx = makeTx(nextOffset)
      val txUpdate = TransactionTreeUpdate(tx)
      // Note: runs the futures sequentially in order to get deterministic tests
      stores
        .foldLeft(Future.unit) { (acc, store) =>
          for {
            _ <- acc
            _ <- store.ingestionSink.ingestUpdate(domain, txUpdate)
          } yield ()
        }
        .map(_ => tx)
    }

    def unassign[TCid <: ContractId[T], T](
        contractAndDomain: (Contract[TCid, T], DomainId),
        reassignmentId: String,
        counter: Long,
    )(implicit store: MultiDomainAcsStore): Future[Reassignment[ReassignmentEvent.Unassign]] = {
      val reassignment = mkReassignment(
        nextOffset,
        toUnassignEvent(
          contractAndDomain._1.contractId,
          reassignmentId,
          domain,
          contractAndDomain._2,
          counter,
        ),
      )

      store.ingestionSink
        .ingestUpdate(
          domain,
          ReassignmentUpdate(reassignment),
        )
        .map(_ => reassignment)
    }

    def assign[TCid <: ContractId[T], T](
        contractAndDomain: (Contract[TCid, T], DomainId),
        reassignmentId: String,
        counter: Long,
    )(implicit store: MultiDomainAcsStore): Future[Reassignment[ReassignmentEvent.Assign]] = {
      val reassignment = mkReassignment(
        nextOffset,
        toAssignEvent(
          contractAndDomain._1,
          reassignmentId,
          contractAndDomain._2,
          domain,
          counter,
        ),
      )

      store.ingestionSink
        .ingestUpdate(
          domain,
          ReassignmentUpdate(reassignment),
        )
        .map(_ => reassignment)
    }

    def exercise[TCid <: ContractId[T], T](
        contract: Contract[TCid, T],
        interfaceId: Option[Identifier],
        choiceName: String,
        choiceArgument: damlValue,
        exerciseResult: damlValue,
        offset: String = nextOffset,
        txEffectiveAt: Instant = defaultEffectiveAt,
    )(implicit store: MultiDomainAcsStore): Future[TransactionTree] = {
      val tx = mkTx(
        offset,
        Seq(mkExercise(contract, interfaceId, choiceName, choiceArgument, exerciseResult)),
        txEffectiveAt,
      )
      store.ingestionSink
        .ingestUpdate(
          domain,
          TransactionTreeUpdate(tx),
        )
        .map(_ => tx)
    }
  }

  private def nextTransactionId(): String = java.util.UUID.randomUUID().toString.replace("-", "")

  protected def mkTx(
      offset: String,
      events: Seq[TreeEvent],
      effectiveAt: Instant = defaultEffectiveAt,
  ): TransactionTree = {
    val transactionId = nextTransactionId()
    val eventsWithId = events.zipWithIndex.map { case (e, i) =>
      withEventId(e, s"$transactionId:$i")
    }
    val eventsById = eventsWithId.map(e => e.getEventId -> e).toMap
    val rootEventIds = eventsWithId.map(_.getEventId)
    new TransactionTree(
      transactionId = transactionId,
      commandId = "",
      workflowId = "",
      effectiveAt = effectiveAt,
      offset = offset,
      eventsById = eventsById.asJava,
      rootEventIds = rootEventIds.asJava,
    )
  }

  protected def mkExerciseTx(
      offset: String,
      root: ExercisedEvent,
      children: Seq[TreeEvent],
      effectiveAt: Instant = defaultEffectiveAt,
  ): TransactionTree = {
    val transactionId = nextTransactionId()
    val childrenWithId = children.zipWithIndex.map { case (e, i) =>
      withEventId(e, s"$transactionId:${i + 1}")
    }
    val rootWithId =
      withEventId(withChildren(root, childrenWithId.map(_.getEventId)), s"$transactionId:0")
    val eventsById = (rootWithId +: childrenWithId).map(e => e.getEventId -> e).toMap
    val rootEventIds = Seq(rootWithId.getEventId)
    new TransactionTree(
      transactionId = transactionId,
      commandId = "",
      workflowId = "",
      effectiveAt = effectiveAt,
      offset = offset,
      eventsById = eventsById.asJava,
      rootEventIds = rootEventIds.asJava,
    )
  }

  protected def mkReassignment[T <: ReassignmentEvent](offset: String, event: T): Reassignment[T] =
    Reassignment(
      updateId = "",
      offset = new LedgerOffset.Absolute(offset),
      event = event,
    )

  protected def mkExercise[TCid <: ContractId[T], T](
      contract: Contract[TCid, T],
      interfaceId: Option[Identifier],
      choiceName: String,
      choiceArgument: damlValue,
      exerciseResult: damlValue,
  ): TreeEvent =
    new ExercisedEvent(
      eventId = "dummyEventId",
      contractId = contract.contractId.contractId,
      templateId = contract.identifier,
      interfaceId = interfaceId.toJava,
      witnessParties = Seq.empty.asJava,
      consuming = false,
      choice = choiceName,
      choiceArgument = choiceArgument,
      exerciseResult = exerciseResult,
      actingParties = Seq.empty.asJava,
      childEventIds = Seq.empty.asJava,
    )
}

object StoreTest {

  val dummyDomain = DomainId.tryFromString("dummy::domain")

  case class TestTxLogIndexRecord(
      optOffset: Option[String],
      eventId: String,
      domainId: DomainId,
  ) extends TxLogStore.IndexRecord {
    override def acsContractId: Option[ContractId[_]] = None
  }

  case class TestTxLogEntry(
      indexRecord: TestTxLogIndexRecord,
      payload: String,
  ) extends TxLogStore.Entry[TestTxLogIndexRecord]

  object TestTxLogStoreParser extends TxLogStore.Parser[TestTxLogIndexRecord, TestTxLogEntry] {
    def parseAcs(
        acs: Seq[ActiveContract],
        incompleteOut: Seq[IncompleteReassignmentEvent.Unassign],
        incompleteIn: Seq[IncompleteReassignmentEvent.Assign],
    )(implicit
        tc: TraceContext
    ): Seq[(DomainId, TestTxLogEntry)] = Seq.empty

    override def tryParse(tx: TransactionTree, domain: DomainId)(implicit
        tc: TraceContext
    ): Seq[TestTxLogEntry] = {
      Trees.foldTree(tx, Seq.empty[TestTxLogEntry])(
        onCreate = (res, event, _) => {
          res :+
            TestTxLogEntry(
              indexRecord = TestTxLogIndexRecord(
                optOffset = Some(tx.getOffset),
                eventId = event.getEventId,
                domainId = dummyDomain,
              ),
              payload = event.getEventId,
            )
        },
        onExercise = (res, _, _) => res,
      )
    }

    override def error(
        offset: String,
        eventId: String,
        domainId: DomainId,
    ): Option[TestTxLogEntry] = None
  }
}
