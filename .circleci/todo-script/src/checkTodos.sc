import java.io.PrintWriter

import scala.collection.mutable.ListBuffer
import scala.collection.{Map, SortedMap}
import scala.sys.process._
import scala.util.matching.Regex

/*
 * Current limitations of the TODO checker:
 *  - For `.rst` files, TODO blocks are considered to be the range from each instance of `todo::` to the next blank
 *    line. This is different to the actual TODO block in the `.rst` file---for example, a `rst` TODO block may contain
 *    blank lines followed by more content of the block.
 */
// This script depends on hub, https://github.com/github/hub, and awk

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

trait Bucket extends Ordered[Bucket] {

  /** The name of the bucket
    */
  val name: String

  /** Provides an ordering between different [[Bucket]] classes
    */
  val classPosition: Int

  /** Provides an ordering between instances of the same [[Bucket]] class
    */
  val withinClassPosition: Int

  override def compare(that: Bucket): Int = {
    val compareCategories = classPosition.compareTo(that.classPosition)
    if (compareCategories == 0)
      withinClassPosition.compareTo(that.withinClassPosition)
    else compareCategories
  }
}

case class TagBucket(tag: String) extends Bucket {
  override val name = tag
  override val classPosition = 1
  override val withinClassPosition = tag.hashCode
}

case class MilestoneBucket(number: Int) extends Bucket {
  override val name = "Milestone " + number.toString
  override val classPosition = 2
  override val withinClassPosition = number
}

case class IssueBucket(number: Int) extends Bucket {
  override val name = "Issue " + number.toString
  override val classPosition = 3
  override val withinClassPosition = number
}

object UnknownBucket extends Bucket {
  override val name = "Unknown category"
  override val classPosition = 4
  override val withinClassPosition = 0
}

object UnassignedBucket extends Bucket {
  override val name = "No category assigned"
  override val classPosition = 5
  override val withinClassPosition = 0
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
  override val regex = "[i#][0-9]+".r

  override def getBucket(str: String) = numsToIssue(str)
}

object Milestone extends RegexCategory {
  override val regex = "M[0-9]+".r

  override def getBucket(str: String) = MilestoneBucket(str.drop(1).toInt)
}

object GithubIssueLink extends RegexCategory {
  override val regex: Regex = "canton/issues/[0-9]+".r

  override def getBucket(str: String): Bucket = numsToIssue(str)
}

def numsToIssue(str: String): IssueBucket = {
  "[0-9]+".r.findFirstMatchIn(str) match {
    case None => throw new RuntimeException("The given string isn't an issue")
    case Some(m) => IssueBucket(m.matched.toInt)
  }
}

val tags: List[RegexCategory] = List(
  Tag(List("Luciano")),
  Tag(List("Alex")),
  Tag(List("Mike")),
  Tag(List("Simon")),
  Tag(List("Moritz")),
  Tag(List("Arne")),
  Tag(List("Itai")),
)

val allRegexps: List[RegexCategory] = tags ++ List(Issue, Milestone)

val todoPatterns = Seq("TODO", "XXX", "FIXME")
val todoPatternRegexpStr = todoPatterns.map(str => s"($str)").mkString("|")

def addToBucket(
    acc: Map[Bucket, List[String]],
    bucket: Bucket,
    line: String,
): Map[Bucket, List[String]] = {
  acc + (bucket -> (line :: acc.getOrElse(bucket, List())))
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
  val identifierRegex = ("(" + todoPatternRegexpStr + """)(.*?)\((.+?)\)""").r
  identifierRegex.findFirstMatchIn(line) match {
    case Some(contents) => matchTODOWithBuckets(line, contents.matched)
    case None => List(UnassignedBucket -> line)
  }
}

def processRstStyleTodo(line: String): List[(Bucket, String)] = {
  val ppLine = line.replace("<SEP>", "\n")

  val allIssues = line
    .split("<SEP>")
    .toList
    .map { issue =>
      GithubIssueLink.regex
        .findFirstMatchIn(issue)
        .map(m => GithubIssueLink.getBucket(m.matched) -> ppLine)
    }
    .collect { case Some(x) => x }

  if (allIssues.isEmpty) List(UnassignedBucket -> ppLine)
  else allIssues
}

def tableToString(table: Map[Bucket, List[String]]): String = {
  table.toList
    .map({ case (bucket, todoList) => bucket.name + "\n\n" + todoList.mkString("\n") })
    .mkString("\n\n")
}

def writeToFile(content: String, filename: String): Unit = {
  new PrintWriter(filename) {
    write(content);
    close()
  }
}

