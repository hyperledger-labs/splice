package org.lfdecentralizedtrust.splice.store

import cats.data.NonEmptyList
import com.daml.ledger.api.v2.TraceContextOuterClass
import com.daml.ledger.javaapi.data.codegen.{ContractId, DamlRecord as CodegenDamlRecord}
import com.daml.ledger.javaapi.data.{
  CreatedEvent,
  DamlRecord,
  Event,
  ExercisedEvent,
  Identifier,
  Transaction,
  Unit as damlUnit,
  Value as damlValue,
}
import com.digitalasset.canton.config.CantonRequireTypes.String3
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.{LogEntry, NamedLogging, SuppressionRule}
import com.digitalasset.canton.protocol.LfContractId
import com.google.protobuf.ByteString
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.{
  payment as paymentCodegen,
  subscriptions as subCodegen,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.{
  amulet as amuletCodegen,
  amuletrules as amuletrulesCodegen,
  ans as ansCodegen,
  expiry as expiryCodegen,
  externalpartyamuletrules as externalpartyamuletrulesCodegen,
  fees as feesCodegen,
  round as roundCodegen,
  schedule as scheduleCodegen,
  validatorlicense as validatorLicenseCodegen,
}
import org.lfdecentralizedtrust.splice.environment.{BaseLedgerConnection, DarResource, DarResources}
import org.lfdecentralizedtrust.splice.environment.ledger.api.{
  ActiveContract,
  IncompleteReassignmentEvent,
  Reassignment,
  ReassignmentEvent,
  ReassignmentUpdate,
  TransactionTreeUpdate,
  TreeUpdate,
  TreeUpdateOrOffsetCheckpoint,
}
import org.lfdecentralizedtrust.splice.util.{
  Contract,
  EventId,
  PackageQualifiedName,
  SpliceUtil,
  Trees,
}
import com.digitalasset.canton.{BaseTest, HasActorSystem, HasExecutionContext}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.{ParticipantId, PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.daml.lf.data.Numeric
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.FeaturedAppRight
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{AmuletConfig, USD}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.svstate.{RewardState, SvRewardState}
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.test.dummyholding.DummyHolding
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_AddSv
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.voterequestoutcome.VRO_Accepted
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  ActionRequiringConfirmation,
  DsoRules_AddSv,
  DsoRules_CloseVoteRequest,
  DsoRules_CloseVoteRequestResult,
  Reason,
  Vote,
  VoteRequest,
}
import org.lfdecentralizedtrust.splice.history.{AmuletCreate, AppRewardCreate}
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.HasIngestionSink
import org.lfdecentralizedtrust.splice.store.db.TxLogRowData
import org.scalatest.wordspec.AsyncWordSpec
import org.slf4j.event.Level

import java.nio.file.{Files, Paths}
import java.time.temporal.ChronoUnit
import java.time.{Duration, Instant}
import java.util
import java.util.Optional
import scala.concurrent.{Future, blocking}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

abstract class StoreTestBase
    extends AsyncWordSpec
    with BaseTest
    with HasActorSystem
    with HasExecutionContext
    with NamedLogging {

  protected val upgradedAppRewardCouponPackageId = "upgradedpackageid"
  protected val dummyHoldingPackageId = DummyHolding.TEMPLATE_ID.getPackageId
  protected val maliciousPackageId = "maliciouspackageid"

  // Looks up the package name from the package ID in dars.lock, to avoid having to parse all DARs just to find this mapping
  // TODO(#3937): this is quite hacky. What we should really do is just auto-generate DarResources instead of deriving it from DARs at runtime.
  protected def tryGetPackageNameFromDarLock(identifier: Identifier): Option[String] = {
    val path = Paths.get("daml/dars.lock")
    Files
      .readAllLines(path)
      .asScala
      .map(_.trim.split("\\s+"))
      .filter(line => line.length > 1 && line(2) == identifier.getPackageId)
      .map(_(0))
      .headOption
  }

  protected def getPackageName(identifier: Identifier) = {
    tryGetPackageNameFromDarLock(identifier).getOrElse(
      PackageQualifiedName
        .lookupFromResources(identifier)
        .map(_.packageName)
        // should only be necessary for upgrade/malicious identifiers
        .orElse(
          Map(
            upgradedAppRewardCouponPackageId -> amuletCodegen.AppRewardCoupon.PACKAGE_NAME,
            dummyHoldingPackageId -> DummyHolding.PACKAGE_NAME, // not in DarResources
            maliciousPackageId -> "malicious-package",
          )
            .get(identifier.getPackageId)
        )
        .getOrElse(
          throw new IllegalArgumentException(
            s"Identifier is not present in resources nor does it look like mock data: $identifier"
          )
        )
    )
  }

  protected def mkPartyId(name: String) = PartyId.tryFromProtoPrimitive(name + "::dummy")

  protected def mkParticipantId(name: String) =
    ParticipantId.tryFromProtoPrimitive("PAR::" + name + "::dummy")

  protected val dsoParty: PartyId = mkPartyId("dso")

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

  protected def time(n: Long): CantonTimestamp = CantonTimestamp.ofEpochSecond(n)

  private def schedule(
      initialTickDuration: Long
  ): scheduleCodegen.Schedule[Instant, AmuletConfig[USD]] = {
    SpliceUtil.defaultAmuletConfigSchedule(
      NonNegativeFiniteDuration(Duration.ofMinutes(initialTickDuration)),
      10,
      dummyDomain,
    )
  }

  protected def amuletRules(initialTickDuration: Long = 10) = {
    val templateId = amuletrulesCodegen.AmuletRules.TEMPLATE_ID_WITH_PACKAGE_ID

    val template = new amuletrulesCodegen.AmuletRules(
      dsoParty.toProtoPrimitive,
      schedule(initialTickDuration),
      false,
    )
    contract(
      identifier = templateId,
      contractId = new amuletrulesCodegen.AmuletRules.ContractId(nextCid()),
      payload = template,
    )
  }

  protected def externalPartyAmuletRules() = {
    val templateId =
      externalpartyamuletrulesCodegen.ExternalPartyAmuletRules.TEMPLATE_ID_WITH_PACKAGE_ID

    val template = new externalpartyamuletrulesCodegen.ExternalPartyAmuletRules(
      dsoParty.toProtoPrimitive
    )
    contract(
      identifier = templateId,
      contractId =
        new externalpartyamuletrulesCodegen.ExternalPartyAmuletRules.ContractId(nextCid()),
      payload = template,
    )
  }

  protected def transferCommand(
      sender: PartyId,
      receiver: PartyId,
      delegate: PartyId,
      amount: BigDecimal,
      expiresAt: Instant,
      nonce: Long,
  ) = {
    val templateId = externalpartyamuletrulesCodegen.TransferCommand.TEMPLATE_ID_WITH_PACKAGE_ID

    val template = new externalpartyamuletrulesCodegen.TransferCommand(
      dsoParty.toProtoPrimitive,
      sender.toProtoPrimitive,
      receiver.toProtoPrimitive,
      delegate.toProtoPrimitive,
      amount.bigDecimal,
      expiresAt,
      nonce,
      Optional.of("transfer-command-description"),
    )
    contract(
      identifier = templateId,
      contractId = new externalpartyamuletrulesCodegen.TransferCommand.ContractId(nextCid()),
      payload = template,
    )
  }

  protected def ansRules() = {
    val templateId = ansCodegen.AnsRules.TEMPLATE_ID_WITH_PACKAGE_ID

    val template = new ansCodegen.AnsRules(
      dsoParty.toProtoPrimitive,
      new ansCodegen.AnsRulesConfig(
        new RelTime(1_000_000),
        new RelTime(1_000_000),
        new java.math.BigDecimal(1.0).setScale(10),
        "ANS entry: ",
      ),
    )
    contract(
      identifier = templateId,
      contractId = new ansCodegen.AnsRules.ContractId(nextCid()),
      payload = template,
    )
  }

  protected val holdingFee = BigDecimal(1.0)

  protected def openMiningRound(dso: PartyId, round: Long, amuletPrice: Double) = {
    val template = new roundCodegen.OpenMiningRound(
      dso.toProtoPrimitive,
      new Round(round),
      numeric(amuletPrice),
      Instant.now().truncatedTo(ChronoUnit.MICROS),
      Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(600),
      new RelTime(1_000_000),
      SpliceUtil.defaultTransferConfig(10, holdingFee),
      SpliceUtil.issuanceConfig(10.0, 10.0, 10.0),
      new RelTime(1_000_000),
    )

    contract(
      roundCodegen.OpenMiningRound.TEMPLATE_ID_WITH_PACKAGE_ID,
      new roundCodegen.OpenMiningRound.ContractId(round.toString),
      template,
    )
  }

  protected def issuingMiningRound(dso: PartyId, round: Long) = {
    val template = new roundCodegen.IssuingMiningRound(
      dso.toProtoPrimitive,
      new Round(round),
      numeric(1.0), // issuancePerValidatorRewardCoupon
      numeric(1.0), // issuancePerFeaturedAppRewardCoupon
      numeric(2.0), // issuancePerUnfeaturedAppRewardCoupon
      numeric(1.0), // issuancePerSvRewardCoupon
      Instant.now().truncatedTo(ChronoUnit.MICROS),
      Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(600),
      java.util.Optional.of(numeric(1.0)), // optIssuancePerValidatorFaucetCoupon
    )

    contract(
      roundCodegen.IssuingMiningRound.TEMPLATE_ID_WITH_PACKAGE_ID,
      new roundCodegen.IssuingMiningRound.ContractId(nextCid()),
      template,
    )
  }

  protected def closedMiningRound(dso: PartyId, round: Long) = {
    val template = new roundCodegen.ClosedMiningRound(
      dso.toProtoPrimitive,
      new Round(round),
      numeric(1),
      numeric(1),
      numeric(1),
      numeric(1),
      None.toJava,
    )

    contract(
      roundCodegen.ClosedMiningRound.TEMPLATE_ID_WITH_PACKAGE_ID,
      new roundCodegen.ClosedMiningRound.ContractId(nextCid()),
      template,
    )
  }

  protected def amulet(
      owner: PartyId,
      amount: BigDecimal,
      createdAtRound: Long,
      ratePerRound: BigDecimal,
      version: DarResource = DarResources.amulet_current,
      dso: PartyId = dsoParty,
      contractId: String = nextCid(),
  ) = {
    val templateId = new Identifier(
      version.packageId,
      amuletCodegen.Amulet.TEMPLATE_ID.getModuleName,
      amuletCodegen.Amulet.TEMPLATE_ID.getEntityName,
    )
    val template = new amuletCodegen.Amulet(
      dso.toProtoPrimitive,
      owner.toProtoPrimitive,
      new feesCodegen.ExpiringAmount(
        numeric(amount),
        new Round(createdAtRound),
        new feesCodegen.RatePerRound(numeric(ratePerRound)),
      ),
    )
    contract(
      identifier = templateId,
      contractId = new amuletCodegen.Amulet.ContractId(contractId),
      payload = template,
    )
  }

  protected def lockedAmulet(
      owner: PartyId,
      amount: BigDecimal,
      createdAtRound: Long,
      ratePerRound: BigDecimal,
      version: DarResource = DarResources.amulet_current,
      dso: PartyId = dsoParty,
      holders: Seq[PartyId] = Seq.empty,
  ) = {
    val templateId = new Identifier(
      version.packageId,
      amuletCodegen.LockedAmulet.TEMPLATE_ID.getModuleName,
      amuletCodegen.LockedAmulet.TEMPLATE_ID.getEntityName,
    )
    val amuletTemplate = amulet(owner, amount, createdAtRound, ratePerRound, version, dso).payload
    val template = new amuletCodegen.LockedAmulet(
      amuletTemplate,
      new expiryCodegen.TimeLock(
        holders.map(_.toProtoPrimitive).asJava,
        Instant.now().truncatedTo(ChronoUnit.MICROS),
        None.toJava,
      ),
    )
    contract(
      identifier = templateId,
      contractId = new amuletCodegen.LockedAmulet.ContractId(nextCid()),
      payload = template,
    )
  }

  protected def appRewardCoupon(
      round: Int,
      provider: PartyId,
      featured: Boolean = false,
      amount: Numeric.Numeric = numeric(1.0),
      contractId: String = nextCid(),
  ): Contract[amuletCodegen.AppRewardCoupon.ContractId, amuletCodegen.AppRewardCoupon] =
    contract(
      identifier = amuletCodegen.AppRewardCoupon.TEMPLATE_ID_WITH_PACKAGE_ID,
      contractId = new amuletCodegen.AppRewardCoupon.ContractId(contractId),
      payload = new amuletCodegen.AppRewardCoupon(
        dsoParty.toProtoPrimitive,
        provider.toProtoPrimitive,
        featured,
        amount,
        new Round(round),
        Optional.empty(),
      ),
    )

  protected def appActivityMarker(
      provider: PartyId,
      weight: Numeric.Numeric = numeric(1.0),
      beneficiary: Option[PartyId] = None,
      contractId: String = nextCid(),
  ): Contract[
    amuletCodegen.FeaturedAppActivityMarker.ContractId,
    amuletCodegen.FeaturedAppActivityMarker,
  ] =
    contract(
      identifier = amuletCodegen.FeaturedAppActivityMarker.TEMPLATE_ID_WITH_PACKAGE_ID,
      contractId = new amuletCodegen.FeaturedAppActivityMarker.ContractId(contractId),
      payload = new amuletCodegen.FeaturedAppActivityMarker(
        dsoParty.toProtoPrimitive,
        provider.toProtoPrimitive,
        beneficiary.getOrElse(provider).toProtoPrimitive,
        weight,
      ),
    )

  protected def numeric(value: BigDecimal, scale: Int = 10) = {
    Numeric.assertFromBigDecimal(Numeric.Scale.assertFromInt(scale), value)
  }

  protected def validatorLicense(
      validator: PartyId,
      sponsor: PartyId,
      faucetState: Option[validatorLicenseCodegen.FaucetState] = None,
  ) = {
    val templateId = validatorLicenseCodegen.ValidatorLicense.TEMPLATE_ID_WITH_PACKAGE_ID
    val dummyVersion = "0.1.0"
    val dummyContactPoint = s"${validator.uid.identifier}@example.com"
    val template = new validatorLicenseCodegen.ValidatorLicense(
      validator.toProtoPrimitive,
      sponsor.toProtoPrimitive,
      dsoParty.toProtoPrimitive,
      faucetState.toJava,
      Some(
        new validatorLicenseCodegen.ValidatorLicenseMetadata(
          defaultEffectiveAt,
          dummyVersion,
          dummyContactPoint,
        )
      ).toJava,
      Some(defaultEffectiveAt).toJava,
    )
    contract(
      identifier = templateId,
      contractId = new validatorLicenseCodegen.ValidatorLicense.ContractId(nextCid()),
      payload = template,
    )
  }

  protected def validatorRewardCoupon(
      round: Int,
      user: PartyId,
      amount: Numeric.Numeric = numeric(1.0),
  ): Contract[
    amuletCodegen.ValidatorRewardCoupon.ContractId,
    amuletCodegen.ValidatorRewardCoupon,
  ] =
    contract(
      identifier = amuletCodegen.ValidatorRewardCoupon.TEMPLATE_ID_WITH_PACKAGE_ID,
      contractId = new amuletCodegen.ValidatorRewardCoupon.ContractId(nextCid()),
      payload = new amuletCodegen.ValidatorRewardCoupon(
        dsoParty.toProtoPrimitive,
        user.toProtoPrimitive,
        amount,
        new Round(round),
      ),
    )

  protected def validatorFaucetCoupon(validator: PartyId, round: Long = 1L) = {
    contract(
      identifier = validatorLicenseCodegen.ValidatorFaucetCoupon.TEMPLATE_ID_WITH_PACKAGE_ID,
      contractId = new validatorLicenseCodegen.ValidatorFaucetCoupon.ContractId(nextCid()),
      payload = new validatorLicenseCodegen.ValidatorFaucetCoupon(
        dsoParty.toProtoPrimitive,
        validator.toProtoPrimitive,
        new Round(round),
      ),
    )
  }

  protected def validatorLivenessActivityRecord(validator: PartyId, round: Long = 1L) = {
    contract(
      identifier =
        validatorLicenseCodegen.ValidatorLivenessActivityRecord.TEMPLATE_ID_WITH_PACKAGE_ID,
      contractId =
        new validatorLicenseCodegen.ValidatorLivenessActivityRecord.ContractId(nextCid()),
      payload = new validatorLicenseCodegen.ValidatorLivenessActivityRecord(
        dsoParty.toProtoPrimitive,
        validator.toProtoPrimitive,
        new Round(round),
      ),
    )
  }

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
        dsoParty.toProtoPrimitive,
        "description",
      )
    val payData = new subCodegen.SubscriptionPayData(
      new paymentCodegen.PaymentAmount(numeric(amount.bigDecimal), paymentCodegen.Unit.AMULETUNIT),
      new RelTime(1L),
      new RelTime(1L),
    )
    val template = new subCodegen.SubscriptionInitialPayment(
      subscriptionData,
      payData,
      numeric(amount.bigDecimal),
      new amuletCodegen.LockedAmulet.ContractId(nextCid()),
      new Round(1L),
      reference,
    )
    contract(
      subCodegen.SubscriptionInitialPayment.TEMPLATE_ID_WITH_PACKAGE_ID,
      paymentId,
      template,
    )
  }

  protected def featuredAppRight(
      providerParty: PartyId,
      contractId: String = nextCid(),
  ) = {
    val template = new FeaturedAppRight(dsoParty.toProtoPrimitive, providerParty.toProtoPrimitive)
    contract(
      FeaturedAppRight.TEMPLATE_ID_WITH_PACKAGE_ID,
      new FeaturedAppRight.ContractId(contractId),
      template,
    )
  }

  protected def svRewardCoupon(
      round: Int,
      sv: PartyId,
      beneficiary: PartyId,
      weight: Long,
      contractId: String = nextCid(),
  ): Contract[amuletCodegen.SvRewardCoupon.ContractId, amuletCodegen.SvRewardCoupon] =
    contract(
      identifier = amuletCodegen.SvRewardCoupon.TEMPLATE_ID_WITH_PACKAGE_ID,
      contractId = new amuletCodegen.SvRewardCoupon.ContractId(contractId),
      payload = new amuletCodegen.SvRewardCoupon(
        dsoParty.toProtoPrimitive,
        sv.toProtoPrimitive,
        beneficiary.toProtoPrimitive,
        new Round(round),
        weight,
      ),
    )

  protected def svRewardState(
      svName: String,
      rewardState: RewardState = new RewardState(0L, 0L, new Round(0L), 0L),
      contractId: String = nextCid(),
  ): Contract[SvRewardState.ContractId, SvRewardState] =
    contract(
      identifier = SvRewardState.TEMPLATE_ID_WITH_PACKAGE_ID,
      contractId = new SvRewardState.ContractId(contractId),
      payload = new SvRewardState(
        dsoParty.toProtoPrimitive,
        svName,
        rewardState,
      ),
    )

  protected def mkVoteRequestResult(
      voteRequestContract: Contract[VoteRequest.ContractId, VoteRequest],
      effectiveAt: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS),
  ): DsoRules_CloseVoteRequestResult = new DsoRules_CloseVoteRequestResult(
    voteRequestContract.payload,
    Instant.now().truncatedTo(ChronoUnit.MICROS),
    util.List.of(),
    util.List.of(),
    new VRO_Accepted(effectiveAt),
  )

  protected def mkCloseVoteRequest(
      requestId: VoteRequest.ContractId
  ): DamlRecord = {
    new DsoRules_CloseVoteRequest(
      requestId,
      Optional.empty(),
      Optional.empty(),
    ).toValue
  }

  protected lazy val addUser666Action = new ARC_DsoRules(
    new SRARC_AddSv(
      new DsoRules_AddSv(
        userParty(666).toProtoPrimitive,
        "user666",
        10_000L,
        "user666ParticipantId",
        new Round(1L),
      )
    )
  )

  protected def voteRequest(
      requester: PartyId,
      votes: Seq[Vote],
      expiry: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(3600L),
      effectiveAt: Optional[Instant] = Optional.empty(),
      action: ActionRequiringConfirmation = addUser666Action,
  ): Contract[VoteRequest.ContractId, VoteRequest] = {
    val cid = new VoteRequest.ContractId(nextCid())
    val template = new VoteRequest(
      dsoParty.toProtoPrimitive,
      requester.toProtoPrimitive,
      action,
      new Reason("https://www.example.com", ""),
      expiry,
      votes.map(e => (e.sv, e)).toMap.asJava,
      Optional.of(cid),
      effectiveAt,
    )

    contract(
      VoteRequest.TEMPLATE_ID_WITH_PACKAGE_ID,
      cid,
      template,
    )
  }

  protected def transferPreapproval(
      receiver: PartyId,
      provider: PartyId,
      validFrom: CantonTimestamp,
      expiresAt: CantonTimestamp,
  ): Contract[
    amuletrulesCodegen.TransferPreapproval.ContractId,
    amuletrulesCodegen.TransferPreapproval,
  ] = {
    val templateId = amuletrulesCodegen.TransferPreapproval.TEMPLATE_ID_WITH_PACKAGE_ID
    val template = new amuletrulesCodegen.TransferPreapproval(
      dsoParty.toProtoPrimitive,
      receiver.toProtoPrimitive,
      provider.toProtoPrimitive,
      validFrom.toInstant,
      validFrom.toInstant,
      expiresAt.toInstant,
    )
    contract(
      identifier = templateId,
      contractId = new amuletrulesCodegen.TransferPreapproval.ContractId(nextCid()),
      payload = template,
    )
  }

  protected def transferCommandCounter(
      sender: PartyId,
      nextNonce: Long,
  ): Contract[
    externalpartyamuletrulesCodegen.TransferCommandCounter.ContractId,
    externalpartyamuletrulesCodegen.TransferCommandCounter,
  ] = {
    val templateId =
      externalpartyamuletrulesCodegen.TransferCommandCounter.TEMPLATE_ID_WITH_PACKAGE_ID
    val template = new externalpartyamuletrulesCodegen.TransferCommandCounter(
      dsoParty.toProtoPrimitive,
      sender.toProtoPrimitive,
      nextNonce,
    )
    contract(
      identifier = templateId,
      contractId = new externalpartyamuletrulesCodegen.TransferCommandCounter.ContractId(nextCid()),
      payload = template,
    )
  }

  protected def toCreatedEvent(
      contract: Contract[?, ?],
      signatories: Seq[PartyId] = Seq.empty,
      observers: Seq[PartyId] = Seq.empty,
      implementedInterfaces: Map[Identifier, DamlRecord] = Map.empty,
      failedInterfaces: Map[Identifier, com.google.rpc.Status] = Map.empty,
  ): CreatedEvent = {
    new CreatedEvent(
      Seq.empty[String].asJava,
      0,
      1,
      contract.identifier,
      getPackageName(contract.identifier),
      contract.contractId.contractId,
      contract.payload.toValue,
      contract.createdEventBlob,
      implementedInterfaces.asJava,
      failedInterfaces.asJava,
      None.toJava,
      signatories.map(_.toProtoPrimitive).asJava,
      observers.map(_.toProtoPrimitive).asJava,
      contract.createdAt,
      false,
      contract.identifier.getPackageId,
    )
  }

  protected def toArchivedEvent[TCid <: ContractId[T], T](
      contract: Contract[TCid, T],
      implementedInterfaces: Seq[Identifier] = Seq.empty,
  ): ExercisedEvent = {
    new ExercisedEvent(
      Seq.empty.asJava,
      0,
      1,
      contract.identifier,
      getPackageName(contract.identifier),
      None.toJava,
      contract.contractId.contractId,
      "DummyChoiceName",
      damlUnit.getInstance(),
      Seq.empty.asJava,
      true,
      1,
      damlUnit.getInstance(),
      implementedInterfaces.asJava,
      false,
    )
  }

  protected def toActiveContract[TCid <: ContractId[T], T](
      domain: SynchronizerId,
      contract: Contract[TCid, T],
      counter: Long,
  ): ActiveContract =
    ActiveContract(
      domain,
      toCreatedEvent(contract, Seq(dsoParty)),
      counter,
    )

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
      Seq.empty.asJava,
      0,
      1,
      templateId,
      getPackageName(templateId),
      interfaceId.toJava,
      contractId,
      choice,
      argument,
      Seq.empty.asJava,
      consuming,
      1,
      result,
      Seq.empty.asJava,
      false,
    )
  }

  protected def withNodeId(
      event: Event,
      nodeId: Int,
  ): Event = event match {
    case created: CreatedEvent =>
      new CreatedEvent(
        created.getWitnessParties,
        created.getOffset,
        nodeId,
        created.getTemplateId,
        created.getPackageName,
        created.getContractId,
        created.getArguments,
        created.getCreatedEventBlob,
        created.getInterfaceViews,
        created.getFailedInterfaceViews,
        created.getContractKey,
        created.getSignatories,
        created.getObservers,
        created.createdAt,
        created.isAcsDelta(),
        created.getTemplateId.getPackageId,
      )
    case exercised: ExercisedEvent =>
      new ExercisedEvent(
        exercised.getWitnessParties,
        exercised.getOffset,
        nodeId,
        exercised.getTemplateId,
        exercised.getPackageName,
        exercised.getInterfaceId,
        exercised.getContractId,
        exercised.getChoice,
        exercised.getChoiceArgument,
        exercised.getActingParties,
        exercised.isConsuming,
        nodeId,
        exercised.getExerciseResult,
        exercised.getImplementedInterfaces,
        exercised.isAcsDelta(),
      )
    case _ => sys.error("Catch-all required because of no exhaustiveness checks with Java")
  }

  protected def withlastDescendedNodeid[E <: Event](event: E, lastDescendedNodeId: Int): E = {
    event match {
      case exercised: ExercisedEvent =>
        new ExercisedEvent(
          exercised.getWitnessParties,
          exercised.getOffset,
          exercised.getNodeId,
          exercised.getTemplateId,
          exercised.getPackageName,
          exercised.getInterfaceId,
          exercised.getContractId,
          exercised.getChoice,
          exercised.getChoiceArgument,
          exercised.getActingParties,
          exercised.isConsuming,
          lastDescendedNodeId,
          exercised.getExerciseResult,
          Seq.empty.asJava,
          exercised.isAcsDelta(),
        ).asInstanceOf[E]
      case e => e
    }
  }

  protected lazy val dummyDomain = StoreTestBase.dummyDomain

  protected val dummy2Domain = SynchronizerId.tryFromString("dummy2::domain")

  protected val domainMigrationId = 0L

  protected val nextDomainMigrationId = 1L

  protected val defaultEffectiveAt: Instant = CantonTimestamp.Epoch.toInstant

  protected def toIncompleteUnassign(
      contract: Contract[?, ?],
      unassignId: String,
      source: SynchronizerId,
      target: SynchronizerId,
      counter: Long,
      implementedInterfaces: Map[Identifier, DamlRecord],
  ): IncompleteReassignmentEvent.Unassign = IncompleteReassignmentEvent.Unassign(
    toUnassignEvent(
      contract.contractId,
      unassignId,
      source,
      target,
      counter,
    ),
    toCreatedEvent(contract, Seq(dsoParty), implementedInterfaces = implementedInterfaces),
  )

  protected def toIncompleteAssign(
      contract: Contract[?, ?],
      unassignId: String,
      source: SynchronizerId,
      target: SynchronizerId,
      counter: Long,
      implementedInterfaces: Map[Identifier, DamlRecord],
  ): IncompleteReassignmentEvent.Assign = IncompleteReassignmentEvent.Assign(
    toAssignEvent(
      contract,
      unassignId,
      source,
      target,
      counter,
      implementedInterfaces,
    )
  )

  protected def toUnassignEvent(
      contractId: ContractId[?],
      unassignId: String,
      source: SynchronizerId,
      target: SynchronizerId,
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

  protected def toAssignEvent(
      contract: Contract[?, ?],
      unassignId: String,
      source: SynchronizerId,
      target: SynchronizerId,
      counter: Long,
      implementedInterfaces: Map[Identifier, DamlRecord],
  ): ReassignmentEvent.Assign = ReassignmentEvent.Assign(
    unassignId = unassignId,
    submitter = userParty(1),
    source = source,
    target = target,
    createdEvent =
      toCreatedEvent(contract, Seq(dsoParty), implementedInterfaces = implementedInterfaces),
    counter = counter,
  )

  protected def mkValidatorRewardCoupon(i: Int) = validatorRewardCoupon(i, userParty(i))

  private var offsetCounter: Long = 0L

  protected def nextOffset(): Long = blocking {
    synchronized {
      val offset = offsetCounter
      offsetCounter += 1
      offset
    }
  }

  protected def mkCreateTx[TCid <: ContractId[T], T](
      offset: Long,
      createRequests: Seq[Contract[TCid, T]],
      effectiveAt: Instant,
      createdEventSignatories: Seq[PartyId],
      synchronizerId: SynchronizerId,
      workflowId: String,
      recordTime: Instant = defaultEffectiveAt,
      createdEventObservers: Seq[PartyId] = Seq.empty,
      updateId: String = nextUpdateId(),
  ): Transaction = mkCreateTxWithInterfaces(
    offset,
    createRequests.map(cr =>
      (cr, Map.empty[Identifier, DamlRecord], Map.empty[Identifier, com.google.rpc.Status])
    ),
    effectiveAt,
    createdEventSignatories,
    synchronizerId,
    workflowId,
    recordTime,
    createdEventObservers,
    updateId,
  )

  protected def mkCreateTxWithInterfaces(
      offset: Long,
      createRequests: Seq[
        (Contract[?, ?], Map[Identifier, DamlRecord], Map[Identifier, com.google.rpc.Status])
      ],
      effectiveAt: Instant,
      createdEventSignatories: Seq[PartyId],
      synchronizerId: SynchronizerId,
      workflowId: String,
      recordTime: Instant = defaultEffectiveAt,
      createdEventObservers: Seq[PartyId] = Seq.empty,
      updateId: String = nextUpdateId(),
  ): Transaction = mkTx(
    offset,
    createRequests.map[Event] { case (contract, implementedInterfaces, failedInterfaces) =>
      toCreatedEvent(
        contract,
        createdEventSignatories,
        createdEventObservers,
        implementedInterfaces,
        failedInterfaces,
      )
    },
    synchronizerId,
    effectiveAt,
    workflowId,
    recordTime = recordTime,
    updateId = updateId,
  )

  protected def acsImportEntryToActiveContract(entry: StoreTestBase.AcsImportEntry) = entry match {
    case StoreTestBase.AcsImportEntry(contract, domain, counter, implementedInterfaces) =>
      ActiveContract(
        domain,
        toCreatedEvent(contract, Seq(dsoParty), implementedInterfaces = implementedInterfaces),
        counter,
      )
  }

  protected def acs(
      acs: Seq[StoreTestBase.AcsImportEntry] = Seq.empty,
      incompleteOut: Seq[StoreTestBase.AcsImportIncompleteEntry] = Seq.empty,
      incompleteIn: Seq[StoreTestBase.AcsImportIncompleteEntry] = Seq.empty,
      acsOffset: Long = nextOffset(),
  )(implicit store: MultiDomainAcsStore): Future[Unit] = for {
    _ <- store.testIngestionSink.ingestAcs(
      acsOffset,
      acs.map(acsImportEntryToActiveContract),
      incompleteOut.map {
        case StoreTestBase.AcsImportIncompleteEntry(
              c,
              sourceDomain,
              targetDomain,
              tfid,
              counter,
              implementedInterfaces,
            ) =>
          toIncompleteUnassign(
            c,
            tfid,
            sourceDomain,
            targetDomain,
            counter,
            implementedInterfaces,
          )
      },
      incompleteIn.map {
        case StoreTestBase.AcsImportIncompleteEntry(
              c,
              sourceDomain,
              targetDomain,
              tfid,
              counter,
              implementedInterfaces,
            ) =>
          toIncompleteAssign(
            c,
            tfid,
            sourceDomain,
            targetDomain,
            counter,
            implementedInterfaces,
          )
      },
    )
  } yield ()

  protected def initWithAcs(
      activeContracts: Seq[StoreTestBase.AcsImportEntry] = Seq.empty,
      incompleteOut: Seq[StoreTestBase.AcsImportIncompleteEntry] = Seq.empty,
      incompleteIn: Seq[StoreTestBase.AcsImportIncompleteEntry] = Seq.empty,
      acsOffset: Long = nextOffset(),
  )(implicit store: MultiDomainAcsStore): Future[Unit] = for {
    _ <- store.testIngestionSink.initialize()
    _ <- acs(activeContracts, incompleteOut, incompleteIn, acsOffset)
  } yield ()

  protected val RepeatedIngestionWarningMessage =
    "This is expected if the SQL query was automatically retried after a transient database error"

  /** Runs the given Future, suppressing warnings generated by repeated database actions.
    *
    * Context: UpdateHistory and DbMultidomainAcsStore use SQL queries that are idempotent,
    * but print a warning if the query is repeated.
    * This is expected in DbTest, where we repeat all writes to ensure idempotency.
    * This is NOT expected in integration tests and production environments, where the warning
    * most likely indicates a real bug, except for the rare case where the query was retried
    * because of a transient database connection problem.
    * We could also ignore the warning in `project/ignore-patterns/canton_network_test_log.ignore.txt`,
    * but that would suppress the warnings in integration tests, and currently we'd rather catch real
    * bugs at the cost of occasionally failing a test due to transient database connection problems.
    */
  def withoutRepeatedIngestionWarning[A](f: => Future[A], maxCount: Int = 1): Future[A] = {
    def isRepeatedIngestionWarning(entry: LogEntry): Boolean =
      entry.message.contains(RepeatedIngestionWarningMessage)

    loggerFactory.assertLogsSeq(SuppressionRule.Level(Level.WARN))(
      f,
      entries => {
        val (expected, unexpected) = entries.partition(isRepeatedIngestionWarning)
        expected.length should be <= maxCount
        unexpected should be(empty)
      },
    )
  }

  implicit class WithTestIngestionSink(underlying: MultiDomainAcsStore.HasIngestionSink) {
    val testIngestionSink = new TestIngestionSink(underlying.ingestionSink)
  }

  class TestIngestionSink(val underlying: MultiDomainAcsStore.IngestionSink)
      extends MultiDomainAcsStore.IngestionSink {

    override def ingestionFilter: MultiDomainAcsStore.IngestionFilter =
      underlying.ingestionFilter
    override def initialize()(implicit traceContext: TraceContext) =
      underlying.initialize()

    override def ingestAcsStreamInBatches(
        source: Source[Seq[BaseLedgerConnection.ActiveContractsItem], NotUsed],
        offset: Long,
    )(implicit tc: TraceContext, mat: Materializer): Future[Unit] =
      withoutRepeatedIngestionWarning(underlying.ingestAcsStreamInBatches(source, offset)(tc, mat))

    override def ingestUpdateBatch(batch: NonEmptyList[TreeUpdateOrOffsetCheckpoint])(implicit
        traceContext: TraceContext
    ) = withoutRepeatedIngestionWarning(
      underlying.ingestUpdateBatch(batch)(traceContext)
    )
  }

  // Convenient syntax to make the tests easy to read.
  protected implicit class DomainSyntax(private val domain: SynchronizerId) {

    def create[TCid <: ContractId[T], T, Sink](
        c: Contract[TCid, T],
        offset: Long = nextOffset(),
        txEffectiveAt: Instant = defaultEffectiveAt,
        createdEventSignatories: Seq[PartyId] = Seq(dsoParty),
        workflowId: String = "",
        recordTime: Instant = defaultEffectiveAt,
        createdEventObservers: Seq[PartyId] = Seq.empty,
        implementedInterfaces: Map[Identifier, DamlRecord] = Map.empty,
        failedInterfaces: Map[Identifier, com.google.rpc.Status] = Map.empty,
    )(implicit store: HasIngestionSink): Future[Transaction] = {
      val tx = mkCreateTxWithInterfaces(
        offset,
        Seq((c, implementedInterfaces, failedInterfaces)),
        txEffectiveAt,
        createdEventSignatories,
        domain,
        workflowId,
        recordTime,
        createdEventObservers,
      )

      store.testIngestionSink
        .ingestUpdate(
          domain,
          TransactionTreeUpdate(tx),
        )
        .map(_ => tx)
    }

    def createMulti[TCid <: ContractId[T], T](
        c: Contract[TCid, T],
        offset: Long = nextOffset(),
        txEffectiveAt: Instant = defaultEffectiveAt,
        createdEventSignatories: Seq[PartyId] = Seq(dsoParty),
        workflowId: String = "",
        recordTime: Instant = defaultEffectiveAt,
    )(implicit stores: Seq[HasIngestionSink]): Future[Transaction] = {
      val tx = mkCreateTx(
        offset,
        Seq(c),
        txEffectiveAt,
        createdEventSignatories,
        domain,
        workflowId,
        recordTime,
      )
      val txUpdate = TransactionTreeUpdate(tx)
      // Note: runs the futures sequentially in order to get deterministic tests
      stores
        .foldLeft(Future.unit) { (acc, store) =>
          for {
            _ <- acc
            _ <- store.testIngestionSink.ingestUpdate(domain, txUpdate)
          } yield ()
        }
        .map(_ => tx)
    }

    def archive[TCid <: ContractId[T], T](
        c: Contract[TCid, T],
        txEffectiveAt: Instant = defaultEffectiveAt,
        implementedInterfaces: Seq[Identifier] = Seq.empty,
    )(implicit store: HasIngestionSink): Future[Transaction] = {
      val tx =
        mkTx(nextOffset(), Seq(toArchivedEvent(c, implementedInterfaces)), domain, txEffectiveAt)
      store.testIngestionSink
        .ingestUpdate(
          domain,
          TransactionTreeUpdate(
            tx
          ),
        )
        .map(_ => tx)
    }

    def ingest(
        makeTx: Long => Transaction
    )(implicit store: HasIngestionSink): Future[Transaction] = {
      val tx = makeTx(nextOffset())
      store.testIngestionSink
        .ingestUpdate(
          domain,
          TransactionTreeUpdate(
            tx
          ),
        )
        .map(_ => tx)
    }

    def ingestMulti(
        makeTx: Long => Transaction
    )(implicit stores: Seq[HasIngestionSink]): Future[Transaction] = {
      val tx = makeTx(nextOffset())
      val txUpdate = TransactionTreeUpdate(tx)
      // Note: runs the futures sequentially in order to get deterministic tests
      stores
        .foldLeft(Future.unit) { (acc, store) =>
          for {
            _ <- acc
            _ <- store.testIngestionSink.ingestUpdate(domain, txUpdate)
          } yield ()
        }
        .map(_ => tx)
    }

    def unassign[TCid <: ContractId[T], T](
        contractAndDomain: (Contract[TCid, T], SynchronizerId),
        reassignmentId: String,
        counter: Long,
        recordTime: CantonTimestamp = CantonTimestamp.Epoch,
        offset: Long = nextOffset(),
    )(implicit store: HasIngestionSink): Future[Reassignment[ReassignmentEvent.Unassign]] = {
      val reassignment = mkReassignment(
        offset,
        toUnassignEvent(
          contractAndDomain._1.contractId,
          reassignmentId,
          domain,
          contractAndDomain._2,
          counter,
        ),
        recordTime,
      )

      store.testIngestionSink
        .ingestUpdate(
          domain,
          ReassignmentUpdate(reassignment),
        )
        .map(_ => reassignment)
    }

    def assign[TCid <: ContractId[T], T](
        contractAndDomain: (Contract[TCid, T], SynchronizerId),
        reassignmentId: String,
        counter: Long,
        recordTime: CantonTimestamp = CantonTimestamp.Epoch,
        offset: Long = nextOffset(),
        implementedInterfaces: Map[Identifier, DamlRecord] = Map.empty,
    )(implicit store: HasIngestionSink): Future[Reassignment[ReassignmentEvent.Assign]] = {
      val reassignment = mkReassignment(
        offset,
        toAssignEvent(
          contractAndDomain._1,
          reassignmentId,
          contractAndDomain._2,
          domain,
          counter,
          implementedInterfaces,
        ),
        recordTime,
      )

      store.testIngestionSink
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
        offset: Long = nextOffset(),
        txEffectiveAt: Instant = defaultEffectiveAt,
        recordTime: Instant = defaultEffectiveAt,
    )(implicit store: HasIngestionSink): Future[Transaction] = {
      val tx = mkTx(
        offset,
        Seq(mkExercise(contract, interfaceId, choiceName, choiceArgument, exerciseResult)),
        domain,
        txEffectiveAt,
        recordTime = recordTime,
      )
      store.testIngestionSink
        .ingestUpdate(
          domain,
          TransactionTreeUpdate(tx),
        )
        .map(_ => tx)
    }
  }

  private def nextUpdateId(): String = java.util.UUID.randomUUID().toString.replace("-", "")

  protected def mkTx(
      offset: Long,
      events: Seq[Event],
      synchronizerId: SynchronizerId,
      effectiveAt: Instant = defaultEffectiveAt,
      workflowId: String = "",
      commandId: String = "",
      recordTime: Instant = defaultEffectiveAt,
      updateId: String = nextUpdateId(),
  ): Transaction = {
    val eventsWithId = events.zipWithIndex.map { case (e, i) =>
      withNodeId(e, i)
    }
    new Transaction(
      updateId,
      commandId,
      workflowId,
      effectiveAt,
      eventsWithId.asJava,
      offset,
      synchronizerId.toProtoPrimitive,
      TraceContextOuterClass.TraceContext.getDefaultInstance,
      recordTime,
      ByteString.EMPTY,
    )
  }

  protected def mkExerciseTx(
      offset: Long,
      root: ExercisedEvent,
      children: Seq[Event],
      synchronizerId: SynchronizerId,
      effectiveAt: Instant = defaultEffectiveAt,
  ): Transaction = {
    val updateId = nextUpdateId()
    val childrenWithId = children.zipWithIndex.map { case (e, i) =>
      withNodeId(e, i + 1) // account for root node id
    }
    val rootWithId = {
      withlastDescendedNodeid(
        withNodeId(
          root,
          0,
        ),
        childrenWithId.map(_.getNodeId).maxOption.map(_.intValue()).getOrElse(0),
      )
    }
    val events = rootWithId +: childrenWithId
    new Transaction(
      updateId,
      "",
      "",
      effectiveAt,
      events.asJava,
      offset,
      synchronizerId.toProtoPrimitive,
      TraceContextOuterClass.TraceContext.getDefaultInstance,
      effectiveAt, // we equate record time and effectiveAt for simplicity
      ByteString.EMPTY,
    )
  }

  protected def mkReassignment[T <: ReassignmentEvent](
      offset: Long,
      event: T,
      recordTime: CantonTimestamp = CantonTimestamp.Epoch,
  ): Reassignment[T] =
    Reassignment(
      updateId = nextUpdateId(),
      offset = offset,
      recordTime = recordTime,
      event = event,
    )

  protected def mkExercise[TCid <: ContractId[T], T](
      contract: Contract[TCid, T],
      interfaceId: Option[Identifier],
      choiceName: String,
      choiceArgument: damlValue,
      exerciseResult: damlValue,
  ): Event =
    new ExercisedEvent(
      Seq.empty.asJava,
      0,
      1,
      contract.identifier,
      getPackageName(contract.identifier),
      interfaceId.toJava,
      contract.contractId.contractId,
      choiceName,
      choiceArgument,
      Seq.empty.asJava,
      false,
      1,
      exerciseResult,
      Seq.empty.asJava,
      false,
    )

  /** Convenience wrapper that autoinfers the payloadValue assuming
    * that the contract was created using the version we're decoding to.
    */
  protected def contract[TCid, T](
      identifier: Identifier,
      contractId: TCid & ContractId[?],
      payload: T & CodegenDamlRecord[?],
  ): Contract[TCid, T] = Contract(
    identifier,
    contractId,
    payload,
    ByteString.EMPTY,
    Instant.EPOCH,
  )

  // Numbers in daml have 10 decimal places
  protected lazy val damlNumericScale: Numeric.Scale = Numeric.Scale.assertFromInt(10)

  /** A list of special numeric values of the given scale,
    * suitable for testing whether serialization/deserialization code preserves the exact values
    */
  protected def specialNumericValues(
      scale: Numeric.Scale = damlNumericScale
  ): Seq[Numeric.Numeric] = {
    Seq(
      // 0 (zero)
      Numeric.assertFromBigDecimal(scale, java.math.BigDecimal.ZERO),
      // +999999.999999 (maximum value)
      Numeric.maxValue(scale),
      // -999999.999999 (minimum value)
      Numeric.minValue(scale),
      // +0.0000001 (smallest positive value)
      Numeric.assertFromBigDecimal(
        scale,
        new java.math.BigDecimal(java.math.BigInteger.ONE, scale),
      ),
      // -0.0000001 (largest negative value)
      Numeric.assertFromBigDecimal(
        scale,
        new java.math.BigDecimal(java.math.BigInteger.ONE.negate(), scale),
      ),
      // +0.3333333 (tests for decimal encoding instead of a binary one)
      Numeric.assertFromString("0." + "3".repeat(scale)),
      // -0.3333333 (tests for decimal encoding instead of a binary one)
      Numeric.assertFromString("-0." + "3".repeat(scale)),
    )
  }
}

