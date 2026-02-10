// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import com.daml.ledger.javaapi
import com.daml.ledger.javaapi.data.{CreatedEvent, Identifier}
import com.daml.ledger.javaapi.data.codegen.json.JsonLfWriter
import com.daml.ledger.javaapi.data.codegen.{ContractId, DamlRecord, DefinedDataType}
import com.digitalasset.canton.config.CantonRequireTypes.{String2066, String3, String300}
import com.digitalasset.canton.data.Offset
import com.digitalasset.canton.daml.lf.value.json.ApiCodecCompressed
import com.digitalasset.canton.topology.{Member, PartyId, SynchronizerId}
import com.digitalasset.daml.lf.data.Ref.HexString
import com.digitalasset.daml.lf.data.Time.Timestamp
import com.google.protobuf.ByteString
import io.circe.Json
import io.circe.parser.parse as circeParse
import org.lfdecentralizedtrust.splice.store.db.AcsQueries.AcsStoreId
import org.lfdecentralizedtrust.splice.store.db.TxLogQueries.TxLogStoreId
import org.lfdecentralizedtrust.splice.util.Contract.Companion
import org.lfdecentralizedtrust.splice.util.{
  Contract,
  LegacyOffset,
  PackageQualifiedName,
  QualifiedName,
}
import slick.ast.FieldSymbol
import slick.jdbc.{GetResult, JdbcType, PositionedParameters, PositionedResult, SetParameter}
import com.digitalasset.canton.resource.DbParameterUtils
import com.digitalasset.canton.LfValue
import com.digitalasset.canton.logging.ErrorLoggingContext
import spray.json.{JsString, JsValue, JsonFormat, deserializationError}

import java.sql.{JDBCType, PreparedStatement, ResultSet}
import java.io.StringWriter

trait AcsJdbcTypes {
  import AcsJdbcTypes.JsonString

  val profile: slick.jdbc.JdbcProfile

  import profile.api.*

  protected implicit lazy val byteArrayGetResult: GetResult[Array[Byte]] =
    GetResult { rs => rs.nextBytes() }

  protected implicit def byteStringSetParameter(implicit
      setParameterByteArray: SetParameter[Array[Byte]]
  ): SetParameter[ByteString] =
    (bs: ByteString, pp: PositionedParameters) => setParameterByteArray.apply(bs.toByteArray, pp)

  protected implicit lazy val acsStoreIdJdbcType: JdbcType[AcsStoreId] =
    AcsStoreId.subst(implicitly[BaseColumnType[Int]])

  protected implicit lazy val acsStoreIdGetResult: GetResult[AcsStoreId] =
    GetResult.GetInt.andThen(AcsStoreId.apply(_))

  protected implicit lazy val acsStoreIdGetResultOption: GetResult[Option[AcsStoreId]] =
    GetResult.GetIntOption.andThen(_.map(AcsStoreId.apply(_)))

  protected implicit lazy val acsStoreIdSetParameter: SetParameter[AcsStoreId] =
    (id: AcsStoreId, pp: PositionedParameters) =>
      SetParameter.SetInt.apply(AcsStoreId.unwrap(id), pp)

  protected implicit lazy val txLogStoreIdJdbcType: JdbcType[TxLogStoreId] =
    TxLogStoreId.subst(implicitly[BaseColumnType[Int]])

  protected implicit lazy val txLogStoreIdGetResult: GetResult[TxLogStoreId] =
    GetResult.GetInt.andThen(TxLogStoreId.apply(_))

  protected implicit lazy val txLogStoreIdGetResultOption: GetResult[Option[TxLogStoreId]] =
    GetResult.GetIntOption.andThen(_.map(TxLogStoreId.apply(_)))

  protected implicit lazy val txLogStoreIdSetParameter: SetParameter[TxLogStoreId] =
    (id: TxLogStoreId, pp: PositionedParameters) =>
      SetParameter.SetInt.apply(TxLogStoreId.unwrap(id), pp)

  protected implicit lazy val timestampJdbcType: JdbcType[Timestamp] =
    MappedColumnType.base[Timestamp, Long](_.micros, Timestamp.assertFromLong)

  protected implicit lazy val timestampGetResult: GetResult[Timestamp] =
    GetResult.GetLong.andThen(Timestamp.assertFromLong)

  protected implicit lazy val timestampGetResultOption: GetResult[Option[Timestamp]] =
    GetResult.GetLongOption.andThen(_.map(Timestamp.assertFromLong))

  protected implicit lazy val timestampSetParameter: SetParameter[Timestamp] =
    (ts: Timestamp, pp: PositionedParameters) => SetParameter.SetLong.apply(ts.micros, pp)

  protected implicit lazy val timestampSetParameterOption: SetParameter[Option[Timestamp]] =
    (ts: Option[Timestamp], pp: PositionedParameters) =>
      SetParameter.SetLongOption.apply(ts.map(_.micros), pp)

