package com.daml.network.scan.store

import com.daml.ledger.javaapi.data.{TreeEvent, *}
import com.daml.network.store.TxLogStore
import com.digitalasset.canton.topology.PartyId
import java.time.Instant
import com.digitalasset.canton.config.CantonRequireTypes.String3
import com.digitalasset.canton.topology.DomainId

sealed trait TxLogIndexRecord extends TxLogStore.IndexRecord {
  val companion: TxLogIndexRecordCompanion
}

sealed trait TxLogIndexRecordCompanion {
  val shortType: String

  def dbType: String3 = String3.tryCreate(shortType)
}

object TxLogIndexRecord {

  final case class ErrorIndexRecord(
      offset: String,
      eventId: String,
      domainId: DomainId,
  ) extends TxLogIndexRecord {
    override def optOffset = Some(offset)

    override def acsContractId: Option[codegen.ContractId[_]] = None

    override val companion: TxLogIndexRecordCompanion = ErrorIndexRecord
  }

  object ErrorIndexRecord extends TxLogIndexRecordCompanion {
    override val shortType: String = "err"
  }

  final case class OpenMiningRoundIndexRecord(
      offset: String,
      eventId: String,
      domainId: DomainId,
      round: Long,
  ) extends TxLogIndexRecord {
    override def optOffset = Some(offset)

    override def acsContractId: Option[codegen.ContractId[_]] = None

    override val companion: TxLogIndexRecordCompanion = OpenMiningRoundIndexRecord
  }

  object OpenMiningRoundIndexRecord extends TxLogIndexRecordCompanion {
    override val shortType: String = "omr"
  }

  final case class ClosedMiningRoundIndexRecord(
      offset: String,
      eventId: String,
      domainId: DomainId,
      round: Long,
      effectiveAt: Instant,
  ) extends TxLogIndexRecord {
    override def optOffset = Some(offset)

    override def acsContractId: Option[codegen.ContractId[_]] = None

    override val companion: TxLogIndexRecordCompanion = ClosedMiningRoundIndexRecord
  }

  object ClosedMiningRoundIndexRecord extends TxLogIndexRecordCompanion {
    override val shortType: String = "cmr"
  }

  sealed trait RewardIndexRecord extends TxLogIndexRecord {
    def party: PartyId
    def amount: BigDecimal
    def round: Long
  }

  final case class AppRewardIndexRecord(
      offset: String,
      eventId: String,
      domainId: DomainId,
      round: Long,
      party: PartyId,
      amount: BigDecimal,
  ) extends RewardIndexRecord {
    override def optOffset = Some(offset)

    override def acsContractId: Option[codegen.ContractId[_]] = None

    override val companion: TxLogIndexRecordCompanion = AppRewardIndexRecord
  }

  object AppRewardIndexRecord extends TxLogIndexRecordCompanion {
    override val shortType: String = "are"
  }

  final case class ValidatorRewardIndexRecord(
      offset: String,
      eventId: String,
      domainId: DomainId,
      round: Long,
      party: PartyId,
      amount: BigDecimal,
  ) extends RewardIndexRecord {
    override def optOffset = Some(offset)

    override def acsContractId: Option[codegen.ContractId[_]] = None

    override val companion: TxLogIndexRecordCompanion = ValidatorRewardIndexRecord
  }

  object ValidatorRewardIndexRecord extends TxLogIndexRecordCompanion {
    override val shortType: String = "vre"
  }

  final case class ExtraTrafficPurchaseIndexRecord(
      offset: String,
      eventId: String,
      domainId: DomainId,
      round: Long,
      validator: PartyId,
      trafficPurchased: Long,
      ccSpent: BigDecimal,
  ) extends TxLogIndexRecord {
    override def optOffset = Some(offset)

    override def acsContractId: Option[codegen.ContractId[_]] = None

    override val companion: TxLogIndexRecordCompanion = ExtraTrafficPurchaseIndexRecord
  }

  object ExtraTrafficPurchaseIndexRecord extends TxLogIndexRecordCompanion {
    override val shortType: String = "etp"
  }

  final case class BalanceChangeIndexRecord(
      optOffset: Option[String],
      eventId: String,
      domainId: DomainId,
      round: Long,
      changeToInitialAmountAsOfRoundZero: BigDecimal,
      changeToHoldingFeesRate: BigDecimal,
  ) extends TxLogIndexRecord {
    override val companion: TxLogIndexRecordCompanion = BalanceChangeIndexRecord
    override def acsContractId: Option[codegen.ContractId[_]] = None
  }

  object BalanceChangeIndexRecord extends TxLogIndexRecordCompanion {
    override val shortType: String = "bac"
  }

  final case class TransactionIndexRecord(
      offset: String,
      eventId: String,
      domainId: DomainId,
  ) extends TxLogIndexRecord {
    override def optOffset = Some(offset)

    override def acsContractId: Option[codegen.ContractId[_]] = None

    override val companion: TxLogIndexRecordCompanion = TransactionIndexRecord
  }

  object TransactionIndexRecord extends TxLogIndexRecordCompanion {
    override val shortType: String = "rar"
    def apply(
        tx: TransactionTree,
        event: TreeEvent,
        domainId: DomainId,
    ): TransactionIndexRecord =
      TransactionIndexRecord(
        offset = tx.getOffset(),
        eventId = event.getEventId(),
        domainId = domainId,
      )
  }
}
