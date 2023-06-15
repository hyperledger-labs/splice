import scala.annotation.tailrec

// Note: the sv user name is defined in sv.conf
val svUserName = System.getProperty("SV_USER_NAME", "sv_user")

println("Starting SV participant node")
svParticipant.start()

if (userExists(svUserName)) {
  println(s"Found existing SV user: " + svUserName)
} else {
  println(s"Creating SV user $svUserName")
  svParticipant.ledger_api.users.create(
    id = svUserName,
    actAs = Set.empty,
    readAs = Set.empty,
    primaryParty = None,
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
