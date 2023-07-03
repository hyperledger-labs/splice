import cats.syntax.functor._
import com.digitalasset.canton.console.ParticipantReferenceCommon
import com.digitalasset.canton.console.commands.ParticipantHealthAdministrationCommon
import io.circe._
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._

case class DomainDef(
    alias: String,
    url: String,
)

def connectDomain(
    p: ParticipantReferenceCommon,
    pHealth: ParticipantHealthAdministrationCommon,
    domain: DomainDef,
) {
  logger.info(s"Ensuring connection to ${domain.alias} domain. (url: ${domain.url})")
  p.domains.connect(domain.alias, domain.url)
  utils.retry_until_true(p.domains.active(domain.alias))

  logger.info(s"Executing self ping to verify connection to ${domain.alias} domain...")
  pHealth.ping(p.id)
}

// Reference to a value from an environment. This is mainly used
// to reference secrets since we cannot directly reference their values.
final case class EnvSubst(
    env: String
)

// A reference to a party
sealed trait PartyRef

final case object Self {
  implicit val selfEncoder: Encoder[Self.type] =
    Encoder.encodeString.contramap[Self.type](_ => "self")
  implicit val selfDecoder: Decoder[Self.type] = Decoder.decodeString.emap { s =>
    Either.cond(s == "self", Self, s"Expected \"self\" but got $s")
  }
}

// Primary party of the current user
final case class PartyFromSelf(fromUser: Self.type) extends PartyRef
// Primary party of another user
final case class PartyFromOther(fromUser: EnvSubst) extends PartyRef

object PartyRef {
  implicit val encodePartyRef: Encoder[PartyRef] = Encoder.instance {
    case self @ PartyFromSelf(_) => self.asJson
    case other @ PartyFromOther(_) => other.asJson
  }
  implicit val decodePartyRef: Decoder[PartyRef] =
    List[Decoder[PartyRef]](
      Decoder[PartyFromSelf].widen,
      Decoder[PartyFromOther].widen,
    ).reduceLeft(_ or _)
}

// Primary party definition for the user
sealed trait PrimaryParty
// A new party is allocated with the given hint
final case class AllocateParty(allocate: String) extends PrimaryParty
// Primary party is set to match the primary part of another user.
final case class PartyFromUser(fromUser: EnvSubst) extends PrimaryParty

object PrimaryParty {
  implicit val encodePrimaryParty: Encoder[PrimaryParty] = Encoder.instance {
    case allocate @ AllocateParty(_) => allocate.asJson
    case fromUser @ PartyFromUser(_) => fromUser.asJson
  }
  implicit val decodePrimaryParty: Decoder[PrimaryParty] =
    List[Decoder[PrimaryParty]](
      Decoder[AllocateParty].widen,
      Decoder[PartyFromUser].widen,
    ).reduceLeft(_ or _)
}

// Definition of a user that will be created by the bootstrap script.
final case class UserDef(
    name: EnvSubst,
    primaryParty: Option[PrimaryParty],
    actAs: Seq[PartyRef],
    readAs: Seq[PartyRef],
    admin: Boolean,
)

def resolveEnv(env: EnvSubst): String =
  sys.env(env.env)

def resolvePrimaryParty(p: ParticipantReferenceCommon, primaryParty: PrimaryParty) =
  primaryParty match {
    case AllocateParty(allocate) => p.ledger_api.parties.allocate(allocate, allocate).party
    case PartyFromUser(env) => p.ledger_api.users.get(resolveEnv(env)).primaryParty.get
  }

def resolvePartyRef(
    p: ParticipantReferenceCommon,
    userId: String,
    self: Option[PartyId],
    ref: PartyRef,
) =
  ref match {
    case PartyFromSelf(_) => self.getOrElse(sys.error(s"User $userId has no primary party"))
    case PartyFromOther(env) =>
      val otherId = resolveEnv(env)
      p.ledger_api.users
        .get(otherId)
        .primaryParty
        .getOrElse(sys.error(s"User $otherId has no primary party"))
  }