  protected implicit def contractIdJdbcType[T]: JdbcType[ContractId[T]] =
    MappedColumnType.base[ContractId[T], String](_.contractId, new ContractId[T](_))

  protected implicit def contractIdSetParameter: SetParameter[ContractId[?]] =
    (c: ContractId[?], pp: PositionedParameters) =>
      implicitly[SetParameter[String2066]].apply(lengthLimited(c.contractId), pp)

  protected implicit def contractIdSetParameterOption: SetParameter[Option[ContractId[?]]] =
    (cId: Option[ContractId[?]], pp: PositionedParameters) =>
      implicitly[SetParameter[Option[String2066]]]
        .apply(cId.map(c => lengthLimited(c.contractId)), pp)

  protected implicit def contractIdGetResult[T]: GetResult[ContractId[T]] =
    GetResult.GetString.andThen(new ContractId[T](_))

  protected implicit def contractIdGetResultOption[T]: GetResult[Option[ContractId[T]]] =
    GetResult.GetStringOption.andThen(_.map(new ContractId[T](_)))

  protected implicit def contractIdArrayGetResult[T]: GetResult[Array[ContractId[T]]] =
    stringArrayGetResult.andThen(_.map(new ContractId[T](_)))

  protected implicit lazy val stringArrayGetResult: GetResult[Array[String]] =
    (r: PositionedResult) => {
      (r.rs
        .getArray(r.skip.currentPos)
        .getArray match {
        case arr: Array[String] =>
          arr
        case x =>
          throw new IllegalStateException(
            s"Expected an array of strings, but got $x. Are you sure you selected a text array column?"
          )
      })
    }

  protected implicit lazy val intArrayGetResult: GetResult[Array[Int]] = (r: PositionedResult) => {
    val sqlArray = r.rs.getArray(r.skip.currentPos)
    if (sqlArray == null) Array.emptyIntArray
    else
      sqlArray.getArray match {
        case arr: Array[java.lang.Integer] => arr.map(_.intValue())
        case arr: Array[Int] => arr
        case x =>
          throw new IllegalStateException(
            s"Expected an array of integers, but got $x. Are you sure you selected an integer array column?"
          )
      }
  }

  protected implicit lazy val stringArrayOptGetResult: GetResult[Option[Array[String]]] =
    (r: PositionedResult) => {
      Option(r.rs.getArray(r.skip.currentPos)).map {
        _.getArray match {
          case arr: Array[String] =>
            arr
          case x =>
            throw new IllegalStateException(
              s"Expected an optional array of strings, but got $x. Are you sure you selected a text array column?"
            )
        }
      }
    }

  protected implicit lazy val stringSeqGetResult: GetResult[Seq[String]] =
    stringArrayGetResult.andThen(_.toSeq)

  protected implicit lazy val stringSeqOptGetResult: GetResult[Option[Seq[String]]] =
    stringArrayOptGetResult.andThen(_.map(_.toSeq))

  protected implicit lazy val intSeqSetParameter: SetParameter[Seq[Int]] =
    (ints: Seq[Int], pp: PositionedParameters) =>
      DbParameterUtils.setArrayIntOParameterDb(Some(ints.toArray), pp)

  protected implicit lazy val longArraySetParameter: SetParameter[Array[Long]] = (v, pp) =>
    pp.setObject(v, java.sql.Types.ARRAY)

  private val stringArraySetParameter: SetParameter[Array[String]] =
    (strings: Array[String], pp: PositionedParameters) =>
      pp.setObject(
        pp.ps.getConnection.createArrayOf("text", strings.map(x => x)),
        JDBCType.ARRAY.getVendorTypeNumber,
      )

  protected implicit lazy val string3ArraySetParameter: SetParameter[Array[String3]] =
    (strings: Array[String3], pp: PositionedParameters) =>
      stringArraySetParameter(strings.map(_.str), pp)

  protected implicit lazy val string2066ArraySetParameter: SetParameter[Array[String2066]] =
    (strings: Array[String2066], pp: PositionedParameters) =>
      stringArraySetParameter(strings.map(_.str), pp)

  protected implicit lazy val string2066SeqSetParameter: SetParameter[Seq[String2066]] =
    (strings: Seq[String2066], pp: PositionedParameters) =>
      stringArraySetParameter(strings.map(_.str).toArray, pp)

  protected implicit def contractIdArraySetParameter[T]: SetParameter[Array[ContractId[T]]] =
    (ids: Array[ContractId[T]], pp: PositionedParameters) =>
      stringArraySetParameter(ids.map(_.contractId), pp)

