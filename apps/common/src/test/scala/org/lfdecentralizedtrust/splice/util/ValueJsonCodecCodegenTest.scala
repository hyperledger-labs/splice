package org.lfdecentralizedtrust.splice.util

import com.daml.ledger.javaapi.data as JavaApi
import org.lfdecentralizedtrust.splice.codegen.java.splice.{
  amulet as amuletCodegen,
  amuletrules as amuletrulesCodegen,
  round as roundCodegen,
  types as typesCodegen,
  validatorlicense as validatorlicenseCodegen,
}
import org.lfdecentralizedtrust.splice.store.{StoreErrors, StoreTest}
import com.digitalasset.daml.lf.data.Time.Timestamp

import java.util.Optional

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
        )
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
            event.getChoice,
            encodedResult,
          )
          .value

        decodedResult shouldEqual originalResult
      }
    }
  }

}
