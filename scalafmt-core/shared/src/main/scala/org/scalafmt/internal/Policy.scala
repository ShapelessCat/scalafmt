package org.scalafmt.internal

import org.scalafmt.util.LoggerOps

import org.scalameta.FileLine
import scala.meta.tokens.{Token => T}

import scala.language.implicitConversions

/** The decision made by [[Router]].
  *
  * Used by [[Policy]] to enforce non-local formatting.
  */
abstract class Policy {
  import Policy._

  /** applied to every decision until expire */
  def f: Pf
  def rank: Int

  def filter(pred: Clause => Boolean): Policy
  def exists(pred: Clause => Boolean): Boolean
  def maxEndPos: End.WithPos
  def appliesUntil(nextft: FT)(pred: Clause => Boolean): Boolean
  def unexpired(split: Split, nextft: FT): Policy
  def noDequeue: Boolean
  def switch(trigger: T, on: Boolean): Policy

  def &(other: Policy): Policy =
    if (other.isEmpty) this else new AndThen(this, other)
  def |(other: Policy): Policy =
    if (other.isEmpty) this else new OrElse(this, other)
  def ==>(other: Policy): Policy =
    if (other.isEmpty) this else new Relay(this, other)
  def <==(pos: End.WithPos): Policy = new Expire(this, pos)
  def ?(flag: Boolean): Policy = if (flag) this else NoPolicy

  @inline
  final def unexpiredOpt(split: Split, nextft: FT): Option[Policy] =
    Some(unexpired(split, nextft)).filter(_.nonEmpty)

  @inline
  final def &(other: Option[Policy]): Policy = other.fold(this)(&)
  @inline
  final def |(other: Option[Policy]): Policy = other.fold(this)(|)
  @inline
  final def ==>(other: Option[Policy]): Policy = other.fold(this)(==>)

  @inline
  final def <(exp: FT): Policy = <==(End < exp)
  @inline
  final def <=(exp: FT): Policy = <==(End <= exp)
  @inline
  final def >=(exp: FT): Policy = <==(End >= exp)
  @inline
  final def >(exp: FT): Policy = <==(End > exp)

  @inline
  final def isEmpty: Boolean = this eq NoPolicy
  @inline
  final def nonEmpty: Boolean = this ne NoPolicy

}

object Policy {

  type Pf = PartialFunction[Decision, Seq[Split]]

  @inline
  def noPolicy: Policy = NoPolicy

  object NoPolicy extends Policy {
    override def toString: String = "NoPolicy"
    override def f: Pf = PartialFunction.empty
    override def |(other: Policy): Policy = other
    override def &(other: Policy): Policy = other
    override def ==>(other: Policy): Policy = other
    override def <==(pos: End.WithPos): Policy = this
    override def ?(flag: Boolean): Policy = this

    override def rank: Int = 0
    override def unexpired(split: Split, nextft: FT): Policy = this

    override def maxEndPos: End.WithPos = End.Never
    override def appliesUntil(nextft: FT)(pred: Clause => Boolean): Boolean =
      false
    override def filter(pred: Clause => Boolean): Policy = this
    override def exists(pred: Clause => Boolean): Boolean = false
    override def switch(trigger: T, on: Boolean): Policy = this
    override def noDequeue: Boolean = false
  }

  def apply(prefix: String, noDequeue: Boolean = false, rank: Int = 0)(f: Pf)(
      implicit fl: FileLine,
  ): Policy = new ClauseImpl(f, prefix, noDequeue, rank)

  def after(trigger: T, policy: Policy)(implicit fl: FileLine): Policy =
    new Switch(NoPolicy, trigger, policy)

  def before(policy: Policy, trigger: T)(implicit fl: FileLine): Policy =
    new Switch(policy, trigger, NoPolicy)

  def beforeLeft(
      exp: FT,
      prefix: String,
      noDequeue: Boolean = false,
      rank: Int = 0,
  )(f: Pf): Policy = apply(prefix, noDequeue, rank)(f) < exp

  def onLeft(exp: FT, prefix: String, noDequeue: Boolean = false, rank: Int = 0)(
      f: Pf,
  ): Policy = apply(prefix, noDequeue, rank = rank)(f) <= exp

