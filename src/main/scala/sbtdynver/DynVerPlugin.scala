package sbtdynver

import java.util._
import scala.util._
import sbt._, Keys._

object DynVerPlugin extends AutoPlugin {
  override def requires = plugins.JvmPlugin
  override def trigger  = allRequirements

  object autoImport {
    val dynver                  = taskKey[String]("The version of your project, from git")
    val dynverCurrentDate       = settingKey[Date]("The current date, for dynver purposes")
    val dynverGitDescribeOutput = settingKey[Option[GitDescribeOutput]]("The output from git describe")
    val dynverCheckVersion      = taskKey[Boolean]("Checks if version and dynver match")
    val dynverAssertVersion     = taskKey[Unit]("Asserts if version and dynver match")
  }
  import autoImport._

  override def buildSettings = Seq(
       version := dynverGitDescribeOutput.value.version(dynverCurrentDate.value),
    isSnapshot := dynverGitDescribeOutput.value.isSnapshot,

    dynverCurrentDate       := new Date,
    dynverGitDescribeOutput := DynVer.getGitDescribeOutput(dynverCurrentDate.value),

    dynver                  := DynVer.version(new Date),
    dynverCheckVersion      := (dynver.value == version.value),
    dynverAssertVersion     := (
      if (!dynverCheckVersion.value)
        sys.error(s"Version and dynver mismatch - version: ${version.value}, dynver: ${dynver.value}")
    )
  )
}

final case class GitRef(value: String)
final case class GitCommitSuffix(distance: Int, sha: String)
final case class GitDirtySuffix(value: String)

object GitRef extends (String => GitRef) {
  final implicit class GitRefOps(val x: GitRef) extends AnyVal { import x._
    def isTag: Boolean = value startsWith "v"
    def dropV: GitRef = GitRef(value.replaceAll("^v", ""))
    def mkString(prefix: String, suffix: String): String = if (value.isEmpty) "" else prefix + value + suffix
  }
}

object GitCommitSuffix extends ((Int, String) => GitCommitSuffix) {
  final implicit class GitCommitSuffixOps(val x: GitCommitSuffix) extends AnyVal { import x._
    def isEmpty: Boolean = distance <= 0 || sha.isEmpty
    def mkString(prefix: String, infix: String, suffix: String): String =
      if (isEmpty) "" else prefix + distance + infix + sha + suffix
  }
}

object GitDirtySuffix extends (String => GitDirtySuffix) {
  final implicit class GitDirtySuffixOps(val x: GitDirtySuffix) extends AnyVal { import x._
    def dropPlus: GitDirtySuffix = GitDirtySuffix(value.replaceAll("^\\+", ""))
    def mkString(prefix: String, suffix: String): String = if (value.isEmpty) "" else prefix + value + suffix
  }
}

final case class GitDescribeOutput(ref: GitRef, commitSuffix: GitCommitSuffix, dirtySuffix: GitDirtySuffix) {
  def version: String       = ref.dropV.value + commitSuffix.mkString("+", "-", "") + dirtySuffix.value
  def isSnapshot(): Boolean = isDirty() || hasNoTags()

  def isDirty(): Boolean    = dirtySuffix.value.nonEmpty
  def hasNoTags(): Boolean  = !ref.isTag
}

object GitDescribeOutput extends ((GitRef, GitCommitSuffix, GitDirtySuffix) => GitDescribeOutput) {
  private val Tag          =  """(v[0-9][^+]*)""".r
  private val Distance     =  """\+([0-9]+)""".r
  private val Sha          =  """([0-9a-f]{8})""".r
  private val CommitSuffix = s"""($Distance-$Sha)""".r
  private val TstampSuffix =  """(\+[0-9]{8}-[0-9]{4})""".r

  private val FromTag  = s"""^$Tag$CommitSuffix?$TstampSuffix?$$""".r
  private val FromSha  = s"""^$Sha$TstampSuffix?$$""".r
  private val FromHead = s"""^HEAD$TstampSuffix$$""".r

  private[sbtdynver] def parse(s: String): GitDescribeOutput = s.trim match {
    case FromTag(tag, _, dist, sha, dirty) => parse0(   tag, dist, sha, dirty)
    case FromSha(sha, dirty)               => parse0(   sha,  "0",  "", dirty)
    case FromHead(dirty)                   => parse0("HEAD",  "0",  "", dirty)
  }

  private def parse0(ref: String, dist: String, sha: String, dirty: String) = {
    val commit = if (dist == null || sha == null) GitCommitSuffix(0, "") else GitCommitSuffix(dist.toInt, sha)
    GitDescribeOutput(GitRef(ref), commit, GitDirtySuffix(if (dirty eq null) "" else dirty))
  }

  implicit class OptGitDescribeOutputOps(val _x: Option[GitDescribeOutput]) extends AnyVal {
    def mkVersion(f: GitDescribeOutput => String, fallback: => String): String = _x.fold(fallback)(f)

    def version(d: Date): String = mkVersion(_.version, DynVer fallback d)
    def isSnapshot: Boolean      = _x.fold(true)(x => x.isDirty() || x.hasNoTags())

    def isDirty: Boolean         = _x.fold(true)(_.isDirty())
    def hasNoTags: Boolean       = _x.fold(true)(_.hasNoTags())
  }
}

// sealed just so the companion object can extend it. Shouldn't've been a case class.
sealed case class DynVer(wd: Option[File]) {
  def version(d: Date): String            = getGitDescribeOutput(d) version d
  def isSnapshot(): Boolean               = getGitDescribeOutput(new Date).isSnapshot

  def makeDynVer(d: Date): Option[String] = getGitDescribeOutput(d) map (_.version)
  def isDirty(): Boolean                  = getGitDescribeOutput(new Date).isDirty
  def hasNoTags(): Boolean                = getGitDescribeOutput(new Date).hasNoTags

  def getGitDescribeOutput(d: Date) = {
    val process = scala.sys.process.Process(s"""git describe --tags --abbrev=8 --match v[0-9]* --always --dirty=+${timestamp(d)}""", wd)
    Try(process !! impl.NoProcessLogger).toOption
      .map(_.replaceAll("-([0-9]+)-g([0-9a-f]{8})", "+$1-$2"))
      .map(GitDescribeOutput.parse)
  }

  def timestamp(d: Date): String = "%1$tY%1$tm%1$td-%1$tH%1$tM" format d
  def fallback(d: Date): String = s"HEAD+${timestamp(d)}"
}
object DynVer extends DynVer(None) with (Option[File] => DynVer)

object `package`

package impl {
  object NoProcessLogger extends scala.sys.process.ProcessLogger {
    def info(s: => String)  = ()
    def out(s: => String)   = ()
    def error(s: => String) = ()
    def err(s: => String)   = ()
    def buffer[T](f: => T)  = f
  }
}
