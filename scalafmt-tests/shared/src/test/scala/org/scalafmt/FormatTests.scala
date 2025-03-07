package org.scalafmt

import org.scalafmt.Error.Incomplete
import org.scalafmt.Error.SearchStateExploded
import org.scalafmt.config.LineEndings
import org.scalafmt.rewrite.FormatTokensRewrite
import org.scalafmt.sysops.FileOps
import org.scalafmt.sysops.PlatformFileOps
import org.scalafmt.util._

import scala.meta.parsers.ParseException

import munit.FunSuite
import munit.diff.Diff

// TODO(olafur) property test: same solution without optimization or timeout.

class FormatTests extends FunSuite with CanRunTests with FormatAssertions {
  import LoggerOps._
  lazy val onlyUnit = UnitTests.tests.exists(_.only)
  lazy val onlyManual = !onlyUnit && ManualTests.tests.exists(_.only)
  lazy val onlyOne = tests.exists(_.only)

  private val noAnsiDiffOptions = diffOptions.withForceAnsi(Some(false))

  override def ignore(t: DiffTest): Boolean = false

  override val tests = if (onlyManual) ManualTests.tests else UnitTests.tests

  tests.sortBy(x => (x.loc.path, x.loc.line)).withFilter(testShouldRun)
    .foreach { t =>
      runTest(run)(t)
      if (t.style.lineEndings.isEmpty) runTest(run)(t.copy(
        name = s"${t.name} [WIN]",
        original = crlf(t.original),
        expected = crlf(t.expected),
        style = t.style.withLineEndings(LineEndings.windows),
      ))
    }

  def run(t: DiffTest): Unit = {
    // @note munit assertions take an implicit Location generated by macros at compile time
    // this line makes them instead throw a useful exception pointing to the right stat file
    implicit val loc = t.loc
    val debug = new Debug(onlyOne)
    val runner = t.style.runner
    val result = Scalafmt.formatCode(
      t.original,
      t.style.copy(runner = HasTests.scalafmtRunner(runner, debug)),
      filename = t.filename,
    )
    debug.printTest()
    val resultEither = result.formatted.toEither
    val err = t.style.onTestFailure
    val obtained = resultEither match {
      case Left(e) if err.nonEmpty && e.getMessage.contains(err) => t.expected
      case Left(e: Incomplete) => e.formattedCode
      case Left(e: SearchStateExploded) => logger.elem(e); e.partialOutput
      case Left(e: Error.WithCode) => throw e
      case Left(e) => throw Error.WithCode(e, t.original)
      case Right(code) => code
    }
    def assertVisits(
        dbg1: Debug,
        visitsOpt1: Option[Int],
        dbgOpt2: Option[Debug],
        visitsOpt2: Option[Int],
    )(implicit loc: munit.Location) = dbg1.completedEvent
      .foreach { completedEvent =>
        val actual1 = completedEvent.totalExplored
        val actual2 = dbgOpt2.flatMap(_.completedEvent)
          .fold(actual1)(_.totalExplored)
        if (actual1 > 2000 || actual2 > 2000) {
          def error = s"stateVisits = $actual1, stateVisits2 = $actual2"
          visitsOpt1 match {
            case Some(visits1) =>
              val actual = (actual1, actual2)
              val expected = (visits1, visitsOpt2.getOrElse(visits1))
              assertEquals(actual, expected, error)
            case None => fail(s"\nExpected test to assert: $error")
          }
        }
      }
    var debug2Opt: Option[Debug] = None
    def assertObtained(implicit loc: munit.Location) = {
      assertEquals(showCR(obtained), showCR(t.expected))
      assertVisits(debug, t.stateVisits, debug2Opt, t.stateVisits2)
    }
    debugResults += saveResult(t, obtained, debug)
    if (resultEither.isLeft) {
      assertObtained
      return
    }
    val debug2 = new Debug(onlyOne)
    debug2Opt = Some(debug2)
    val result2 = Scalafmt.formatCode(
      obtained,
      t.style.copy(runner = HasTests.scalafmtRunner(runner, debug2)),
      filename = t.filename,
    )
    debug2.printTest()
    val result2Either = result2.formatted.toEither
    result2Either match {
      case Left(e: ParseException) if !onlyManual =>
        assertEquals(
          "test does not parse: " + parseException2Message(e, obtained),
          t.expected,
        )
      case Left(Error.WithCode(e: ParseException, code)) if !onlyManual =>
        assertEquals(
          "test does not parse: " + parseException2Message(e, code),
          t.expected,
        )
      case Left(e: Error.WithCode) => throw e
      case Left(e) => throw Error.WithCode(e, obtained)
      case Right(code) =>
        if (onlyManual) {
          assertEquals(code, obtained, "Idempotency violated")
          assertObtained
        } else if (code == obtained) assertObtained
        else {
          val diff = new Diff(code, obtained, noAnsiDiffOptions)
          if (diff.isEmpty) assertObtained
          else {
            val report = diff.createDiffOnlyReport()
            val eol = if (report.last == '\n') "" else "\n"
            val error = "Idempotency violated\n" + report + eol
            if (error != t.expected) failComparison(error, code, obtained)
          }
        }
    }
    if (
      result2Either.isRight && t.style.rewrite.rules.isEmpty &&
      FormatTokensRewrite.getEnabledFactories(t.style).isEmpty &&
      !t.style.assumeStandardLibraryStripMargin &&
      !FileOps.isMarkdown(t.filename) && err.isEmpty
    ) assertFormatPreservesAst(
      t.filename,
      t.original,
      obtained,
      result.config.runner,
    )
  }

  def testShouldRun(t: DiffTest): Boolean = !onlyOne || t.only

  override def afterAll(): Unit = {
    val explored = Debug.explored.get()
    logger.debug(s"Total explored: $explored")
    if (!onlyUnit && !onlyManual)
      assertEquals(explored, 2551504, "total explored")
    // TODO(olafur) don't block printing out test results.
    TestPlatformCompat.executeAndWait(PlatformFileOps.writeFile(
      FileOps.getPath("target", "index.html"),
      Report.heatmap(debugResults.result()),
    ))(
      // Travis exits right after running tests.
      if (sys.env.contains("TRAVIS")) Some(20) else None,
    )
  }
}