  def onRight(exp: FT, prefix: String, noDequeue: Boolean = false, rank: Int = 0)(
      f: Pf,
  ): Policy = apply(prefix, noDequeue, rank = rank)(f) >= exp

  def afterRight(
      exp: FT,
      prefix: String,
      noDequeue: Boolean = false,
      rank: Int = 0,
  )(f: Pf): Policy = apply(prefix, noDequeue, rank)(f) > exp

  def onlyFor(on: FT, prefix: String, noDequeue: Boolean = false, rank: Int = 0)(
      f: Seq[Split] => Seq[Split],
  ): Policy = End <= on ==>
    onRight(on, s"$prefix[${on.idx}]", noDequeue, rank) {
      case Decision(`on`, ss) => f(ss)
    }

  abstract class Clause(implicit val fl: FileLine) extends Policy {
    def prefix: String
    def suffix: String = ""

    override def toString = {
      val prefixWithColon = prefix match {
        case "" => ""
        case x => s"$x:"
      }
      val noDeqPrefix = if (noDequeue) "!" else ""
      val suffixWithColon = suffix match {
        case "" => ""
        case x => s":$x"
      }
      s"$prefixWithColon[$fl]${noDeqPrefix}d$suffixWithColon"
    }

    override def unexpired(split: Split, nextft: FT): Policy = this

    override def filter(pred: Clause => Boolean): Policy =
      if (pred(this)) this else NoPolicy

    override def exists(pred: Clause => Boolean): Boolean = pred(this)

    override def switch(trigger: T, on: Boolean): Policy = this

    override def maxEndPos: End.WithPos = End.Never
    override def appliesUntil(nextft: FT)(pred: Clause => Boolean): Boolean =
      pred(this)
  }

  private class ClauseImpl(
      val f: Pf,
      val prefix: String,
      val noDequeue: Boolean,
      val rank: Int = 0,
  )(implicit fl: FileLine)
      extends Clause

  abstract class WithConv extends Policy {
    override def unexpired(split: Split, nextft: FT): Policy =
      conv(_.unexpired(split, nextft))

    override def filter(pred: Clause => Boolean): Policy = conv(_.filter(pred))

    override def switch(trigger: T, on: Boolean): Policy =
      conv(_.switch(trigger, on))

    protected def conv(pred: Policy => Policy): Policy
  }

  private class OrElse(p1: Policy, p2: Policy) extends WithConv {
    override lazy val f: Pf = p1.f.orElse(p2.f)

    override def rank: Int = math.min(p1.rank, p2.rank)

    override def noDequeue: Boolean = p1.noDequeue || p2.noDequeue

    override def toString: String = s"($p1 | $p2)"

    protected def conv(pred: Policy => Policy): Policy = {
      val np1 = pred(p1)
      val np2 = pred(p2)
      if (np1.eq(p1) && np2.eq(p2)) this else np1 | np2
    }

    override def maxEndPos: End.WithPos = p1.maxEndPos.max(p2.maxEndPos)
    override def appliesUntil(nextft: FT)(pred: Clause => Boolean): Boolean = p1
      .appliesUntil(nextft)(pred) && p2.appliesUntil(nextft)(pred)

    override def exists(pred: Clause => Boolean): Boolean = p1.exists(pred) ||
      p2.exists(pred)
  }

  private class AndThen(p1: Policy, p2: Policy) extends WithConv {
    override lazy val f: Pf = { case x =>
      p2.f.applyOrElse(
        p1.f.andThen(x.withSplits _).applyOrElse(x, identity[Decision]),
        (y: Decision) => y.splits,
      )
    }

    override def rank: Int = math.min(p1.rank, p2.rank)

    override def noDequeue: Boolean = p1.noDequeue || p2.noDequeue

    override def toString: String = s"($p1 & $p2)"

    protected def conv(pred: Policy => Policy): Policy = {
      val np1 = pred(p1)
      val np2 = pred(p2)
      if (np1.eq(p1) && np2.eq(p2)) this else np1 & np2
    }

    override def maxEndPos: End.WithPos = p1.maxEndPos.max(p2.maxEndPos)
    override def appliesUntil(nextft: FT)(pred: Clause => Boolean): Boolean = p1
      .appliesUntil(nextft)(pred) || p2.appliesUntil(nextft)(pred)

    override def exists(pred: Clause => Boolean): Boolean = p1.exists(pred) ||
      p2.exists(pred)
  }

