package org.scalafmt.rewrite

import org.scalafmt.config.RedundantBracesSettings
import org.scalafmt.config.ScalafmtConfig
import org.scalafmt.internal._
import org.scalafmt.util.TreeOps._

import scala.meta._
import scala.meta.tokens.{Token => T}

import scala.annotation.tailrec

object RedundantBraces extends Rewrite with FormatTokensRewrite.RuleFactory {

  import FormatTokensRewrite._

  override def enabled(implicit style: ScalafmtConfig): Boolean = true

  override def create(implicit ftoks: FormatTokens): Rule = new RedundantBraces

  private def needParensAroundParams(f: Term.FunctionTerm): Boolean =
    /* either we have parens or no type; multiple params or
     * no params guarantee parens, so we look for type and
     * parens only for a single param */
    f.paramClause match {
      case pc @ Term.ParamClause(param :: Nil, _) => param.decltpe.nonEmpty &&
        !pc.tokens.head.is[T.LeftParen]
      case _ => false
    }

  private def canRewriteBlockWithParens(b: Term.Block)(implicit
      ftoks: FormatTokens,
  ): Boolean = getBlockSingleStat(b).exists(canRewriteStatWithParens)

  private def canRewriteStatWithParens(
      t: Stat,
  )(implicit ftoks: FormatTokens): Boolean = t match {
    case f: Term.FunctionTerm => canRewriteFuncWithParens(f)
    case _: Term.Assign => false // disallowed in 2.13
    case _: Defn => false
    case _: Term.PartialFunction => false
    case Term.Block(s :: Nil) if !ftoks.isEnclosedInMatching(t) =>
      canRewriteStatWithParens(s)
    case _ => true
  }

  /* guard for statements requiring a wrapper block
   * "foo { x => y; z }" can't become "foo(x => y; z)" */
  @tailrec
  private def canRewriteFuncWithParens(
      f: Term.FunctionTerm,
      nested: Boolean = false,
  ): Boolean = !needParensAroundParams(f) &&
    (getTreeSingleStat(f.body) match {
      case Some(t: Term.FunctionTerm) => canRewriteFuncWithParens(t, true)
      case Some(_: Defn) => false
      case x => nested || x.isDefined
    })

  private def checkApply(t: Tree): Boolean = t.parent match {
    case Some(p @ Term.ArgClause(`t` :: Nil, _)) => isParentAnApply(p)
    case _ => false
  }

  private[scalafmt] def canRewriteWithParensOnRightBrace(rb: FT)(implicit
      ftoks: FormatTokens,
  ): Boolean = !ftoks.prevNonCommentBefore(rb).left.is[T.Semicolon] &&
    (rb.meta.leftOwner match { // look for "foo { bar }"
      case b: Term.Block => checkApply(b) && canRewriteBlockWithParens(b) &&
        b.parent.exists(ftoks.getLast(_) eq rb)
      case f: Term.FunctionTerm => checkApply(f) && canRewriteFuncWithParens(f)
      case t @ Term.ArgClause(arg :: Nil, _) => isParentAnApply(t) &&
        ftoks.getDelimsIfEnclosed(t).exists(_._2 eq rb) &&
        canRewriteStatWithParens(arg)
      case _ => false
    })

  private[scalafmt] def isLeftParenReplacedWithBraceOnLeft(pft: FT)(implicit
      session: Session,
  ): Boolean = session.claimedRuleOnLeft(pft)
    .exists(x => (x.how eq ReplacementType.Replace) && x.ft.right.is[T.LeftBrace])

}

/** Removes/adds curly braces where desired.
  */
