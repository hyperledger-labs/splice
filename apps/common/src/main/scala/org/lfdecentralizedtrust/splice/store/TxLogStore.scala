// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import com.daml.ledger.javaapi.data.Transaction
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.tracing.TraceContext

import scala.jdk.CollectionConverters.*
import scala.util.Try
import org.lfdecentralizedtrust.splice.store.db.TxLogRowData
import org.lfdecentralizedtrust.splice.util.{Codec, EventId}
import com.digitalasset.canton.config.CantonRequireTypes.String3
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}

import scala.collection.SeqView
import scala.reflect.ClassTag

/** Stores historical information that can be used to construct application-specific historical events,
  * such as a user notification or an item from a bank statement.
  *
  * Principles:
  * - The store for tx log entries and the store for the ACS are updated from the same daml transaction stream.
  *   The updates are atomical, i.e., both stores always reflect the state of the ledger at the same offset.
  *   To simplify keeping the audit log and the ACS store in sync, the tx log store is integrated into the ACS store.
  * - If an application needs to display multiple kinds of historical events, then the set of tx log entries
  *   should represent the union of all historical events.
  * - Each transaction tree can produce any number of tx log entries.
  */
object TxLogStore {
  def firstPage[TXE, TXER <: TXE](log: SeqView[TXE], limit: PageLimit)(implicit
      tag: ClassTag[TXER]
  ): Seq[TXER] =
    log
      .collect { case txi: TXER =>
        txi
      }
      .take(limit.limit)
      .toSeq

  def nextPage[TXE, TXER <: TXE](
      log: SeqView[TXE],
      pageEnd: String,
      limit: PageLimit,
  )(
      project: TXER => String
  )(implicit tag: ClassTag[TXER]): Seq[TXER] =
    log
      .collect { case txi: TXER =>
        txi
      }
      .dropWhile(e => project(e) != pageEnd)
      .slice(1, 1 + limit.limit)
      .toSeq

  /** Extracts tx log entries from transaction tree events */
  trait Parser[+TXE] {

    /** Extract application-specific TxLog entries from the given daml transaction */
    def tryParse(tx: Transaction, domain: SynchronizerId)(implicit tc: TraceContext): Seq[TXE]

    /** Returns a TxLog entry to be stored in case this parser failed to parse the given daml transaction.
      * Must not throw an error.
      */
    def error(offset: Long, eventId: String, synchronizerId: SynchronizerId): Option[TXE]

    final def parse(tx: Transaction, domain: SynchronizerId, logger: TracedLogger)(implicit
        tc: TraceContext
    ): Seq[TXE] =
      Try(tryParse(tx, domain))
        .recoverWith { case e: Throwable =>
          logger.error(s"Failed to parse transaction: ${e.getMessage}", e)
          val firstRootEventId = tx.getRootNodeIds.asScala.headOption
            .map(EventId.prefixedFromUpdateIdAndNodeId(tx.getUpdateId, _))
            .getOrElse("")
          Try(
            error(tx.getOffset, firstRootEventId, domain).toList
          )
        }
        .fold(
          e => {
            logger.error(s"Failed to handle parsing error: ${e.getMessage}", e)
            Seq.empty
          },
          identity,
        )
  }

  object Parser {
    lazy val empty: Parser[Nothing] = new Parser[Nothing] {
      override def tryParse(tx: Transaction, domain: SynchronizerId)(implicit
          tc: TraceContext
      ): Seq[Nothing] = Seq.empty
      override def error(
          offset: Long,
          eventId: String,
          synchronizerId: SynchronizerId,
      ): Option[Nothing] = None
    }
  }

  trait Config[TXE] {

    /** Extracts entries from transaction trees */
    def parser: Parser[TXE]

    /** Defines index columns */
    def entryToRow: TXE => TxLogRowData

    /** Encodes the entry payload to a JSON object */
    def encodeEntry: TXE => (String3, String)

    /** Decodes the entry payload from a JSON object */
    def decodeEntry: (String3, String) => TXE
  }

  object Config {
    def empty: Config[Nothing] = new Config[Nothing] {
      override def parser = Parser.empty
      override def entryToRow: Nothing = throw new RuntimeException(
        "This app does not serialize any TxLog entries"
      )
      override def encodeEntry: Nothing = throw new RuntimeException(
        "This app does not serialize any TxLog entries"
      )
      override def decodeEntry: Nothing = throw new RuntimeException(
        "This app does not serialize any TxLog entries"
      )
    }
  }