  private class Expire(policy: Policy, endPolicy: End.WithPos)
      extends WithConv {
    override def f: Pf = policy.f
    override def rank: Int = policy.rank

    override def <==(pos: End.WithPos): Policy =
      if (pos >= endPolicy) this else policy <== pos

    override def switch(trigger: T, on: Boolean): Policy = {
      val res = policy.switch(trigger, on)
      if (res eq policy) this else res <== endPolicy
    }
    override def unexpired(split: Split, nextft: FT): Policy =
      if (!endPolicy.notExpiredBy(nextft)) NoPolicy
      else super.unexpired(split, nextft)
    override def noDequeue: Boolean = policy.noDequeue
    override def toString: String = s"$policy <== $endPolicy"

    protected def conv(func: Policy => Policy): Policy = {
      val filtered = func(policy)
      if (filtered eq policy) this else filtered <== endPolicy
    }

    override def maxEndPos: End.WithPos = endPolicy.min(policy.maxEndPos)
    override def appliesUntil(nextft: FT)(pred: Clause => Boolean): Boolean =
      endPolicy.notExpiredBy(nextft) && policy.appliesUntil(nextft)(pred)

    override def exists(pred: Clause => Boolean): Boolean = policy.exists(pred)
  }

  private class Delay(policy: Policy, begPolicy: End.WithPos) extends Policy {
    override def f: Pf = PartialFunction.empty
    override def rank: Int = 0

    override def <==(pos: End.WithPos): Policy =
      if (pos <= begPolicy) NoPolicy else new Expire(this, pos)

    override def filter(pred: Clause => Boolean): Policy = this
    override def exists(pred: Clause => Boolean): Boolean = policy.exists(pred)
    override def switch(trigger: T, on: Boolean): Policy = this
    override def unexpired(split: Split, nextft: FT): Policy =
      if (begPolicy.notExpiredBy(nextft)) this
      else policy.unexpired(split, nextft)
    override def noDequeue: Boolean = policy.noDequeue
    override def toString: String = s"$begPolicy ==> $policy"

    override def maxEndPos: End.WithPos = begPolicy.max(policy.maxEndPos)
    override def appliesUntil(nextft: FT)(pred: Clause => Boolean): Boolean =
      false
  }

  abstract class WithBeforeAndAfter extends WithConv {
    def before: Policy
    def after: Policy

    protected def withBefore(before: Policy)(func: Policy => Policy): Policy

    override protected def conv(func: Policy => Policy): Policy = {
      val filtered = func(before)
      if (filtered eq before) this else withBefore(filtered)(func)
    }

    override def f: Pf = before.f
    override def rank: Int = before.rank
    override def noDequeue: Boolean = before.noDequeue
    override def maxEndPos: End.WithPos = before.maxEndPos.max(after.maxEndPos)

    override def appliesUntil(nextft: FT)(pred: Clause => Boolean): Boolean =
      before.appliesUntil(nextft)(pred) && after.appliesUntil(nextft)(pred)

    override def exists(pred: Clause => Boolean): Boolean = before
      .exists(pred) || after.exists(pred)
  }

  private class Relay(val before: Policy, val after: Policy)
      extends WithBeforeAndAfter {
    override def toString: String = s"$before ==> $after"
    override protected def withBefore(before: Policy)(
        func: Policy => Policy,
    ): Policy = if (before.isEmpty) func(after) else new Relay(before, after)
  }

  class RelayOnSplit(
      val before: Policy,
      trigger: (Split, FT) => Boolean,
      triggerEnd: End.WithPos,
      val after: Policy,
  )(implicit fl: FileLine)
      extends WithBeforeAndAfter {
    override def unexpired(split: Split, nextft: FT): Policy =
      if (trigger(split, nextft)) after.unexpired(split, nextft)
      else if (!triggerEnd.notExpiredBy(nextft)) NoPolicy
      else super.unexpired(split, nextft)

    override def toString: String = s"REL?:[$fl]($before ??? $after)"

    override protected def withBefore(before: Policy)(
        func: Policy => Policy,
    ): Policy = new RelayOnSplit(before, trigger, triggerEnd, after)
  }