  protected implicit def contractIdAnyArraySetParameter: SetParameter[Array[ContractId[?]]] =
    (ids: Array[ContractId[?]], pp: PositionedParameters) =>
      stringArraySetParameter(ids.map(_.contractId), pp)

  protected implicit def partyIdGetResult[T]: GetResult[PartyId] =
    GetResult.GetString.andThen(PartyId.tryFromProtoPrimitive)

  protected implicit def partyIdGetResultOption[T]: GetResult[Option[PartyId]] =
    GetResult.GetStringOption.andThen(_.map(PartyId.tryFromProtoPrimitive))

  protected implicit lazy val offsetJdbcType: JdbcType[Offset] =
    MappedColumnType.base[Offset, String](
      offset => LegacyOffset.fromLong(offset.unwrap).toHexString,
      s => Offset.tryFromLong(LegacyOffset.fromHexString(HexString.assertFromString(s)).toLong),
    )

  protected implicit lazy val identifierSetParameter: SetParameter[Identifier] = {
    (identifier: Identifier, pp: PositionedParameters) =>
      {
        implicitly[SetParameter[String2066]].apply(
          lengthLimited(
            s"${identifier.getPackageId}:${identifier.getModuleName}:${identifier.getEntityName}"
          ),
          pp,
        )
      }
  }

  protected implicit lazy val packageQualifiedNameSetParameter: SetParameter[PackageQualifiedName] =
    SetParameter.SetString.contramap(_.toString)

  protected implicit lazy val qualifiedNameSetParameter: SetParameter[QualifiedName] =
    (v1: QualifiedName, v2: PositionedParameters) =>
      implicitly[SetParameter[String2066]].apply(lengthLimited(v1.toString()), v2)

  protected implicit val qualifiedNameGetResult: GetResult[QualifiedName] =
    GetResult.GetString.andThen { s => QualifiedName.assertFromString(s) }

  protected implicit val packageQualifiedNameGetResult: GetResult[PackageQualifiedName] =
    implicitly[GetResult[(QualifiedName, String)]].andThen { case (qualifiedName, packageName) =>
      PackageQualifiedName(packageName, qualifiedName)
    }

  protected implicit lazy val qualifiedNameJdbcType: JdbcType[QualifiedName] =
    MappedColumnType.base[QualifiedName, String](
      { _.toString }, {
        QualifiedName.assertFromString(_)
      },
    )

  protected implicit lazy val synchronizerIdJdbcType: JdbcType[SynchronizerId] =
    MappedColumnType.base[SynchronizerId, String](_.toProtoPrimitive, SynchronizerId.tryFromString)

  protected implicit lazy val partyIdJdbcType: JdbcType[PartyId] =
    MappedColumnType.base[PartyId, String](_.toProtoPrimitive, PartyId.tryFromProtoPrimitive)

  protected implicit lazy val partyIdSetParameterOption: SetParameter[Option[PartyId]] =
    (partyId: Option[PartyId], pp: PositionedParameters) =>
      implicitly[SetParameter[Option[String2066]]]
        .apply(partyId.map(party => lengthLimited(party.toProtoPrimitive)), pp)

  protected implicit lazy val partyIdSetParameterArray: SetParameter[Array[PartyId]] =
    (partyId: Array[PartyId], pp: PositionedParameters) =>
      implicitly[SetParameter[Array[String2066]]]
        .apply(partyId.map(party => lengthLimited(party.toProtoPrimitive)), pp)

  protected implicit lazy val memberIdSetParameter: SetParameter[Member] =
    (memberId: Member, pp: PositionedParameters) =>
      implicitly[SetParameter[String300]].apply(memberId.toLengthLimitedString, pp)

  protected implicit lazy val memberIdSetParameterOption: SetParameter[Option[Member]] =
    (memberIdO: Option[Member], pp: PositionedParameters) =>
      implicitly[SetParameter[Option[String300]]].apply(memberIdO.map(_.toLengthLimitedString), pp)

  protected implicit lazy val jsonJdbcType: JdbcType[Json] = new profile.DriverJdbcType[Json]() {
    override def sqlType: Int = java.sql.Types.OTHER

    override def sqlTypeName(sym: Option[FieldSymbol]): String = "jsonb"

    override def setValue(v: Json, p: PreparedStatement, idx: Int): Unit =
      p.setObject(idx, v.noSpaces, java.sql.Types.OTHER)

    override def getValue(r: ResultSet, idx: Int): Json = {
      val value = r.getString(idx)
      if (r.wasNull())
        throw new IllegalStateException("Tried to deserialize as Json, but the value was null.")
      else
        io.circe.parser
          .parse(value)
          .getOrElse(throw new IllegalStateException("JSONB column didn't contain valid JSON."))
    }

    override def updateValue(v: Json, r: ResultSet, idx: Int): Unit =
      r.updateObject(idx, v.noSpaces, java.sql.Types.OTHER)

    override def valueToSQLLiteral(value: Json): String = s"'${value.noSpaces}'"
  }

