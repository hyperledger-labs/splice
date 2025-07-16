import java.io.PrintWriter
import scala.collection.mutable.ListBuffer
import scala.collection.{Map, SortedMap}
import scala.sys.process.*
import scala.util.{Failure, Success, Try}
import scala.util.matching.Regex

// This script depends on hub, https://github.com/github/hub

// check if we should run at all
val disableCheckerFile = "disabled-todo-checker"
if (new java.io.File(disableCheckerFile).exists()) {
  Console.out.println(
    s"todo-checker has been disabled by the presence of the following file: `${disableCheckerFile}`"
  )
  sys.exit(0)
}

val prNumO: Option[String] = {
  val pullRequestEnv = sys.env.getOrElse("CIRCLE_PULL_REQUEST", "")

  "([0-9]+)".r
    .findFirstMatchIn(pullRequestEnv)
    .map(_.matched)
}

val branch = sys.env.getOrElse("CIRCLE_BRANCH", "").strip()
val baseBranch = {
  prNumO.map { prNum =>
    Seq("hub", "pr", "show", "-f", "%B", prNum).!!.strip()
  }
}
val runningInCI = sys.env.contains("CI")
val releaseLineStr = "release-line"
val onReleaseLine = baseBranch.exists(_.contains(releaseLineStr)) || branch.contains(releaseLineStr)
val disableTodoChecker = onReleaseLine && runningInCI

if (disableTodoChecker) {
  val debugInfo = s"[debug info: branch '$branch', base branch '$baseBranch']"

  if (baseBranch.isDefined) {
    Console.out.println(
      s"todo-checker has been disabled, because this CI run is for a PR based on release line branch $baseBranch $debugInfo"
    )
  } else {
    Console.out.println(
      s"todo-checker has been disabled, because this CI run is for a commit on release line branch $branch $debugInfo"
    )
  }
  sys.exit(0)
}

// Check `hub` prerequisites
if (sys.env.getOrElse("GITHUB_TOKEN", "").isEmpty) {
  Console.err.println("""Error: GITHUB_TOKEN not set
      |
      |The TODO checker uses the Github cli to fetch the PR description, the commits in the PR, and the list of open Github issues.
      |You'll need to create a personal access token and export it from GITHUB_TOKEN for this to work.
      |
      |Please follow
      |  https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/creating-a-personal-access-token
      |_and_ don't forget to authorize that token for SAML as per
      |  https://docs.github.com/en/enterprise-cloud@latest/authentication/authenticating-with-saml-single-sign-on/authorizing-a-personal-access-token-for-use-with-saml-single-sign-on
      |
      |You can test whether your Github CLI works by calling
      |  hub issue
    """.stripMargin)
  sys.exit(1)
}

Console.out.println(
  s"Using Github CLI (`hub`) to fetch open issues assuming that an appropriate access token is provided in GITHUB_TOKEN. Please call `hub` manually to check your setup in case the process is stuck."
)


// Determine open issues
val fixedIssuesCurrentPR: Set[Int] = {
  prNumO.fold(Set.empty[Int])({ prNum =>
    val prInfo = Seq("hub", "pr", "show", "-f", "%b", prNum).!!
    val branch = Seq("hub", "pr", "show", "-f", "%H", prNum).!!.trim
    // When running from forks, we don't currently check the commit messages on the branch
    val commits = Try(Seq("git", "log", s"main..$branch").!!).getOrElse("")
    val issueCloseKeywords =
      Seq("close", "closes", "closed", "fix", "fixes", "fixed", "resolve", "resolves", "resolved")
        .map(w => s"($w)")
        .mkString("|")
    val fixedIssueRegexStr = "(?i)(" + issueCloseKeywords + ")(\\s+)(#)([0-9]+)"
    val fixedIssueRegex = fixedIssueRegexStr.r
    fixedIssueRegex
      .findAllMatchIn(prInfo + " " + commits)
      .map(m => "([0-9]+)".r.findFirstMatchIn(m.matched).get.matched.toInt)
      .toSet
  })
}

val openIssues: Set[Int] =
  Seq("hub", "issue", "-f", "%I ").!!.split("\\s+").map(_.toInt).toSet

println(s"Issues fixed by this PR: $fixedIssuesCurrentPR\n")

// Build classification buckets
trait Bucket extends Ordered[Bucket] {

  /** The name of the bucket
    */
  val name: String

  /** Provides an ordering between different [[Bucket]] classes
    */
  val classPosition: Int

  /** Provides an ordering between instances of the same [[Bucket]] class
    */
  val withinClassPosition: (Int, String)

  /** True whether TODOs in that bucket are OK on main.
    */
  val shouldNotExist: Boolean

