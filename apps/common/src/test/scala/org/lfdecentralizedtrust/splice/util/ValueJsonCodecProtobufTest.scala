package org.lfdecentralizedtrust.splice.util

import com.daml.ledger.javaapi.data as JavaApi
import org.lfdecentralizedtrust.splice.util.Generators.valueGen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

class ValueJsonCodecProtobufTest
    extends AnyFlatSpec
    with Matchers
    with ScalaCheckDrivenPropertyChecks
    with TableDrivenPropertyChecks {

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSize = 1, sizeRange = 3)

  "ValueJsonCodecProtobuf" should "convert between JavaAPI.Value and JSON strings" in forAll(
    valueGen
  ) { value =>
    val original: JavaApi.Value = JavaApi.Value.fromProto(value)
    val encoded: String = ValueJsonCodecProtobuf.serializeValue(original)
    val decoded: JavaApi.Value = ValueJsonCodecProtobuf.deserializeValue(encoded)

    decoded shouldEqual original
  }
}