  protected implicit lazy val jsonGetResult: GetResult[Json] = GetResult { rs =>
    val value = rs.nextString()
    if (rs.wasNull())
      throw new IllegalStateException("Tried to deserialize as Json, but the value was null.")
    else
      io.circe.parser
        .parse(value)
        .getOrElse(throw new IllegalStateException("JSONB column didn't contain valid JSON."))
  }

  protected implicit lazy val jsonSetParameter: SetParameter[Json] =
    (json: Json, pp: PositionedParameters) => {
      pp.setObject(json.noSpaces, java.sql.Types.OTHER)
    }

  protected implicit lazy val jsonSetParameterOption: SetParameter[Option[Json]] =
    (jsValueOpt: Option[Json], pp: PositionedParameters) =>
      jsValueOpt match {
        case Some(jsValue) => jsonSetParameter(jsValue, pp)
        case None => pp.setNull(java.sql.Types.OTHER)
      }

  protected implicit lazy val jsonStringGetResult: GetResult[JsonString] = GetResult { rs =>
    val value = rs.nextString()
    if (rs.wasNull())
      throw new IllegalStateException("Tried to deserialize as Json, but the value was null.")
    else
      JsonString(value)
  }

  protected implicit lazy val jsonStringSetParameter: SetParameter[JsonString] =
    (jsonString: JsonString, pp: PositionedParameters) =>
      pp.setObject(jsonString.value, java.sql.Types.OTHER)

  protected implicit lazy val jsonStringSetParameterOption: SetParameter[Option[JsonString]] =
    (jsonStringOpt: Option[JsonString], pp: PositionedParameters) =>
      jsonStringOpt match {
        case Some(jsonString) => jsonStringSetParameter(jsonString, pp)
        case None => pp.setNull(java.sql.Types.OTHER)
      }

  protected def payloadJsonFromDefinedDataType(
      data: DefinedDataType[?]
  ): Json = AcsJdbcTypes.payloadJsonFromDefinedDataType(data)

  /** The DB may truncate strings of unbounded length, so it's advised to use a LengthLimitedString instead.
    * We use String2066 because it's the max length of an [[com.digitalasset.canton.protocol.LfTemplateId]].
    */
  protected def lengthLimited(s: String): String2066 = String2066.tryCreate(s)

  protected def tryToDecode[TCid <: ContractId[?], T <: DamlRecord[?], D](
      companion: Companion.Template[TCid, T],
      createdEvent: CreatedEvent,
  )(
      toData: Contract[TCid, T] => D
  ): Either[String, D] = {
    Contract
      .fromCreatedEvent(companion)(createdEvent)
      .map(toData)
      .toRight(
        s"Failed to decode ${companion.getTemplateIdWithPackageId} from CreatedEvent of contract id ${createdEvent.getContractId}."
      )
  }
}

object AcsJdbcTypes {
  final case class JsonString(value: String)

  // DB *must not* use stringly ints or decimals;
  // this output relies on a *big* invariant: comparing the raw JSON data
  // with the built-in SQL operators <, >, &c, yields equal results to
  // comparing the same data in a data-aware way. That's why we *must* use
  // numbers-as-numbers in both codecs, and why ISO-8601 strings
  // for dates and timestamps are so important.

  private val javaapiDamlRecordCodec = {
    // copied from JsonContractIdFormat
    implicit val ContractIdFormat: JsonFormat[LfValue.ContractId] =
      new JsonFormat[LfValue.ContractId] {
        override def write(obj: LfValue.ContractId): JsValue =
          JsString(obj.coid)

        override def read(json: JsValue): LfValue.ContractId = json match {
          case JsString(s) =>
            LfValue.ContractId.fromString(s).fold(deserializationError(_), identity)
          case _ => deserializationError("ContractId must be a string")
        }
      }

    new ApiCodecCompressed(encodeDecimalAsString = false, encodeInt64AsString = false)
  }

  @throws[io.circe.ParsingFailure]
  def payloadJsonFromDefinedDataType(
      data: DefinedDataType[?]
  ): Json = {
    val sw = new StringWriter()
    val jw = new JsonLfWriter(
      sw,
      JsonLfWriter.opts().encodeInt64AsString(false).encodeNumericAsString(false),
    )
    data.jsonEncoder.encode(jw)
    circeParse(sw.toString).fold(throw _, identity)
  }

  def payloadJsonFromJavaApiDamlRecord(
      record: javaapi.data.DamlRecord
  )(implicit elc: ErrorLoggingContext): Json = {
    circeParse(
      javaapiDamlRecordCodec.apiValueToJsValue(Contract.javaValueToLfValue(record)).compactPrint
    ).fold(throw _, identity)
  }
}