val fixedIssuesCurrentPR: Set[Int] = {
  prNumO.fold(Set.empty[Int])({ prNum =>
    val prInfo = Seq("hub", "pr", "show", "-f", "%b", prNum).!!
    val issueCloseKeywords = "((fixes)|(closes))"
    val fixedIssueRegexStr = "(?i)" + issueCloseKeywords + "(\\s+)(#)([0-9]+)"
    val fixedIssueRegex = fixedIssueRegexStr.r
    fixedIssueRegex
      .findAllMatchIn(prInfo)
      .map(m => "([0-9]+)".r.findFirstMatchIn(m.matched).get.matched.toInt)
      .toSet
  })
}

if (sys.env.getOrElse("GITHUB_TOKEN", "").isEmpty){
  Console.err.println(
    """Error: GITHUB_TOKEN not set
      |
      |The TODO checker uses the Github cli to determine the list of open Github issues.
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
  s"Using Github CLI (`hub`) to fetch open issues assuming that an appropriate access token is provided in GITHUB_TOKEN. Please call `hub` manually to check your setup in case the process is stuck.")

val openIssues: Set[Int] = Seq("hub", "issue", "-f", "%I ").!!.split("\\s+").map(_.toInt).toSet

val projectRoot = "." // CI scripts are called from the project root

val scalaStyleExcludeDirectories =
Seq("canton",
    "canton-release",
    "todo-script",
    "log",
    "todo-out",
    "docs",
    ".git",
    ".idea",
    "target",
    "3rdparty",
    ".daml",
    "experiments",
    "build")

// Different versions of grep (e.g. on Mac and Ubuntu) behave differently. This grep call should be tested for both
// platforms if it is to be run locally. Right now it's only tested for Ubuntu because the CI uses Ubuntu.
def grepForPattern(pattern: String): Seq[String] = {
  Seq("grep", "-r") ++ scalaStyleExcludeDirectories.map(dir => s"--exclude-dir=$dir") ++ Seq(
    "-I", // Ignore binary files
    "--exclude=*.png",
    "--exclude=*checkTodos.sc",
    pattern,
    projectRoot,
  )
}

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

val grepCommands = todoPatterns.map(grepForPattern)
val grepLines = grepCommands.flatMap({ command =>
  command.lineStream_!(ErrorCollector)
})

val awkGrep = Seq("git", "grep", "--files-with-matches", """\.\. todo::""", "docs-open")
val awkRun = Seq("xargs", "awk", "-f", ".circleci/todo-script/rst_script.awk")
val awkCommand = awkGrep #| awkRun
val awkLines = awkCommand.lazyLines_!(ErrorCollector)

if (ErrorCollector.anyError())
  throw new RuntimeException("grep or awk failed with errors:\n\n" + ErrorCollector.errors())

def initialMapping(): Map[Bucket, List[String]] = SortedMap()
def pairsToMap(pairs: List[(Bucket, String)]): Map[Bucket, List[String]] =
  pairs.foldLeft(initialMapping()) { case (acc, (bucket, string)) =>
    addToBucket(acc, bucket, string)
  }

val scalaStyleIssuesTable: List[(Bucket, String)] =
  grepLines.toList.flatMap(line => processScalaStyleTodo(line))
val rstStyleIssuesTable: List[(Bucket, String)] =
  awkLines.toList.flatMap(line => processRstStyleTodo(line))
val table = pairsToMap(scalaStyleIssuesTable ++ rstStyleIssuesTable)

val issuesNotOpen = table.filter{
  case (IssueBucket(i), _) => (!openIssues.contains(i)) || fixedIssuesCurrentPR.contains(i)
  case _ => false
}

val todosWithoutReference = table.filter{ case (bucket, _) =>
  bucket match {
    case UnknownBucket | UnassignedBucket => true
    case _ => false
  }
}

def purple(str: String): String = {
  val ANSI_PURPLE = "\u001B[35m"
  val ANSI_RESET = "\u001B[0m"
  ANSI_PURPLE + str + ANSI_RESET
}

val strTable = tableToString(table)
val strNoOwner = purple("TODOs without owners:\n\n") + tableToString(todosWithoutReference)
val strNotOpen = purple("TODOs for closed issues:\n\n") + tableToString(issuesNotOpen)

new java.io.File("todo-out").mkdirs
writeToFile(strTable, "todo-out/todos")

val allTodos = table.values.toList.flatten
// There may be duplicates in allTodos when a TODO occurs in multiple categories
// For example:
// TODO(ratko/matthias): this would show up twice

val uniqueTodos = Set.apply(allTodos: _*)
val todoCount = uniqueTodos.size
writeToFile(todoCount.toString, "todo-out/count")

if (todosWithoutReference.nonEmpty && issuesNotOpen.nonEmpty) {
  val output = List(strNoOwner, strNotOpen).mkString("\n\n")
  Console.err.println(output)
  System.exit(1)
}

if (todosWithoutReference.nonEmpty) {
  Console.err.println(strNoOwner)
  System.exit(1)
}

if (issuesNotOpen.nonEmpty) {
  Console.err.println(strNotOpen)
  System.exit(1)
}