  object RelayOnSplit {
    def by(triggerEnd: End.WithPos)(
        trigger: (Split, FT) => Boolean,
    )(before: Policy)(after: Policy)(implicit fl: FileLine): Policy =
      if (before.isEmpty) after
      else new RelayOnSplit(before, trigger, triggerEnd, after)
    def apply(trigger: (Split, FT) => Boolean)(before: Policy)(after: Policy)(
        implicit fl: FileLine,
    ): Policy = by(End.Never)(trigger)(before)(after)
  }

  class Switch(val before: Policy, trigger: T, val after: Policy)(implicit
      fl: FileLine,
  ) extends WithBeforeAndAfter {
    override def switch(trigger: T, on: Boolean): Policy =
      if (trigger ne this.trigger) super.switch(trigger, on)
      else if (on) before
      else after.switch(trigger, on = false)
    override def toString: String = s"SW:[$fl]($before,$trigger,$after)"

    override protected def withBefore(before: Policy)(
        func: Policy => Policy,
    ): Policy = new Switch(before, trigger, after)
  }

  final class Map(
      val noDequeue: Boolean = false,
      val rank: Int = 0,
      desc: => String = "",
  )(pred: Split => Split)
      extends Clause {
    private object PredicateDecision {
      def unapply(d: Decision): Option[Seq[Split]] = {
        var replaced = false
        def applyMap(s: Split): Option[Split] = Option(pred(s)).filter { ss =>
          (s eq ss) || {
            replaced = true
            !ss.isIgnored
          }
        }
        val splits = d.splits.flatMap(applyMap)
        if (replaced) Some(splits) else None
      }
    }
    override val f: Pf = { case PredicateDecision(ss) => ss }
    override def prefix: String = {
      val evalDesc = desc
      if (evalDesc.isEmpty) "MAP" else s"MAP[$evalDesc]"
    }
  }

  sealed trait End extends (FT => End.WithPos) {
    def apply(exp: FT): End.WithPos
  }
  object End {
    def <(exp: FT): End.WithPos = BeforeLeft(exp)
    def <=(exp: FT): End.WithPos = OnLeft(exp)
    def >=(exp: FT): End.WithPos = OnRight(exp)
    def >(exp: FT): End.WithPos = AfterRight(exp)

    @inline
    private def getDescWithoutPos(token: T): String = LoggerOps
      .tokWithoutPos(token)

    sealed trait WithPos extends Ordered[WithPos] {
      val endIdx: Int
      def notExpiredBy(ft: FT): Boolean = ft.idx <= endIdx
      def ==>(policy: Policy): Policy =
        if (policy.isEmpty) NoPolicy else new Delay(policy, this)
      override final def compare(that: WithPos): Int = endIdx - that.endIdx
      def max(that: WithPos): WithPos = if (this < that) that else this
      def min(that: WithPos): WithPos = if (this > that) that else this
    }
    case object BeforeLeft extends End {
      def apply(exp: FT): WithPos = new End.WithPos {
        val endIdx: Int = exp.idx - 2
        override def toString: String =
          s"<${getDescWithoutPos(exp.left)}[${exp.idx}]"
      }
    }
    case object OnLeft extends End {
      def apply(exp: FT): WithPos = new End.WithPos {
        val endIdx: Int = exp.idx - 1
        override def toString: String =
          s"<=${getDescWithoutPos(exp.left)}[${exp.idx}]"
      }
    }
    case object OnRight extends End {
      def apply(exp: FT): WithPos = new End.WithPos {
        val endIdx: Int = exp.idx
        override def toString: String =
          s">=${getDescWithoutPos(exp.left)}[${exp.idx}]"
      }
    }
    case object AfterRight extends End {
      def apply(exp: FT): WithPos = new End.WithPos {
        val endIdx: Int = exp.idx + 1
        override def toString: String =
          s">${getDescWithoutPos(exp.left)}[${exp.idx}]"
      }
    }
    case object Never extends WithPos {
      override val endIdx: Int = Int.MaxValue
    }
  }

  implicit def implicitOptionPolicyToPolicy(obj: Option[Policy]): Policy = obj
    .getOrElse(NoPolicy)

  class PolicyBoolean(private val flag: Boolean) extends AnyVal {
    def &&(other: => Policy): Policy = if (flag) other else NoPolicy
    def ||(other: => Policy): Policy = if (flag) NoPolicy else other
  }

  def ?(flag: Boolean) = new PolicyBoolean(flag)

}
