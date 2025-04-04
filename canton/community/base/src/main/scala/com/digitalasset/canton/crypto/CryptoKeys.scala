// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.crypto

import cats.Order
import cats.syntax.either.*
import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.config.CantonRequireTypes.{
  LengthLimitedStringWrapper,
  LengthLimitedStringWrapperCompanion,
  String300,
  String68,
}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.serialization.ProtoConverter
import com.digitalasset.canton.serialization.ProtoConverter.ParsingResult
import com.digitalasset.canton.store.db.DbDeserializationException
import com.digitalasset.canton.topology.UniqueIdentifier
import com.digitalasset.canton.version.{
  HasVersionedMessageCompanion,
  HasVersionedWrapper,
  ProtoVersion,
  ProtocolVersion,
}
import com.google.protobuf.ByteString
import io.circe.Encoder
import slick.jdbc.{GetResult, SetParameter}

trait CryptoKey extends Product with Serializable {
  def format: CryptoKeyFormat
  protected[crypto] def key: ByteString
}

/** a human readable fingerprint of a key that serves as a unique identifier */
final case class Fingerprint private (protected val str: String68)
    extends LengthLimitedStringWrapper
    with PrettyPrinting {
  def toLengthLimitedString: String68 = str

  override protected def pretty: Pretty[Fingerprint] = prettyOfParam(_.unwrap.readableHash)
}

trait HasFingerprint {
  @inline def fingerprint: Fingerprint
}

object Fingerprint {

  implicit val fingerprintOrder: Order[Fingerprint] =
    Order.by[Fingerprint, String](_.unwrap)

  implicit val setParameterFingerprint: SetParameter[Fingerprint] = (f, pp) =>
    pp >> f.toLengthLimitedString
  implicit val getResultFingerprint: GetResult[Fingerprint] = GetResult { r =>
    Fingerprint
      .fromProtoPrimitive(r.nextString())
      .valueOr(err =>
        throw new DbDeserializationException(s"Failed to deserialize fingerprint: $err")
      )
  }

  implicit val fingerprintEncoder: Encoder[Fingerprint] =
    Encoder.encodeString.contramap[Fingerprint](_.unwrap)

  private[this] def apply(hash: Hash): Fingerprint =
    throw new UnsupportedOperationException("Use create/deserialization methods instead.")

  /** create fingerprint from a human readable string */
  def fromProtoPrimitive(str: String): ParsingResult[Fingerprint] =
    UniqueIdentifier
      .verifyValidString(str) // verify that we can represent the string as part of the UID.
      .leftMap(ProtoDeserializationError.StringConversionError.apply(_))
      .flatMap(String68.fromProtoPrimitive(_, "Fingerprint"))
      .map(Fingerprint(_))

  private[crypto] def create(
      bytes: ByteString
  ): Fingerprint = {
    val hash = Hash.digest(HashPurpose.PublicKeyFingerprint, bytes, HashAlgorithm.Sha256)
    new Fingerprint(hash.toLengthLimitedHexString)
  }

  def create(str: String): Either[String, Fingerprint] =
    fromProtoPrimitive(str).leftMap(_.message)

  def tryCreate(str: String): Fingerprint =
    create(str).valueOr(err =>
      throw new IllegalArgumentException(s"Invalid fingerprint $str: $err")
    )

  def tryCreate(str68: String68): Fingerprint =
    tryCreate(str68.unwrap)

}

trait CryptoKeyPairKey extends CryptoKey {
  def id: Fingerprint

  def isPublicKey: Boolean
}

trait CryptoKeyPair[+PK <: PublicKey, +SK <: PrivateKey]
    extends HasVersionedWrapper[CryptoKeyPair[PublicKey, PrivateKey]]
    with Product
    with Serializable {

  require(
    publicKey.id == privateKey.id,
    "Public and private key of the same key pair must have the same ids.",
  )

  override protected def companionObj: CryptoKeyPair.type = CryptoKeyPair

  def publicKey: PK
  def privateKey: SK

  // The keypair is identified by the public key's id
  def id: Fingerprint = publicKey.id

  protected def toProtoCryptoKeyPairPairV30: v30.CryptoKeyPair.Pair

  def toProtoCryptoKeyPairV30: v30.CryptoKeyPair = v30.CryptoKeyPair(toProtoCryptoKeyPairPairV30)
}

