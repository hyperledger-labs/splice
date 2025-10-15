package org.lfdecentralizedtrust.splice.util

import com.daml.ledger.javaapi.data as JavaApi
import org.lfdecentralizedtrust.splice.codegen.java.splice.{
  amulet as amuletCodegen,
  amuletrules as amuletrulesCodegen,
  round as roundCodegen,
  types as typesCodegen,
  validatorlicense as validatorlicenseCodegen,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv1
import org.lfdecentralizedtrust.splice.store.{StoreErrors, StoreTest}
import com.digitalasset.daml.lf.data.Time.Timestamp
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.metadatav1
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.metadatav1.{
  AnyContract,
  anyvalue,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.holdingv1
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.splice.externalpartyamuletrules

import java.time.{Instant, LocalDate}
import scala.jdk.OptionConverters.*
import java.util.Optional
import scala.jdk.CollectionConverters.*

class ValueJsonCodecCodegenTest extends StoreTest with StoreErrors {

  "ValueJsonCodecCodecTest" should {

    "convert between contract arguments and JSON values" in {
      // Note: this codec only works with values that are correspond to our daml code.
      // We can't use random JavaAPI values here, like we do in ValueJsonCodecProtobufTest.
      val codegenContract = openMiningRound(dsoParty, 42, 0.5)
      val event = toCreatedEvent(codegenContract)

      val original: JavaApi.DamlRecord = event.getArguments
      val encoded: String = ValueJsonCodecCodegen.serializableContractPayload(event).value
      val decoded: JavaApi.DamlRecord = ValueJsonCodecCodegen
        .deserializableContractPayload(
          event.getTemplateId,
          encoded,
        )
        .value

      decoded shouldEqual original
    }

    "handles optional fields in contract payloads" in {
      // without optional field set
      val decodedOptionalNotSet: JavaApi.DamlRecord = ValueJsonCodecCodegen
        .deserializableContractPayload(
          validatorlicenseCodegen.ValidatorLicense.TEMPLATE_ID_WITH_PACKAGE_ID,
          """{"validator": "validator", "sponsor": "sponsor", "dso": "dso"}""",
        )
        .value
      validatorlicenseCodegen.ValidatorLicense
        .valueDecoder()
        .decode(decodedOptionalNotSet) shouldBe new validatorlicenseCodegen.ValidatorLicense(
        "validator",
        "sponsor",
        "dso",
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
      )
      // with (one) optional field set
      val decodedOptionalSet: JavaApi.DamlRecord = ValueJsonCodecCodegen
        .deserializableContractPayload(
          validatorlicenseCodegen.ValidatorLicense.TEMPLATE_ID_WITH_PACKAGE_ID,
          s"""{"validator": "validator", "sponsor": "sponsor", "dso": "dso", "lastActiveAt": "${Timestamp.Epoch.toString}"}""",
        )
        .value
      validatorlicenseCodegen.ValidatorLicense
        .valueDecoder()
        .decode(decodedOptionalSet) shouldBe new validatorlicenseCodegen.ValidatorLicense(
        "validator",
        "sponsor",
        "dso",
        Optional.empty(),
        Optional.empty(),
        Optional.of(java.time.Instant.EPOCH),
        Optional.empty(),
        Optional.empty(),
      )
    }

    "convert between choice arguments/results and JSON values" in {
      // Note: this codec only works with values that are correspond to our daml code.
      // We can't use random JavaAPI values here, like we do in ValueJsonCodecProtobufTest.
      val originalArgument: JavaApi.DamlRecord = new amuletrulesCodegen.AmuletRules_DevNet_Tap(
        /*receiver =*/ dsoParty.toProtoPrimitive,
        /*amount =*/ BigDecimal(13).bigDecimal,
        /*openRound =*/ new roundCodegen.OpenMiningRound.ContractId(validContractId(1)),
      ).toValue
      val originalResult: JavaApi.DamlRecord = new amuletrulesCodegen.AmuletRules_DevNet_TapResult(
        /*amuletSum =*/ new amuletCodegen.AmuletCreateSummary(
          /*amulet =*/ new amuletCodegen.Amulet.ContractId(validContractId(2)),
          /*amuletPrice =*/ BigDecimal(0.5).bigDecimal,
          /*round =*/ new typesCodegen.Round(31L),
        ),
        /*meta = */ Optional.of(someMetadata),
      ).toValue
      val event = exercisedEvent(
        contractId = validContractId(3),
        templateId = amuletrulesCodegen.AmuletRules.TEMPLATE_ID_WITH_PACKAGE_ID,
        interfaceId = None,
        choice = amuletrulesCodegen.AmuletRules.CHOICE_AmuletRules_DevNet_Tap.name,
        consuming = false,
        argument = originalArgument,
        result = originalResult,
      )

      clue("argument") {
        val encodedArgument: String = ValueJsonCodecCodegen.serializeChoiceArgument(event).value
        val decodedArgument: JavaApi.DamlRecord = ValueJsonCodecCodegen
          .deserializeChoiceArgument(
            event.getTemplateId,
            event.getInterfaceId.toScala,
            event.getChoice,
            encodedArgument,
          )
          .value

        decodedArgument shouldEqual originalArgument
      }

      clue("result") {
        val encodedResult: String = ValueJsonCodecCodegen.serializeChoiceResult(event).value
        val decodedResult: JavaApi.Value = ValueJsonCodecCodegen
          .deserializeChoiceResult(
            event.getTemplateId,
            event.getInterfaceId.toScala,
            event.getChoice,
            encodedResult,
          )
          .value

        decodedResult shouldEqual originalResult
      }
    }

    "convert between choice arguments/results that come from interfaces and JSON values" in {
      val sender = providerParty(1).toProtoPrimitive
      val receiver = providerParty(2).toProtoPrimitive
      val now = Instant.now()
      val originalArgument: JavaApi.DamlRecord = new transferinstructionv1.TransferFactory_Transfer(
        dsoParty.toProtoPrimitive,
        new transferinstructionv1.Transfer(
          sender,
          receiver,
          numeric(6.12947561),
          new holdingv1.InstrumentId(dsoParty.toProtoPrimitive, "Amulet"),
          /*requestedAt =*/ now,
          /*executeBefore =*/ now.plusMillis(1000L),
          /*inputHoldingCids =*/ List(validContractId(1), validContractId(2))
            .map(new holdingv1.Holding.ContractId(_))
            .asJava,
          someMetadata,
        ),
        new metadatav1.ExtraArgs(
          someChoiceContext,
          someMetadata,
        ),
      ).toValue
      val originalResult: JavaApi.DamlRecord =
        new transferinstructionv1.TransferInstructionResult(
          new transferinstructionv1.transferinstructionresult_output.TransferInstructionResult_Pending(
            new transferinstructionv1.TransferInstruction.ContractId(validContractId(333))
          ),
          /*senderChangeCids =*/ List.empty.asJava,
          someMetadata,
        ).toValue

      val event = exercisedEvent(
        contractId = validContractId(3),
        templateId = externalpartyamuletrules.ExternalPartyAmuletRules.TEMPLATE_ID_WITH_PACKAGE_ID,
        interfaceId =
          Some(transferinstructionv1.TransferFactory.INTERFACE.TEMPLATE_ID_WITH_PACKAGE_ID),
        choice = transferinstructionv1.TransferFactory.CHOICE_TransferFactory_Transfer.name,
        consuming = false,
        argument = originalArgument,
        result = originalResult,
      )

      clue("argument") {
        val encodedArgument: String = ValueJsonCodecCodegen.serializeChoiceArgument(event).value
        val decodedArgument: JavaApi.DamlRecord = ValueJsonCodecCodegen
          .deserializeChoiceArgument(
            event.getTemplateId,
            event.getInterfaceId.toScala,
            event.getChoice,
            encodedArgument,
          )
          .value

        decodedArgument shouldEqual originalArgument
      }

      clue("result") {
        val encodedResult: String = ValueJsonCodecCodegen.serializeChoiceResult(event).value
        val decodedResult: JavaApi.Value = ValueJsonCodecCodegen
          .deserializeChoiceResult(
            event.getTemplateId,
            event.getInterfaceId.toScala,
            event.getChoice,
            encodedResult,
          )
          .value

        decodedResult shouldEqual originalResult
      }
    }
  }

  private lazy val allSimpleValuesMap: Map[String, metadatav1.AnyValue] = Map(
    "av_decimal" -> new anyvalue.AV_Decimal(numeric(3.1957419)),
    "av_party" -> new anyvalue.AV_Party(dsoParty.toProtoPrimitive),
    "av_date" -> new anyvalue.AV_Date(LocalDate.now()),
    "av_bool" -> new anyvalue.AV_Bool(scala.util.Random.nextBoolean()),
    "av_contractid" -> new anyvalue.AV_ContractId(
      new AnyContract.ContractId(validContractId(42))
    ),
    "av_int" -> new anyvalue.AV_Int(scala.util.Random.nextLong()),
    "av_time" -> new anyvalue.AV_Time(Instant.now()),
    "av_text" -> new anyvalue.AV_Text(scala.util.Random.nextString(13)),
    "av_reltime" -> new anyvalue.AV_RelTime(new RelTime(scala.util.Random.nextLong())),
  )
  private lazy val allAnyValuesMap: Map[String, metadatav1.AnyValue] = allSimpleValuesMap ++ Map(
    "av_list" -> new anyvalue.AV_List(allSimpleValuesMap.values.toList.asJava),
    "av_map" -> new anyvalue.AV_Map(allSimpleValuesMap.asJava),
  )
  private lazy val someChoiceContext =
    new metadatav1.ChoiceContext(allAnyValuesMap.asJava)
  private lazy val someMetadata =
    new metadatav1.Metadata(Map("any" -> "thing", "goes" -> "here").asJava)

}
