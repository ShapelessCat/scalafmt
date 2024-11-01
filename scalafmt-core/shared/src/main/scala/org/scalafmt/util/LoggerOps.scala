package org.scalafmt.util

import org.scalafmt.internal.FormatToken
import org.scalafmt.internal.Split
import org.scalafmt.internal.State

import org.scalameta.FileLine
import scala.meta.Tree
import scala.meta.inputs.InputRange
import scala.meta.prettyprinters.Structure
import scala.meta.tokens.Token
import scala.meta.tokens.Token.Interpolation
import scala.meta.tokens.Tokens

import scala.annotation.tailrec
import scala.util.DynamicVariable

import sourcecode.Text

/** Debugging utility.
  */
object LoggerOps {
  private val loggerLike = new DynamicVariable[LoggerLike](Logger)
  def logger = loggerLike.value
  def logger(flag: Boolean): Unit =
    loggerLike.value = if (flag) Logger else NoLogger

  // TODO(olafur) parameterize
  def name2style[T](styles: Text[T]*): Map[String, T] = styles
    .map(x => x.source -> x.value).toMap

  def log(s: State, indent: String = "", nlIndices: Boolean = true): String = {
    val delim = s"\n$indent  "
    val policies = s.policy.policies match {
      case Nil => ""
      case p :: Nil => s";${delim}P($p)"
      case pp => pp.map(_.toString).mkString(s";${delim}P[", s",$delim  ", s"]")
    }
    @tailrec
    def getNlIndices(x: State, res: List[String]): List[String] =
      if (x.depth == 0) res
      else getNlIndices(
        x.prev,
        if (x.split.isNL) s"${x.cost}@${x.depth}" :: res else res,
      )
    val nls =
      if (nlIndices) getNlIndices(s, Nil).mkString(s"${delim}nl=[", ",", "]")
      else ""
    s"d=${s.depth} w=${s.cost}[${s.appliedPenalty}] i=${s.indentation} col=${s
        .column} #nl=${s.lineId}$policies;${delim}s=${log(s.split)}$nls"
  }
  def log(split: Split): String = s"$split"

  def log(formatToken: FormatToken): String =
    s"""|${log(formatToken.left)}
        |${log(formatToken.between: _*)}
        |${log(formatToken.right)}""".stripMargin

  def log2(formatToken: FormatToken): String = formatToken.toString
  def log2(formatToken: Option[FormatToken]): String = formatToken.fold("")(log2)

  def escape(raw: String): String = raw

  def log(tokens: Token*): String = tokens.map(log).mkString("\n")

  def cleanup(token: Token): String = token match {
    case Token.Literal() | Interpolation.Part(_) => escape(token.syntax)
        .stripPrefix("\"").stripSuffix("\"")
    case _ => token.syntax.replace("\n", "")
  }

  def log(tokens: Tokens): String = tokens.map(log).mkString("\n")

  def log(token: Token): String = logTok(token)
  def logTok(token: Token): String = f"[${token.structure}%-40s"
  def logTok(token: Option[Token]): String = token.fold("")(log)

  def log(range: InputRange): String = s"[${range.start}..${range.end})"

  def position(t: Tree): String = log(t.pos)

  def treeInfo(t: Tree): String = {
    val typeName = t.getClass.getName.stripPrefix("scala.meta.")
    val parts = typeName.split('$')
    val name = parts.length match {
      case 0 => typeName
      case 1 => parts(0)
      case _ => s"${parts(0)}.${parts(1)}"
    }
    s"$name ${position(t)}"
  }

  def log(t: Tree): String = log(t, false)
  def log(t: Tree, tokensOnly: Boolean): String = {
    val tokens = s"TOKENS: ${t.tokens.map(x => reveal(x.text)).mkString(",")}"
    if (tokensOnly) tokens
    else s"""|TYPE: ${treeInfo(t)}
             |SOURCE: $t
             |STRUCTURE: ${t.show[Structure]}
             |$tokens
             |""".stripMargin
  }

  def log(t: Option[Tree]): String = log(t, false)
  def log(t: Option[Tree], tokensOnly: Boolean): String = t
    .fold("")(log(_, tokensOnly))

  def reveal(s: String): String = s.map {
    case '\n' => '¶'
    case ' ' => '∙'
    case ch => ch
  }

  def header[T](t: T): String = {
    val line = s"=" * (t.toString.length + 3)
    s"$line\n=> $t\n$line"
  }

  sealed trait LoggerLike {
    def println(x: Any): Unit
    def debug(x: Any)(implicit fileLine: FileLine): Unit
    def elem(values: sourcecode.Text[Any]*)(implicit fileLine: FileLine): Unit
  }
  object Logger extends LoggerLike {
    def println(x: Any): Unit = Console.out.println(x)
    def debug(x: Any)(implicit fileLine: FileLine): Unit = org.scalameta.logger
      .debug(x)
    def elem(values: sourcecode.Text[Any]*)(implicit fileLine: FileLine): Unit =
      org.scalameta.logger.elem(values: _*)
  }
  object NoLogger extends LoggerLike {
    def println(x: Any): Unit = {}
    def debug(x: Any)(implicit fileLine: FileLine): Unit = {}
    def elem(values: sourcecode.Text[Any]*)(implicit
        fileLine: FileLine,
    ): Unit = {}
  }

}