object CryptoKeyPair extends HasVersionedMessageCompanion[CryptoKeyPair[PublicKey, PrivateKey]] {

  override def name: String = "crypto key pair"

  val supportedProtoVersions: SupportedProtoVersions = SupportedProtoVersions(
    ProtoVersion(30) -> ProtoCodec(
      ProtocolVersion.v32,
      supportedProtoVersion(v30.CryptoKeyPair)(fromProtoCryptoKeyPairV30),
      _.toProtoCryptoKeyPairV30.toByteString,
    )
  )

  def fromProtoCryptoKeyPairV30(
      keyPair: v30.CryptoKeyPair
  ): ParsingResult[CryptoKeyPair[_ <: PublicKey, _ <: PrivateKey]] =
    for {
      pair <- keyPair.pair match {
        case v30.CryptoKeyPair.Pair.EncryptionKeyPair(value) =>
          EncryptionKeyPair
            .fromProtoV30(value): Either[
            ProtoDeserializationError,
            CryptoKeyPair[EncryptionPublicKey, EncryptionPrivateKey],
          ]
        case v30.CryptoKeyPair.Pair.SigningKeyPair(value) =>
          SigningKeyPair
            .fromProtoV30(value): Either[
            ProtoDeserializationError,
            CryptoKeyPair[SigningPublicKey, SigningPrivateKey],
          ]
        case v30.CryptoKeyPair.Pair.Empty =>
          Left(ProtoDeserializationError.FieldNotSet("pair"))
      }
    } yield pair
}

trait PublicKey extends CryptoKeyPairKey {
  def toByteString(version: ProtocolVersion): ByteString

  def fingerprint: Fingerprint = id

  override lazy val id: Fingerprint =
    // TODO(i15649): Consider the key format and fingerprint scheme before computing
    Fingerprint.create(key)

  def purpose: KeyPurpose

  def isSigning: Boolean = purpose == KeyPurpose.Signing

  override def isPublicKey: Boolean = true

  protected def toProtoPublicKeyKeyV30: v30.PublicKey.Key

  /** With the v30.PublicKey message we model the class hierarchy of public keys in protobuf.
    * Each child class that implements this trait can be serialized with `toProto` to their corresponding protobuf
    * message. With the following method, it can be serialized to this trait's protobuf message.
    */
  def toProtoPublicKeyV30: v30.PublicKey = v30.PublicKey(key = toProtoPublicKeyKeyV30)
}

object PublicKey {
  def fromProtoPublicKeyV30(publicKeyP: v30.PublicKey): ParsingResult[PublicKey] =
    publicKeyP.key match {
      case v30.PublicKey.Key.Empty => Left(ProtoDeserializationError.FieldNotSet("key"))
      case v30.PublicKey.Key.EncryptionPublicKey(encPubKeyP) =>
        EncryptionPublicKey.fromProtoV30(encPubKeyP)
      case v30.PublicKey.Key.SigningPublicKey(signPubKeyP) =>
        SigningPublicKey.fromProtoV30(signPubKeyP)
    }

}

final case class KeyName(protected val str: String300)
    extends LengthLimitedStringWrapper
    with PrettyPrinting {
  def emptyStringAsNone: Option[KeyName] = if (str.unwrap.isEmpty) None else Some(this)
  override protected def pretty: Pretty[KeyName] = prettyOfClass(
    unnamedParam(_.str.unwrap.unquoted)
  )
}
object KeyName extends LengthLimitedStringWrapperCompanion[String300, KeyName] {
  override def instanceName: String = "KeyName"
  override protected def companion: String300.type = String300
  override protected def factoryMethodWrapper(str: String300): KeyName = KeyName(str)
}