object StoreTestBase {

  implicit class IngestSingleElementSink(underlying: MultiDomainAcsStore.IngestionSink) {
    final def ingestUpdate(synchronizerId: SynchronizerId, update: TreeUpdate)(implicit
        traceContext: TraceContext
    ): Future[Unit] =
      underlying.ingestUpdateBatch(
        NonEmptyList.of(TreeUpdateOrOffsetCheckpoint.Update(update, synchronizerId))
      )
  }

  val dummyDomain = SynchronizerId.tryFromString("dummy::domain")

  object TxLogEntry extends StoreErrors {

    def encode(entry: TestTxLogEntry): (String3, String) = {
      import scalapb.json4s.JsonFormat
      val entryType = EntryType.TestTxLogEntry
      val jsonValue = JsonFormat.toJsonString(entry)
      (entryType, jsonValue)
    }

    def decode(entryType: String3, json: String): TestTxLogEntry = {
      import scalapb.json4s.JsonFormat.fromJsonString as from
      try {
        entryType match {
          case EntryType.TestTxLogEntry => from[TestTxLogEntry](json)
          case _ => throw txDecodingFailed()
        }
      } catch {
        case _: RuntimeException => throw txDecodingFailed()
      }
    }

    object EntryType {
      val TestTxLogEntry: String3 = String3.tryCreate("tte")
    }
  }

