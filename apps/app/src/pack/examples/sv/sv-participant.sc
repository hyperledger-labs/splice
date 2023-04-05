import com.digitalasset.canton.topology.PartyId
import scala.util.Try

val domainUrl = sys.env.get("DOMAIN_URL") match {
  case None =>
    sys.error(
      "Environment variable DOMAIN_URL was not set, set it to http://${targetcluster}.network.canton.global:5008"
    )
  case Some(url) => url
}

// Note: the sv user name is defined in sv.conf
val svUserName = System.getProperty("SV_USER_NAME", "sv_user")

println("Starting SV participant node")
svParticipant.start()

println(s"Connecting SV participant to the domain $domainUrl")
svParticipant.domains.connect("global", domainUrl)

if (Try(svParticipant.ledger_api.users.get(svUserName)).isFailure) {

  println(s"Creating SV user: " + svUserName)
  val svParty =
    svParticipant.ledger_api.parties.allocate("sv_service_user", "sv_service_user").party

  svParticipant.ledger_api.users.create(
    id = svUserName,
    actAs = Set(svParty),
    readAs = Set.empty,
    primaryParty = Some(svParty),
    participantAdmin = true,
  )
}
println("SV participant bootstrap finished")