trait PublicKeyWithName
    extends Product
    with Serializable
    with HasVersionedWrapper[PublicKeyWithName] {
  type K <: PublicKey
  def publicKey: K
  def name: Option[KeyName]

  def id: Fingerprint

  override protected def companionObj: PublicKeyWithName.type =
    PublicKeyWithName

  def toProtoV30: v30.PublicKeyWithName =
    v30.PublicKeyWithName(
      publicKey = Some(
        publicKey.toProtoPublicKeyV30
      ),
      name = name.map(_.unwrap).getOrElse(""),
    )
}

object PublicKeyWithName extends HasVersionedMessageCompanion[PublicKeyWithName] {

  override def name: String = "PublicKeyWithName"

  val supportedProtoVersions: SupportedProtoVersions = SupportedProtoVersions(
    ProtoVersion(30) -> ProtoCodec(
      ProtocolVersion.v32,
      supportedProtoVersion(v30.PublicKeyWithName)(fromProto30),
      _.toProtoV30.toByteString,
    )
  )

  def fromProto30(key: v30.PublicKeyWithName): ParsingResult[PublicKeyWithName] =
    for {
      publicKey <- ProtoConverter.parseRequired(
        PublicKey.fromProtoPublicKeyV30,
        "public_key",
        key.publicKey,
      )
      name <- KeyName.fromProtoPrimitive(key.name)
    } yield {
      (publicKey: @unchecked) match {
        case k: SigningPublicKey => SigningPublicKeyWithName(k, name.emptyStringAsNone)
        case k: EncryptionPublicKey => EncryptionPublicKeyWithName(k, name.emptyStringAsNone)
      }
    }
}

// The private key id must match the corresponding public key's one
trait PrivateKey extends CryptoKeyPairKey {
  def purpose: KeyPurpose

  override def isPublicKey: Boolean = false

  protected def toProtoPrivateKeyKeyV30: v30.PrivateKey.Key

  /** Same representation of the class hierarchy in protobuf messages, see [[PublicKey]]. */
  def toProtoPrivateKey: v30.PrivateKey = v30.PrivateKey(key = toProtoPrivateKeyKeyV30)
}

object PrivateKey {

  def fromProtoPrivateKey(
      privateKeyP: v30.PrivateKey
  ): ParsingResult[PrivateKey] =
    privateKeyP.key match {
      case v30.PrivateKey.Key.Empty => Left(ProtoDeserializationError.FieldNotSet("key"))
      case v30.PrivateKey.Key.EncryptionPrivateKey(encPrivKeyP) =>
        EncryptionPrivateKey.fromProtoV30(encPrivKeyP)
      case v30.PrivateKey.Key.SigningPrivateKey(signPrivKeyP) =>
        SigningPrivateKey.fromProtoV30(signPrivKeyP)
    }

}

sealed trait CryptoKeyFormat extends Product with Serializable with PrettyPrinting {
  def name: String
  def toProtoEnum: v30.CryptoKeyFormat
  override protected def pretty: Pretty[this.type] = prettyOfString(_.name)
}

object CryptoKeyFormat {

  implicit val cryptoKeyFormatOrder: Order[CryptoKeyFormat] =
    Order.by[CryptoKeyFormat, String](_.name)

  case object Der extends CryptoKeyFormat {
    override val name: String = "DER"
    override def toProtoEnum: v30.CryptoKeyFormat = v30.CryptoKeyFormat.CRYPTO_KEY_FORMAT_DER
  }

  case object Raw extends CryptoKeyFormat {
    override val name: String = "Raw"
    override def toProtoEnum: v30.CryptoKeyFormat = v30.CryptoKeyFormat.CRYPTO_KEY_FORMAT_RAW
  }

  case object Symbolic extends CryptoKeyFormat {
    override val name: String = "Symbolic"
    override def toProtoEnum: v30.CryptoKeyFormat = v30.CryptoKeyFormat.CRYPTO_KEY_FORMAT_SYMBOLIC
  }

