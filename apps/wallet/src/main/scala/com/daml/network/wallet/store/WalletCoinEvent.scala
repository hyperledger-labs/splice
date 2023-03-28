package com.daml.network.wallet.store

import com.daml.network.codegen.java.cn.wallet.payment as paymentCodegen
import com.daml.network.codegen.java.cn.wallet.subscriptions as subsCodegen
import com.daml.network.codegen.java.cc.api.v1 as ccApiCodegen
import com.daml.network.codegen.java.da.types as daTypes
import com.daml.network.util.ExerciseNodeCompanion

object AcceptedAppPayment_Collect extends ExerciseNodeCompanion {
  override type Tpl = paymentCodegen.AcceptedAppPayment
  override type Arg = paymentCodegen.AcceptedAppPayment_Collect
  override type Res = paymentCodegen.AcceptedAppPayment_Collect_Result

  override val templateOrInterface = Left(paymentCodegen.AcceptedAppPayment.COMPANION)
  override val choice = paymentCodegen.AcceptedAppPayment.CHOICE_AcceptedAppPayment_Collect

  override val argDecoder = paymentCodegen.AcceptedAppPayment_Collect.valueDecoder()
  override def argToValue(arg: Arg) = arg.toValue

  override val resDecoder = paymentCodegen.AcceptedAppPayment_Collect_Result.valueDecoder()
  override def resToValue(res: Res) = res.toValue
}

object SubscriptionInitialPayment_Collect extends ExerciseNodeCompanion {
  override type Tpl = subsCodegen.SubscriptionInitialPayment
  override type Arg = subsCodegen.SubscriptionInitialPayment_Collect
  override type Res = daTypes.Tuple3[
    subsCodegen.Subscription.ContractId,
    subsCodegen.SubscriptionIdleState.ContractId,
    ccApiCodegen.coin.Coin.ContractId,
  ]

  override val templateOrInterface = Left(subsCodegen.SubscriptionInitialPayment.COMPANION)
  override val choice =
    subsCodegen.SubscriptionInitialPayment.CHOICE_SubscriptionInitialPayment_Collect

  override val argDecoder = subsCodegen.SubscriptionInitialPayment_Collect.valueDecoder()
  override def argToValue(arg: Arg) = arg.toValue

  override val resDecoder = daTypes.Tuple3.valueDecoder(
    cid => new subsCodegen.Subscription.ContractId(cid.asContractId().get().getValue),
    cid => new subsCodegen.SubscriptionIdleState.ContractId(cid.asContractId().get().getValue),
    cid => new ccApiCodegen.coin.Coin.ContractId(cid.asContractId().get().getValue),
  )

  override def resToValue(res: Res) = res.toValue(
    _.toValue,
    _.toValue,
    _.toValue,
  )
}

object SubscriptionPayment_Collect extends ExerciseNodeCompanion {
  override type Tpl = subsCodegen.SubscriptionPayment
  override type Arg = subsCodegen.SubscriptionPayment_Collect
  override type Res = daTypes.Tuple2[
    subsCodegen.SubscriptionIdleState.ContractId,
    ccApiCodegen.coin.Coin.ContractId,
  ]

  override val templateOrInterface = Left(subsCodegen.SubscriptionPayment.COMPANION)
  override val choice = subsCodegen.SubscriptionPayment.CHOICE_SubscriptionPayment_Collect

  override val argDecoder = subsCodegen.SubscriptionPayment_Collect.valueDecoder()
  override def argToValue(arg: Arg) = arg.toValue

  override val resDecoder = daTypes.Tuple2.valueDecoder(
    cid => new subsCodegen.SubscriptionIdleState.ContractId(cid.asContractId().get().getValue),
    cid => new ccApiCodegen.coin.Coin.ContractId(cid.asContractId().get().getValue),
  )

  override def resToValue(res: Res) = res.toValue(
    _.toValue,
    _.toValue,
  )
}