  override def compare(that: Bucket): Int = {
    val compareCategories = classPosition.compareTo(that.classPosition)
    if (compareCategories == 0) {
      val compareWithinClassPosition1 =
        withinClassPosition._1.compareTo(that.withinClassPosition._1)
      if (compareWithinClassPosition1 == 0)
        withinClassPosition._2.compareTo(that.withinClassPosition._2)
      else
        compareWithinClassPosition1
    } else compareCategories
  }
}

object FixmeBucket extends Bucket {
  override val name = "FIXMEs are not allowed on main"
  override val classPosition = 0
  override val withinClassPosition = (0, "")
  override val shouldNotExist = true
}

case class TagBucket(tag: String) extends Bucket {
  override val name = tag
  override val classPosition = 1
  override val withinClassPosition = (tag.hashCode, "")
  override val shouldNotExist = false
}

case class MilestoneBucket(major: Int, minor: String) extends Bucket {
  override val name = s"Milestone M$major-$minor"
  override val classPosition = 2
  override val withinClassPosition = (major, minor)
  override val shouldNotExist = false
}

sealed trait IssueStatus {
  def position: Int
  def description: String
}
case class MarkedAsFixedIssue() extends IssueStatus {
  override val position: Int = 1
  override def description: String = "marked as fixed by this PR, but has left-over TODOs"
}
case class ClosedIssue() extends IssueStatus {
  override val position: Int = 2
  override def description: String = "closed"
}
case class OpenIssue() extends IssueStatus {
  override val position: Int = 3
  override def description: String = "open"
}

case class IssueBucket(number: Int, status: IssueStatus) extends Bucket {
  override val name = s"Issue #$number (${status.description})"
  override val classPosition = 3
  override val withinClassPosition = (number * 10 + status.position, "")
  // We do not want to merge a PR or commit that claims to fix an issue, but has left-over todos for it
  override val shouldNotExist = status == MarkedAsFixedIssue()
}

object UnknownBucket extends Bucket {
  override val name = "Unknown category"
  override val classPosition = 4
  override val withinClassPosition = (0, "")
  override val shouldNotExist = true
}

object ExcludedBucket extends Bucket {
  override val name = "Excluded"
  override val classPosition = 6
  override val withinClassPosition = (0, "")
  override val shouldNotExist = false
}

case class CrossRefIssueBucket(number: Int, org: String, repo: String) extends Bucket {
  override val name = s"Issue #$number in $org/$repo"
  override val classPosition = 7
  override val withinClassPosition = (0, "")
  override val shouldNotExist = false
}

trait RegexCategory {
  val regex: Regex
  def getBucket(name: String): Bucket
}

case class Tag(tags: List[String]) extends RegexCategory {

  override val regex = {
    require(tags.nonEmpty)
    val anyTag = "(?i)" + tags.map(t => s"($t)").mkString("|")
    anyTag.r
  }

  override def getBucket(name: String) = TagBucket(tags.head)
}

object Issue extends RegexCategory {
  override val regex = "[#][0-9]+".r

  override def getBucket(str: String) = numsToIssue(str)
}

object CrossRefIssue extends RegexCategory {
  override val regex = "\\(\\S+/\\S+#[0-9]+\\)".r

  override def getBucket(str: String) = parseCrossRefIssue(str)
}

object Milestone extends RegexCategory {
  override val regex = "M([0-9]+)-([0-9]+)".r

  override def getBucket(str: String) = {
    val m = regex.findFirstMatchIn(str).getOrElse(sys.error(s"Failed to parse milestone: $str"))
    MilestoneBucket(m.group(1).toInt, m.group(2))
  }
}

object GithubIssueLink extends RegexCategory {
  override val regex: Regex = "canton-network-node/issues/[0-9]+".r

  override def getBucket(str: String): Bucket = numsToIssue(str)
}

def numsToIssue(str: String): IssueBucket =
  "[0-9]+".r.findFirstMatchIn(str) match {
    case None => throw new RuntimeException("The given string isn't an issue")
    case Some(m) => {
      val i = m.matched.toInt
      val status =
        if (fixedIssuesCurrentPR.contains(i)) MarkedAsFixedIssue()
        else if (openIssues.contains(i)) OpenIssue()
        else ClosedIssue()
      IssueBucket(i, status)
    }
  }

def parseCrossRefIssue(str: String): CrossRefIssueBucket = {
  val ori = "\\((\\S+)/(\\S+)#([0-9]+)\\)".r.unanchored
  str match {
    case ori(org, repo, issue) =>
      CrossRefIssueBucket(issue.toInt, org, repo)
    case _ => throw new RuntimeException(s"The given string ($str) does not match the expected format")
  }
}

val tags: List[RegexCategory] = List(
  Tag(List("tech-debt")),
  Tag(List("Mx-90")),
)

val allRegexps: List[RegexCategory] = tags ++ List(Issue, Milestone, CrossRefIssue)