  object TestTxLogStoreParser extends TxLogStore.Parser[TestTxLogEntry] {

    private def parseCreatedEvent(event: CreatedEvent): TestTxLogEntry = {
      // Note: amulets and app reward coupons are heavily used in MultiDomainAcsStoreTest
      event match {
        case AmuletCreate(amulet) =>
          TestTxLogEntry(
            eventId = EventId.prefixedFromUpdateIdAndNodeId("updateId", event.getNodeId),
            contractId = event.getContractId,
            numericValue = amulet.payload.amount.initialAmount,
          )
        case AppRewardCreate(amulet) =>
          TestTxLogEntry(
            eventId = EventId.prefixedFromUpdateIdAndNodeId("updateId", event.getNodeId),
            contractId = event.getContractId,
            numericValue = amulet.payload.amount,
          )
        case _ =>
          TestTxLogEntry(
            eventId = EventId.prefixedFromUpdateIdAndNodeId("updateId", event.getNodeId),
            contractId = event.getContractId,
            numericValue = BigDecimal(0),
          )
      }
    }

    override def tryParse(tx: Transaction, domain: SynchronizerId)(implicit
        tc: TraceContext
    ): Seq[TestTxLogEntry] = {
      Trees.foldTree(tx, Seq.empty[TestTxLogEntry])(
        onCreate = (res, event, _) => res :+ parseCreatedEvent(event),
        onExercise = (res, _, _) => res,
      )
    }

