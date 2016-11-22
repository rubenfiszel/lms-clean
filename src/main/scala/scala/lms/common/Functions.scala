package scala.lms
package common

import internal._

import java.io.PrintWriter


import scala.reflect.SourceContext

trait Functions extends Base {

  implicit def fun[A:Rep, B:Rep](fun: A => B): A => B

}

trait FunctionsExp extends Functions with BaseExp with EffectExp {

  case class LambdaDef[A, B](f: Exp[A] => Exp[B], x:Exp[A], b:Block[B]) extends Def[A => B]

  case class Apply[A,B](b:Exp[A => B], x:Exp[A]) extends Def[B]


  case class Lambda[A:Rep, B:Rep](fun: A => B) extends (A => B) {

    val rA = typ[A]
    val rB = typ[B]

    val lambda = doLambdaDef(fun)(rA, rB)

    def apply(arg:A):B = {
      implicit val mf = rB.m
      val apply = Apply(lambda, rA.to(arg))
      rB.from(apply)
    }
  }

  def doLambdaDef[A, B](fun: A => B)(rA: Rep[A], rB:Rep[B]) = {

    implicit val mf = rA.m
    implicit val mf2 = rB.m    
    val x: Exp[rA.U] = unboxedFresh[rA.U]

    val fA = fun.compose((x:Exp[rA.U]) => rA.from(x))
    val fB: Exp[rA.U] => Exp[rB.U] = fA.andThen((x:B) => rB.to(x))

    val b: Block[rB.U] = reifyEffects(fB(x))
    toAtom(LambdaDef(fB, x, b))
  }

  def unboxedFresh[A:Manifest] : Exp[A] = fresh[A]

  implicit def fun[A:Rep,B:Rep](f: A => B):Lambda[A,B]  = 
      Lambda(f)


  override def mirror[A:Manifest](e: Def[A], f: Transformer)(implicit pos: SourceContext): Exp[A] = (e match {
    case e@LambdaDef(g, x:Exp[Any],y:Block[b]) => toAtom(LambdaDef(f(g), f(x), f(y)))(mtype(manifest[A]),pos)
//    case Reflect(e@Apply(g,arg), u, es) => reflectMirrored(Reflect(Apply(f(g),f(arg))(e.mA,mtype(e.mB)), mapOver(f,u), f(es)))(mtype(manifest[A]), pos)
    case _ => super.mirror(e,f)
  }).asInstanceOf[Exp[A]] // why??

  override def syms(e: Any): List[Sym[Any]] = e match {
    case LambdaDef(_, x, y) => syms(y)
    case _ => super.syms(e)
  }

  override def boundSyms(e: Any): List[Sym[Any]] = e match {
    case LambdaDef(_, x, y) => syms(x) ::: effectSyms(y)
    case _ => super.boundSyms(e)
  }

// TODO: right now were trying to hoist as much as we can out of functions.
// That might not always be appropriate. A promising strategy would be to have
// explicit 'hot' and 'cold' functions.

/*
  override def hotSyms(e: Any): List[Sym[Any]] = e match {
    case Lambda(f, x, y) => syms(y)
    case _ => super.hotSyms(e)
  }
*/

  override def symsFreq(e: Any): List[(Sym[Any], Double)] = e match {
    case LambdaDef(_, _, y) => freqHot(y)    
    case _ => super.symsFreq(e)
  }

}

trait ScalaGenFunctions extends ScalaGenNested {

  val IR: FunctionsExp

  import IR._

  override def emitNode(sym: Sym[Any], rhs: Def[Any]) = rhs match {
/*    case e@Lambda(y, x) =>
 */

    case e@LambdaDef(fun, x, y) =>
      emitValDef(sym, "{" + quote(x) + ": (" + remap(x.tp) + ") => ")
      emitBlock(y)
      stream.println(quote(getBlockResult(y)) + ": " + remap(y.tp))
      stream.println("}")

    case Apply(fun, arg) =>
      emitValDef(sym, quote(fun) + "(" + quote(arg) + ")")

/*
      emitValDef(sym, "{" + quote(x) + ": (" + remap(x.tp) + ") => ")
      emitBlock(y)
      stream.println(quote(getBlockResult(y)) + ": " + remap(y.tp))
      stream.println("}")
      emit
 */

      
      

    case _ => super.emitNode(sym, rhs)
  }

}