val identifierRegex = "(TODO)(.*?)\\((.+?)\\)".r

val todoStyleExcludePrefixes =
  Seq(
    "canton/",
    "project/CantonDependencies.scala",
    "experiments/",
    "CONTRIBUTING.md",
    ".github/actions/tests/split_tests/dist/",
    "token-standard/dependencies",
  )
val todoStyleExcludeSuffixes =
  Seq("/checkTodos.sc", "/build.static_tests.yml", "/migrate-github-issues.py")

def addToBucket(
    acc: Map[Bucket, List[String]],
    bucket: Bucket,
    line: String,
): Map[Bucket, List[String]] = {
  acc ++ Map(bucket -> (line :: acc.getOrElse(bucket, List())))
}

def matchTODOWithBuckets(line: String, bucketsForLine: String): List[(Bucket, String)] = {

  val allAttempts = allRegexps
    .map(rgx =>
      rgx.regex.findFirstMatchIn(bucketsForLine).map(m => rgx.getBucket(m.matched) -> line)
    )
    .collect { case Some(x) => x }

  if (allAttempts.isEmpty) List(UnknownBucket -> line) else allAttempts
}

def processScalaStyleTodo(line: String): List[(Bucket, String)] = {
  val excluded = todoStyleExcludePrefixes.exists(line.startsWith) ||
    todoStyleExcludeSuffixes.exists(suffix => line.takeWhile(c => c != ':').endsWith(suffix))
  if (excluded)
    List(ExcludedBucket -> line)
  else if (line.toUpperCase.contains("FIXME"))
    List(FixmeBucket -> line)
  else {
    identifierRegex.findFirstMatchIn(line) match {
      case Some(contents) => matchTODOWithBuckets(line, contents.matched)
      case None => List(UnknownBucket -> line)
    }
  }
}

def purple(str: String): String = {
  val ANSI_PURPLE = "\u001B[35m"
  val ANSI_RESET = "\u001B[0m"
  ANSI_PURPLE + str + ANSI_RESET
}

def tableToString(table: Map[Bucket, List[String]], color: Boolean = false): String = {
  def printBucket(str: String): String = if (color) purple(str) else str
  table.toList
    .map({ case (bucket, todoList) => printBucket(bucket.name) + "\n\n" + todoList.mkString("\n") })
    .mkString("\n\n")
}

def writeToFile(content: String, filename: String): Unit = {
  new PrintWriter(filename) {
    write(content)
    close()
  }
}

def grepForPattern(pattern: String, caseInsensitive: Boolean = false): Seq[String] =
  Seq(
    Seq("git", "grep", "-I"),
    (if (caseInsensitive) Seq("-i") else Seq.empty),
    Seq(pattern),
  ).flatten

val grepCommands = Seq(grepForPattern("TODO"), grepForPattern("FIXME", caseInsensitive = true))
val grepLines = grepCommands.flatMap({ command =>
  Try {
    command.lazyLines_!(ErrorCollector)
  } match {
    case Failure(exception) =>
      println(s"Failed in running command $command")
      throw exception
    case Success(value) => value
  }
})
object ErrorCollector extends ProcessLogger {
  private val allErrors = ListBuffer[String]()
  def errors() = allErrors.mkString("\n\n")
  def anyError(): Boolean = allErrors.nonEmpty

  // Do not use ProcessLogger.apply(_) to work around:
  // https://github.com/lihaoyi/Ammonite/issues/1120
  override def out(s: => String): Unit = Console.out.println(s)
  override def err(s: => String): Unit = allErrors += s
  override def buffer[T](f: => T) = f
}

if (ErrorCollector.anyError())
  throw new RuntimeException("grep or awk failed with errors:\n\n" + ErrorCollector.errors())

def initialMapping(): Map[Bucket, List[String]] = SortedMap()
def pairsToMap(pairs: List[(Bucket, String)]): Map[Bucket, List[String]] =
  pairs.foldLeft(initialMapping()) { case (acc, (bucket, string)) =>
    addToBucket(acc, bucket, string)
  }

val todoStyleIssuesTable: List[(Bucket, String)] =
  grepLines.toList.flatMap(line => {
    Try {
      processScalaStyleTodo(line)
    } match {
      case Failure(exception) =>
        println(s"Failed line $line")
        throw exception
      case Success(value) => value
    }
  })
val table = pairsToMap(todoStyleIssuesTable)

// Write all todos to output dir
new java.io.File("todo-out").mkdirs
writeToFile(tableToString(table), "todo-out/todos")

// Print problems to stderr
val problemTable = table.filter(entry => entry._1.shouldNotExist && entry._2.nonEmpty)
if (problemTable.nonEmpty) {
  Console.err.println(tableToString(problemTable, color = true))
  System.exit(1)
}