    override def error(
        offset: Long,
        eventId: String,
        synchronizerId: SynchronizerId,
    ): Option[TestTxLogEntry] = None
  }

  val testTxLogConfig = new TxLogStore.Config[TestTxLogEntry] {
    override def parser
        : org.lfdecentralizedtrust.splice.store.StoreTestBase.TestTxLogStoreParser.type =
      TestTxLogStoreParser
    override def entryToRow: org.lfdecentralizedtrust.splice.store.TestTxLogEntry => Option[
      org.lfdecentralizedtrust.splice.store.db.TxLogRowData.TxLogRowDataWithoutIndices.type
    ] =
      _ => Some(TxLogRowData.noIndices)
    override def encodeEntry = StoreTestBase.TxLogEntry.encode
    override def decodeEntry = StoreTestBase.TxLogEntry.decode
  }

  case class AcsImportEntry(
      contract: Contract[?, ?],
      synchronizerId: SynchronizerId,
      counter: Long,
      implementedInterfaces: Map[Identifier, DamlRecord] = Map.empty,
  )

  case class AcsImportIncompleteEntry(
      contract: Contract[?, ?],
      sourceDomain: SynchronizerId,
      targetDomain: SynchronizerId,
      tfid: String,
      counter: Long,
      implementedInterfaces: Map[Identifier, DamlRecord] = Map.empty,
  )
}
