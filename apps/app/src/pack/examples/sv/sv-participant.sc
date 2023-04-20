import com.digitalasset.canton.topology.PartyId
import scala.annotation.tailrec

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

if (userExists(svUserName)) {
  println(s"Found existing SV user: " + svUserName)
} else {
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

// TODO(#4176) remove in favor of using a dedicated ledger API call
def userExists(userName: String): Boolean = {
  // `users.list()` is paginated and we can only `filterUser` there, not search for an exact match
  @tailrec
  def loop(userName: String, pageToken: String): Boolean = {
    val response = svParticipant.ledger_api.users.list(filterUser = userName, pageToken = pageToken)
    if (response.users.exists(_.id == userName)) {
      return true
    } else if (response.nextPageToken.isEmpty) {
      return false
    } else {
      loop(userName, response.nextPageToken)
    }
  }
  loop(userName, "")
}