  def fromProtoEnum(
      field: String,
      formatP: v30.CryptoKeyFormat,
  ): ParsingResult[CryptoKeyFormat] =
    formatP match {
      case v30.CryptoKeyFormat.CRYPTO_KEY_FORMAT_UNSPECIFIED =>
        Left(ProtoDeserializationError.FieldNotSet(field))
      case v30.CryptoKeyFormat.Unrecognized(value) =>
        Left(ProtoDeserializationError.UnrecognizedEnum(field, value))
      case v30.CryptoKeyFormat.CRYPTO_KEY_FORMAT_DER => Right(CryptoKeyFormat.Der)
      case v30.CryptoKeyFormat.CRYPTO_KEY_FORMAT_RAW => Right(CryptoKeyFormat.Raw)
      case v30.CryptoKeyFormat.CRYPTO_KEY_FORMAT_SYMBOLIC => Right(CryptoKeyFormat.Symbolic)
    }
}

sealed trait KeyPurpose extends Product with Serializable with PrettyPrinting {

  def name: String

  // An identifier for a key purpose that is used for serialization
  def id: Byte

  def toProtoEnum: v30.KeyPurpose

  override protected def pretty: Pretty[KeyPurpose.this.type] = prettyOfString(_.name)
}

object KeyPurpose {

  val All: Set[KeyPurpose] = Set(Signing, Encryption)

  implicit val setParameterKeyPurpose: SetParameter[KeyPurpose] = (k, pp) => pp.setByte(k.id)
  implicit val getResultKeyPurpose: GetResult[KeyPurpose] = GetResult { r =>
    r.nextByte() match {
      case Signing.id => Signing
      case Encryption.id => Encryption
      case unknown => throw new DbDeserializationException(s"Unknown key purpose id: $unknown")
    }
  }

  case object Signing extends KeyPurpose {
    override val name: String = "signing"
    override val id: Byte = 0
    override def toProtoEnum: v30.KeyPurpose = v30.KeyPurpose.KEY_PURPOSE_SIGNING
  }

  case object Encryption extends KeyPurpose {
    override val name: String = "encryption"
    override val id: Byte = 1
    override def toProtoEnum: v30.KeyPurpose = v30.KeyPurpose.KEY_PURPOSE_ENCRYPTION
  }

  def fromProtoEnum(
      field: String,
      purposeP: v30.KeyPurpose,
  ): ParsingResult[KeyPurpose] =
    purposeP match {
      case v30.KeyPurpose.KEY_PURPOSE_UNSPECIFIED =>
        Left(ProtoDeserializationError.FieldNotSet(field))
      case v30.KeyPurpose.Unrecognized(value) =>
        Left(ProtoDeserializationError.UnrecognizedEnum(field, value))
      case v30.KeyPurpose.KEY_PURPOSE_SIGNING => Right(Signing)
      case v30.KeyPurpose.KEY_PURPOSE_ENCRYPTION => Right(Encryption)
    }
}

/** Information that is cached for each view and is to be re-used if another view has
  * the same recipients and transparency can be respected.
  *
  * @param sessionKeyAndReference the randomness, the corresponding symmetric key used to
  *                               encrypt the view, and a symbolic reference to use in the 'encryptedBy' field.
  * @param encryptedBy an optional symbolic reference for the parent session key (if it exists) that encrypts a view
  *                    containing this session key’s randomness. This cache entry must be revoked if the
  *                    reference no longer matches.
  * @param encryptedSessionKeys the randomness of the session key encrypted for each recipient.
  */
final case class SessionKeyInfo(
    sessionKeyAndReference: SessionKeyAndReference,
    encryptedBy: Option[Object],
    encryptedSessionKeys: Seq[AsymmetricEncrypted[SecureRandomness]],
)

/** The randomness and corresponding session key, as well as a temporary reference to it that lives as long as the cache lives.
  */
final case class SessionKeyAndReference(
    randomness: SecureRandomness,
    key: SymmetricKey,
    reference: Object,
)