class RedundantBraces(implicit val ftoks: FormatTokens)
    extends FormatTokensRewrite.Rule {

  import FormatTokensRewrite._
  import RedundantBraces._

  override def enabled(implicit style: ScalafmtConfig): Boolean =
    RedundantBraces.enabled

  override def onToken(implicit
      ft: FT,
      session: Session,
      style: ScalafmtConfig,
  ): Option[Replacement] = Option {
    ft.right match {
      case _: T.LeftBrace => onLeftBrace
      case _: T.LeftParen => onLeftParen
      case _ => null
    }
  }

  override def onRight(left: Replacement, hasFormatOff: Boolean)(implicit
      ft: FT,
      session: Session,
      style: ScalafmtConfig,
  ): Option[(Replacement, Replacement)] = Option {
    ft.right match {
      case _: T.RightBrace => onRightBrace(left)
      case _: T.RightParen => onRightParen(left, hasFormatOff)
      case _ => null
    }
  }

  private def onLeftParen(implicit ft: FT, style: ScalafmtConfig): Replacement = {
    val rt = ft.right
    val rtOwner = ft.meta.rightOwner
    def lpFunction = okToReplaceFunctionInSingleArgApply(rtOwner).map {
      case (`rt`, f) => f.body match {
          case b: Term.Block => ftoks.getHead(b) match {
              case FT(_: T.LeftBrace, _, lbm) =>
                val lb = new T.LeftBrace(rt.input, rt.dialect, rt.start)
                replaceToken("{", claim = lbm.idx - 1 :: Nil)(lb)
              case _ => null
            }
          case _ => null
        }
      case _ => null
    }
    // single-arg apply of a partial function
    // a({ case b => c; d }) change to a { case b => c; d }
    def lpPartialFunction = rtOwner match {
      case ta @ Term.ArgClause(arg :: Nil, _) if !ta.parent.is[Init] =>
        getOpeningParen(ta).map { lp =>
          if (lp.ne(rt) || getBlockNestedPartialFunction(arg).isEmpty) null
          else ftoks.nextNonCommentAfter(ft) match {
            case FT(lt, _: T.LeftBrace, lbm) =>
              if (lt eq rt) removeToken(claim = lbm.idx :: Nil)
              else {
                val lbo = Some(lbm.rightOwner)
                val lb = new T.LeftBrace(rt.input, rt.dialect, rt.start)
                replaceToken("{", owner = lbo, claim = lbm.idx :: Nil)(lb)
              }
            case _ => null
          }
        }
      case _ => None
    }

    lpFunction.orElse(lpPartialFunction).orNull
  }

  private def onRightParen(left: Replacement, hasFormatOff: Boolean)(implicit
      ft: FT,
      session: Session,
      style: ScalafmtConfig,
  ): (Replacement, Replacement) = left.how match {
    case ReplacementType.Remove =>
      getRightBraceBeforeRightParen(shouldBeRemoved = false).map { rb =>
        ft.meta.rightOwner match {
          case ac: Term.ArgClause => ftoks.matchingOptLeft(rb).map(ftoks.prev)
              .foreach { lb =>
                session.rule[RemoveScala3OptionalBraces].foreach { r =>
                  session.getClaimed(lb.meta.idx).foreach { case (leftIdx, _) =>
                    val repl = r.onLeftForArgClause(ac)(lb, left.style)
                    if (null ne repl) {
                      implicit val ft: FT = ftoks.prev(rb)
                      repl.onRightAndClaim(hasFormatOff, leftIdx)
                    }
                  }
                }
              }
          case _ =>
        }
        (left, removeToken)
      }.orNull

    case ReplacementType.Replace if left.ft.right.is[T.LeftBrace] =>
      val pftOpt = getRightBraceBeforeRightParen(shouldBeRemoved = true)
      def replaceIfAfterRightBrace = pftOpt.map { pft =>
        val rb = pft.left
        // move right to the end of the function
        val rType = new ReplacementType.RemoveAndResurrect(ftoks.prev(pft))
        val rbo = Some(left.ft.rightOwner)
        left -> replaceToken("}", owner = rbo, rtype = rType) {
          // create a shifted token so that any child tree wouldn't own it
          new T.RightBrace(rb.input, rb.dialect, rb.start + 1)
        }
      }
      (ft.meta.rightOwner match {
        case ac: Term.ArgClause => session.rule[RemoveScala3OptionalBraces]
            .flatMap { r =>
              val repl = r.onLeftForArgClause(ac)(left.ft, left.style)
              if (repl eq null) None else repl.onRight(hasFormatOff)
            }
        case _ => None
      }).getOrElse {
        replaceIfAfterRightBrace.orNull // don't know how to Replace
      }
    case _ => null
  }

  private def getRightBraceBeforeRightParen(
      shouldBeRemoved: Boolean,
  )(implicit ft: FT, session: Session, style: ScalafmtConfig): Option[FT] = {
    val pft = ftoks.prevNonComment(ft)
    val ok = pft.left match {
      case _: T.Comma => // looks like trailing comma
        val pft2 = ftoks.prevNonCommentBefore(pft)
        pft2.left.is[T.RightBrace] &&
        session.isRemovedOnLeft(pft2, true) == shouldBeRemoved &&
        session.isRemovedOnLeftOpt(pft).getOrElse {
          val crt = ftoks.prev(pft)
          val crepl = Replacement(this, crt, ReplacementType.Remove, style)
          session.claim(crepl)(crt)
          true
        }
      case _: T.RightBrace =>
        @tailrec
        def shouldNotBeRemoved(xft: FT): Boolean =
          !session.isRemovedOnLeft(xft, ok = true) || {
            val pxft = ftoks.prevNonCommentBefore(xft)
            pxft.left.is[T.RightBrace] && shouldNotBeRemoved(pxft)
          }
        if (shouldBeRemoved) session.isRemovedOnLeft(pft, ok = true)
        else shouldNotBeRemoved(pft)
      case _ => false
    }
    if (ok) Some(pft) else None
  }

  private def onLeftBrace(implicit
      ft: FT,
      session: Session,
      style: ScalafmtConfig,
  ): Replacement = onLeftBrace(ft.meta.rightOwner)

  private def onLeftBrace(
      owner: Tree,
  )(implicit ft: FT, session: Session, style: ScalafmtConfig): Replacement = {
    def handleInterpolation =
      if (
        style.rewrite.redundantBraces.stringInterpolation &&
        processInterpolation
      ) removeToken
      else null

    owner match {
      case t: Term.FunctionTerm if t.tokens.last.is[T.RightBrace] =>
        if (!okToRemoveFunctionInApplyOrInit(t)) null else removeToken
      case _: Term.PartialFunction =>
        val pft = ftoks.prevNonComment(ft)
        pft.left match {
          case _: T.LeftParen if isLeftParenReplacedWithBraceOnLeft(pft) =>
            removeToken
          case _ => null
        }
      case t: Term.Block => t.parent match {
          case Some(f: Term.FunctionTerm)
              if okToReplaceFunctionInSingleArgApply(f) => removeToken
          case Some(_: Term.Interpolate) => handleInterpolation
          case Some(_: Term.Xml) => null
          case Some(_: Term.Annotate) => null
          case Some(_: Term.Return) if ftoks.next(ft).right.is[T.Comment] =>
            null
          case Some(p: Case) =>
            val ok = settings.generalExpressions &&
              ((p.body eq t) || shouldRemoveSingleStatBlock(t))
            if (ok) removeToken else null
          case _ => if (processBlock(t)) removeToken else null
        }
      case _: Term.Interpolate => handleInterpolation
      case Importer(_, List(x))
          if !(x.is[Importee.Rename] || x.is[Importee.Unimport]) ||
            style.dialect.allowAsForImportRename &&
            (ConvertToNewScala3Syntax.enabled ||
              !x.tokens.exists(_.is[T.RightArrow])) => removeToken
      case t: Ctor.Block
          if t.stats.isEmpty && isDefnBodiesEnabled(noParams = false) =>
        val prevIsEquals = ftoks.prevNonComment(ft).left.is[T.Equals]
        if (prevIsEquals) removeToken
        else replaceTokenBy("=", t.parent) { x =>
          new T.Equals(x.input, x.dialect, x.start)
        }
      case _ => null
    }
  }

  private def onRightBrace(left: Replacement)(implicit
      ft: FT,
      session: Session,
      style: ScalafmtConfig,
  ): (Replacement, Replacement) = {
    @tailrec
    def okComment(xft: FT): Boolean = ftoks.prevNotTrailingComment(xft) match {
      case Right(x) => (x eq xft) || !session.isRemovedOnLeft(x, true) || {
          val pft = ftoks.prev(x)
          pft.noBreak && okComment(pft)
        }
      case _ => false
    }
    val ok = ft.meta.rightOwner match {
      case t: Term.Block => !braceSeparatesTwoXmlTokens &&
        (ftoks.prevNonComment(ft) match {
          case FT(_: T.Semicolon, _, m) =>
            val plo = m.leftOwner
            (plo eq t) || !plo.parent.contains(t)
          case _ => true
        }) &&
        (style.dialect.allowSignificantIndentation ||
          okComment(ft) && !elseAfterRightBraceThenpOnLeft)
      case _ => true
    }
    if (ok) (left, removeToken) else null
  }

  private def settings(implicit
      style: ScalafmtConfig,
  ): RedundantBracesSettings = style.rewrite.redundantBraces

  private def processInterpolation(implicit ft: FT): Boolean = {
    def isIdentifierAtStart(value: String) = value.headOption
      .exists(x => Character.isLetterOrDigit(x) || x == '_')

    /** we need to keep braces
      *   - for interpolated literal identifiers: {{{s"string ${`type`}"}}}
      *   - and identifiers starting with '_': {{{s"string %{_id}"}}}, otherwise
      *     formatting will result in compilation error (see
      *     https://github.com/scalameta/scalafmt/issues/1420)
      */
    def canRemoveAroundName(name: String): Boolean = name.headOption.forall {
      case '_' | '$' => false
      case '`' => name.length <= 1 || name.last != '`'
      case _ => true
    }

    val ft2 = ftoks(ft, 2) // should point to "name}"
    ft2.right.is[T.RightBrace] &&
    (ft2.meta.leftOwner match {
      case t: Term.Name => canRemoveAroundName(t.text)
      case _ => false
    }) &&
    (ftoks(ft2, 2).right match { // skip splice end, to get interpolation part
      case T.Interpolation.Part(value) => !isIdentifierAtStart(value)
      case _ => false
    })
  }

  private def okToReplaceFunctionInSingleArgApply(f: Term.FunctionTerm)(implicit
      style: ScalafmtConfig,
  ): Boolean = f.parent.flatMap(okToReplaceFunctionInSingleArgApply)
    .exists(_._2 eq f)

  private def getOpeningParen(t: Term.ArgClause): Option[T.LeftParen] = ftoks
    .getHead(t).left match {
    case lp: T.LeftParen => Some(lp)
    case _ => None
  }

  // single-arg apply of a lambda
  // a(b => { c; d }) change to a { b => c; d }
  private def okToReplaceFunctionInSingleArgApply(tree: Tree)(implicit
      style: ScalafmtConfig,
  ): Option[(T.LeftParen, Term.FunctionTerm)] = tree match {
    case ta @ Term.ArgClause((func: Term.FunctionTerm) :: Nil, _) if {
          val body = func.body
          (body.is[Term.Block] || func.tokens.last.ne(body.tokens.last)) &&
          isParentAnApply(ta) && okToRemoveAroundFunctionBody(body, true)
        } => getOpeningParen(ta).map((_, func))
    case _ => None
  }

  // multi-arg apply of single-stat lambdas
  // a(b => { c }, d => { e }) change to a(b => c, d => e)
  // a single-stat lambda with braces can be converted to one without braces,
  // but the reverse conversion isn't always possible
  private def okToRemoveFunctionInApplyOrInit(
      t: Term.FunctionTerm,
  )(implicit style: ScalafmtConfig): Boolean = t.parent match {
    case Some(p: Term.ArgClause) => p.parent match {
        case Some(_: Init) => okToRemoveAroundFunctionBody(t.body, false)
        case Some(_: Term.Apply) => getOpeningParen(p).isDefined &&
          okToRemoveAroundFunctionBody(t.body, p.values)
        case _ => false
      }
    case _ => false
  }

  private def processBlock(b: Term.Block)(implicit
      ft: FT,
      session: Session,
      style: ScalafmtConfig,
  ): Boolean = b.stats.nonEmpty && b.tokens.headOption.contains(ft.right) &&
    okToRemoveBlock(b) && !braceSeparatesTwoXmlTokens &&
    (b.parent match {
      case Some(p: Term.ArgClause) => p.parent.exists(checkValidInfixParent)
      case Some(p) => checkValidInfixParent(p)
      case _ => true
    })

  private def checkValidInfixParent(
      p: Tree,
  )(implicit ft: FT, style: ScalafmtConfig): Boolean = p match {
    case _: Member.Infix =>
      /* for infix, we will preserve the block unless the closing brace
       * follows a non-whitespace character on the same line as we don't
       * break lines around infix expressions.
       * we shouldn't join with the previous line (which might also end
       * in a comment), and if we keep the break before the right brace
       * we are removing, that will likely invalidate the expression. */
      def checkOpen = {
        val nft = ftoks.next(ft)
        nft.noBreak || style.formatInfix(p) && !nft.right.is[T.Comment]
      }
      def checkClose = {
        val nft = ftoks.prev(ftoks.matchingRight(ft))
        nft.noBreak || style.formatInfix(p) && !nft.left.is[T.Comment]
      }
      checkOpen && checkClose
    case _ => true
  }

  private def okToRemoveBlock(
      b: Term.Block,
  )(implicit ft: FT, style: ScalafmtConfig, session: Session): Boolean = b
    .parent.exists {
      case t: Term.ArgClause if isParentAnApply(t) =>
        // Example: as.map { _.toString }
        // Leave this alone for now.
        // In future there should be an option to surround such expressions with parens instead of braces
        if (isSeqMulti(t.values)) okToRemoveBlockWithinApply(b)
        else ftoks.getDelimsIfEnclosed(t).exists { case (ldelim, _) =>
          def isArgDelimRemoved = session.isRemovedOnLeft(ldelim, ok = true)
          def hasExpressionWithBraces: Boolean =
            // there's a nested expression with braces that will be kept
            getSingleStatIfLineSpanOk(b)
              .exists(getBlockNestedPartialFunction(_).isDefined)
          ldelim.left match {
            case lb: T.LeftBrace =>
              // either arg clause has separate braces, or we guarantee braces
              (ft.right ne lb) && !isArgDelimRemoved || hasExpressionWithBraces
            case _: T.LeftParen => isLeftParenReplacedWithBraceOnLeft(ldelim) ||
              hasExpressionWithBraces
            case _ => false
          }
        }

      case d: Defn.Def =>
        def disqualifiedByUnit = !settings.includeUnitMethods &&
          d.decltpe.exists {
            case Type.Name("Unit") => true
            case _ => false
          }
        checkBlockAsBody(b, d.body, noParams = d.paramClauseGroups.isEmpty) &&
        !isProcedureSyntax(d) && !disqualifiedByUnit

      case d: Defn.Var => checkBlockAsBody(b, d.body, noParams = true)
      case d: Defn.Val => checkBlockAsBody(b, d.rhs, noParams = true)
      case d: Defn.Type =>
        checkBlockAsBody(b, d.body, noParams = d.tparamClause.isEmpty)
      case d: Defn.Macro =>
        checkBlockAsBody(b, d.body, noParams = d.paramClauseGroups.isEmpty)
      case d: Defn.GivenAlias =>
        checkBlockAsBody(b, d.body, noParams = d.paramClauseGroup.isEmpty)

      case p: Term.FunctionTerm if isFunctionWithBraces(p) =>
        okToRemoveAroundFunctionBody(b, okIfMultipleStats = true)

      case _: Term.If => settings.ifElseExpressions &&
        shouldRemoveSingleStatBlock(b)

      case Term.Block(List(`b`)) => true

      case _: Term.QuotedMacroExpr | _: Term.SplicedMacroExpr => false

      case _ => settings.generalExpressions && shouldRemoveSingleStatBlock(b)
    }

  private def checkBlockAsBody(b: Term.Block, rhs: Tree, noParams: => Boolean)(
      implicit style: ScalafmtConfig,
  ): Boolean = rhs.eq(b) && getSingleStatIfLineSpanOk(b).exists(innerOk(b)) &&
    isDefnBodiesEnabled(noParams)

  private def isDefnBodiesEnabled(
      noParams: => Boolean,
  )(implicit style: ScalafmtConfig): Boolean = settings.defnBodies match {
    case RedundantBracesSettings.DefnBodies.all => true
    case RedundantBracesSettings.DefnBodies.none => false
    case RedundantBracesSettings.DefnBodies.noParams => noParams
  }

  private def innerOk(b: Term.Block)(s: Stat): Boolean = s match {
    case _: Term.FunctionTerm | _: Term.Xml => false
    case t: Term.NewAnonymous =>
      // can't allow: new A with B .foo
      // can allow if: no ".foo", no "with B", or has braces
      !b.parent.is[Term.Select] || t.templ.inits.lengthCompare(1) <= 0 ||
      t.templ.body.stats.nonEmpty || t.tokens.last.is[T.RightBrace]
    case _: Term => true
    case _ => false
  }

  private def okToRemoveBlockWithinApply(b: Term.Block)(implicit
      style: ScalafmtConfig,
  ): Boolean = getSingleStatIfLineSpanOk(b).exists {
    case f: Term.FunctionTerm => !needParensAroundParams(f) && {
        val fb = f.body
        !fb.is[Term.Block] ||
        // don't rewrite block if the inner block will be rewritten, too
        // sometimes a function body block doesn't have braces
        fb.tokens.headOption.is[T.LeftBrace] &&
        !okToRemoveAroundFunctionBody(fb, true)
      }
    case _: Term.Assign => false // f({ a = b }) is not the same as f(a = b)
    case _ => true
  }

  /** Some blocks look redundant but aren't */
  private def shouldRemoveSingleStatBlock(
      b: Term.Block,
  )(implicit ft: FT, style: ScalafmtConfig, session: Session): Boolean =
    getSingleStatIfLineSpanOk(b).exists { stat =>
      @tailrec
      def keepForParent(tree: Tree): Boolean = tree match {
        case t: Term.ArgClause => t.parent match {
            case Some(p) => keepForParent(p)
            case _ => true
          }
        case _: Term.TryClause =>
          def matching(tok: T) = ftoks.matchingOptLeft(ftoks(tok))
          // "try (x).y" or "try { x }.y" isn't supported until scala 2.13
          // same is true with "try (a, b)" and "try ()"
          // return true if rewrite is not OK
          // inside exists, return true if rewrite is OK
          !stat.tokens.headOption.exists {
            case x: T.LeftParen => matching(x) match {
                case Some(y) if y.left ne stat.tokens.last =>
                  session.rule[RedundantParens].exists {
                    _.onToken(ftoks(x, -1), session, style).exists(_.isRemove)
                  }
                case Some(_) if !style.dialect.allowTryWithAnyExpr =>
                  !stat.isAny[Term.Tuple, Lit.Unit]
                case _ => true
              }
            case x: T.LeftBrace => matching(x) match {
                case Some(y) if y.left ne stat.tokens.last =>
                  findFirstTreeBetween(stat, x, y.left).exists {
                    case z: Term.Block => okToRemoveBlock(z)
                    case _ => false
                  }
                case _ => true
              }
            case _ => true
          }

        // can't do it for try until 2.13.3
        case _ if RewriteCtx.isPrefixExpr(stat) => false

        case parentIf: Term.If if stat.is[Term.If] =>
          // if (a) { if (b) c } else d
          //   ↑ cannot be replaced by ↓
          // if (a) if (b) c else d
          //   which would be equivalent to
          // if (a) { if (b) c else d }
          (parentIf.thenp eq b) && !ifWithoutElse(parentIf) &&
          existsIfWithoutElse(stat.asInstanceOf[Term.If])

        case p: Term.ApplyInfix => stat match {
            case t: Term.ApplyInfix =>
              val useRight = hasSingleElement(p.argClause, b)
              SyntacticGroupOps.groupNeedsParenthesis(
                TreeSyntacticGroup(p),
                TreeSyntacticGroup(t),
                if (useRight) Side.Right else Side.Left,
              )
            case _ => true // don't allow other non-infix
          }

        case p: Term.MatchLike => p.expr eq b
        case p: Type.Match => p.tpe eq b

        case p: Term.ForClause if p.body eq b =>
          @tailrec
          def iter(t: Tree): Boolean = t match {
            case _: Term.Do => true
            case Term.Block(x :: Nil) => iter(x)
            case _ => false
          }
          iter(stat)

        case parent => SyntacticGroupOps.groupNeedsParenthesis(
            TreeSyntacticGroup(parent),
            TreeSyntacticGroup(stat),
            Side.Left,
          )
      }

      innerOk(b)(stat) && !b.parent.exists(keepForParent)
    }

  @inline
  private def okToRemoveAroundFunctionBody(b: Term, s: Seq[Tree])(implicit
      style: ScalafmtConfig,
  ): Boolean = okToRemoveAroundFunctionBody(b, isSeqSingle(s))

  private def okToRemoveAroundFunctionBody(
      b: Term,
      okIfMultipleStats: => Boolean,
  )(implicit style: ScalafmtConfig): Boolean =
    isDefnBodiesEnabled(noParams = false) &&
      (getTreeSingleStat(b) match {
        case Some(_: Term.PartialFunction) => false
        case Some(_: Term.Block) => true
        case Some(s) => okLineSpan(s)
        case _ => okIfMultipleStats
      })

  @tailrec
  private def getBlockNestedPartialFunction(
      tree: Tree,
  ): Option[Term.PartialFunction] = tree match {
    case x: Term.PartialFunction => Some(x)
    case Term.Block(x :: Nil) => getBlockNestedPartialFunction(x)
    case _ => None
  }

  private def getSingleStatIfLineSpanOk(b: Term.Block)(implicit
      style: ScalafmtConfig,
  ): Option[Stat] = getBlockSingleStat(b).filter(okLineSpan(_))

  private def okLineSpan(tree: Tree)(implicit style: ScalafmtConfig): Boolean =
    getTreeLineSpan(tree) <= settings.maxBreaks

  private def braceSeparatesTwoXmlTokens(implicit ft: FT): Boolean = ft.left
    .is[T.Xml.End] && ftoks.next(ft).right.is[T.Xml.Start]

  private def elseAfterRightBraceThenpOnLeft(implicit
      ft: FT,
      ftoks: FormatTokens,
      session: Session,
  ): Boolean = ftoks.nextNonCommentAfter(ft).right.is[T.KwElse] && {
    val pft = ftoks.findToken(ft, ftoks.prev) { xft =>
      xft.left match {
        case _: T.Comment => false
        case _: T.RightBrace => !session.isRemovedOnLeft(xft, ok = true)
        case _ => true
      }
    }
    val rbOwner = ft.rightOwner
    findTreeWithParent(pft.leftOwner) { p =>
      if (p eq rbOwner) Some(false)
      else p.parent match {
        case None => Some(false)
        case Some(pp: Term.If) if pp.thenp eq p => Some(true)
        case _ => None
      }
    }.isDefined
  }

}