  /** The TypeMapper instances below are used by scalapb to convert from the types used in the
    * serialized JSON format (e.g., String) to some application-specific types (e.g., PartyId).
    *
    * Note that not all conversions are safe: for example, asking scalapb to convert a string field
    * to a PartyId effectively makes the field required - missing text fields are equivalent to an
    * empty string which is not a valid PartyId. In this case, asking scalapb to convert a string field
    * to an Option[PartyId] is safer.
    */
  trait TxLogEntryTypeMappers {
    // Note: Timestamp is serialized as an RFC 3339 string in JSON (e.g., "1972-01-01T10:00:20.021Z")
    protected implicit val `TypeMapper[com.google.protobuf.timestamp.Timestamp, java.time.Instant]`
        : scalapb.TypeMapper[com.google.protobuf.timestamp.Timestamp, java.time.Instant] =
      scalapb.TypeMapper[com.google.protobuf.timestamp.Timestamp, java.time.Instant](
        _.asJavaInstant
      )(javaInstant =>
        com.google.protobuf.timestamp.Timestamp(javaInstant.getEpochSecond, javaInstant.getNano)
      )
    // Notes:
    // - Fields with default values are omitted by default in proto3 JSON output
    // - The default value for a string field is the empty string
    // - The empty string is not a valid PartyId or SynchronizerId
    // - It is therefore safe to map the empty string to None
    protected implicit val `TypeMapper[String, Option[PartyId]]`
        : scalapb.TypeMapper[String, Option[com.digitalasset.canton.topology.PartyId]] =
      scalapb.TypeMapper[String, Option[com.digitalasset.canton.topology.PartyId]](str =>
        if (str.isEmpty) None else Some(PartyId.tryFromProtoPrimitive(str))
      )(_.map(_.toProtoPrimitive).getOrElse(""))
    protected implicit val `TypeMapper[String, Option[SynchronizerId]]`
        : scalapb.TypeMapper[String, Option[com.digitalasset.canton.topology.SynchronizerId]] =
      scalapb.TypeMapper[String, Option[com.digitalasset.canton.topology.SynchronizerId]](str =>
        if (str.isEmpty) None else Some(SynchronizerId.tryFromString(str))
      )(_.map(_.toProtoPrimitive).getOrElse(""))

    protected implicit val `TypeMapper[String, Option[java.math.BigDecimal]]`
        : scalapb.TypeMapper[String, Option[java.math.BigDecimal]] =
      scalapb.TypeMapper[String, Option[java.math.BigDecimal]](str =>
        if (str.isEmpty) None else Some(Codec.tryDecode(Codec.JavaBigDecimal)(str))
      )(value => value.map(Codec.encode(_)(Codec.javaBigDecimalValue)).getOrElse(""))
    protected implicit val `TypeMapper[String, Option[scala.math.BigDecimal]]`
        : scalapb.TypeMapper[String, Option[scala.math.BigDecimal]] =
      scalapb.TypeMapper[String, Option[scala.math.BigDecimal]](str =>
        if (str.isEmpty) None else Some(Codec.tryDecode(Codec.BigDecimal)(str))
      )(value => value.map(Codec.encode(_)(Codec.bigDecimalValue)).getOrElse(""))

    protected implicit val `TypeMapper[String, java.math.BigDecimal]`
        : scalapb.TypeMapper[String, java.math.BigDecimal] =
      scalapb.TypeMapper[String, java.math.BigDecimal](Codec.tryDecode(Codec.JavaBigDecimal))(
        Codec.encode(_)(Codec.javaBigDecimalValue)
      )
    protected implicit val `TypeMapper[String, scala.math.BigDecimal]`
        : scalapb.TypeMapper[String, scala.math.BigDecimal] =
      scalapb.TypeMapper[String, scala.math.BigDecimal](Codec.tryDecode(Codec.BigDecimal))(
        Codec.encode(_)(Codec.bigDecimalValue)
      )
    protected implicit val `TypeMapper[String, PartyId]`
        : scalapb.TypeMapper[String, com.digitalasset.canton.topology.PartyId] =
      scalapb.TypeMapper[String, com.digitalasset.canton.topology.PartyId](
        PartyId.tryFromProtoPrimitive
      )(_.toProtoPrimitive)
    protected implicit val `TypeMapper[String, SynchronizerId]`
        : scalapb.TypeMapper[String, com.digitalasset.canton.topology.SynchronizerId] =
      scalapb.TypeMapper[String, com.digitalasset.canton.topology.SynchronizerId](
        SynchronizerId.tryFromString
      )(
        _.toProtoPrimitive
      )

    protected implicit val voteRequestResultType: scalapb.TypeMapper[
      com.google.protobuf.struct.Struct,
      org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules_CloseVoteRequestResult,
    ] =
      scalapb.TypeMapper[
        com.google.protobuf.struct.Struct,
        org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules_CloseVoteRequestResult,
      ](x => {
        val javaProto = com.google.protobuf.struct.Struct.toJavaProto(x)
        val string = com.google.protobuf.util.JsonFormat.printer().print(javaProto)
        org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules_CloseVoteRequestResult
          .fromJson(string)
      })(x => {
        val string = x.toJson
        val builder = com.google.protobuf.Struct.newBuilder()
        com.google.protobuf.util.JsonFormat
          .parser()
          .merge(string, builder)
        com.google.protobuf.struct.Struct.fromJavaProto(builder.build())
      })
  }
}
