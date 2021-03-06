package scala.lms
package common

import internal._

import java.io.PrintWriter
import scala.reflect.SourceContext
import scala.lms.internal.{GenericNestedCodegen, GenericFatCodegen, GenerationFailedException}


trait IfThenElse extends Base{
  this: Booleans =>

  def __ifThenElse[A:Rep](cond: Boolean, thenp: => A, elsep: => A)(implicit pos: SourceContext): A = __ifThenElse(cond, thenp, elsep)


  def __ifThenElse[T](cond: => scala.Boolean, thenp: => T, elsep: => T) = cond match {
    case true => thenp
    case false => elsep
  }
}


trait IfThenElsePureExp extends BaseExp with IfThenElse  {
  this: Booleans =>

  case class IfThenElse[T](cond: Exp[scala.Boolean], thenp: Exp[T], elsep: Exp[T]) extends Def[T]

  override def __ifThenElse[A:Rep](cond: Boolean, thenp: => A, elsep: => A)(implicit pos: SourceContext): A = {
      booleanRep.to(cond) match {
      case Const(true) => thenp
      case Const(false) => elsep
      case bool@_ =>
        val tp = rep[A]
        implicit val mf = tp.m
        val thenpC = tp.to(thenp)
        val elsepC = tp.to(elsep)
        tp.from(IfThenElse(bool, thenpC, elsepC))        
    }
  }
}



trait IfThenElseExp extends IfThenElse with EffectExp {
  this: Booleans =>

  abstract class AbstractIfThenElse[T] extends Def[T] {
    val cond: Exp[scala.Boolean]
    val thenp: Block[T]
    val elsep: Block[T]
  }
  
  case class IfThenElse[T](cond: Exp[scala.Boolean], thenp: Block[T], elsep: Block[T]) extends AbstractIfThenElse[T]

  override def __ifThenElse[A:Rep](cond: Boolean, thenp: => A, elsep: => A)(implicit pos: SourceContext): A = {
    val tp = rep[A]
    implicit val mf = tp.m    
    val a = reifyEffectsHere(tp.to(thenp))
    val b = reifyEffectsHere(tp.to(elsep))
    tp.from(ifThenElse(booleanRep.to(cond),a,b))
  }

  def ifThenElse[T:Manifest](cond: Exp[scala.Boolean], thenp: Block[T], elsep: Block[T])(implicit pos: SourceContext) = {
    val ae = summarizeEffects(thenp)
    val be = summarizeEffects(elsep)
    
    // TODO: make a decision whether we should call reflect or reflectInternal.
    // the former will look for any read mutable effects in addition to the passed
    // summary whereas reflectInternal will take ae orElse be literally.
    // the case where this comes up is if (c) a else b, with a or b mutable.
    // (see TestMutation, for now sticking to old behavior)
    
    ////reflectEffect(IfThenElse(cond,thenp,elsep), ae orElse be)
    reflectEffectInternal(IfThenElse(cond,thenp,elsep), ae orElse be)
  }
  
  override def mirrorDef[A:Manifest](e: Def[A], f: Transformer)(implicit pos: SourceContext): Def[A] = e match {
    case IfThenElse(c,a,b) => IfThenElse(f(c),f(a),f(b))
    case _ => super.mirrorDef(e,f)
  }

  override def mirror[A:Manifest](e: Def[A], f: Transformer)(implicit pos: SourceContext): Exp[A] = {
    e match {
    case Reflect(IfThenElse(c,a,b), u, es) => 
      if (f.hasContext)
       ifThenElse(f(c), reifyEffectsHere(f.reflectBlock(a)), reifyEffectsHere(f.reflectBlock(b)))
      else
        reflectMirrored(Reflect(IfThenElse(f(c),f(a),f(b)), mapOver(f,u), f(es)))(mtype(manifest[A]), pos)
    case IfThenElse(c,a,b) => 
      if (f.hasContext)
        ifThenElse(f(c), reifyEffectsHere(f.reflectBlock(a)), reifyEffectsHere(f.reflectBlock(b)))
      else
        IfThenElse(f(c),f(a),f(b)) // FIXME: should apply pattern rewrites (ie call smart constructor)

    case _ => super.mirror(e,f)
    }
  }


  override def aliasSyms(e: Any): List[Sym[Any]] = e match {
    case IfThenElse(c,a,b) => syms(a):::syms(b)
    case _ => super.aliasSyms(e)
  }

  override def containSyms(e: Any): List[Sym[Any]] = e match {
    case IfThenElse(c,a,b) => Nil
    case _ => super.containSyms(e)
  }

  override def extractSyms(e: Any): List[Sym[Any]] = e match {
    case IfThenElse(c,a,b) => Nil
    case _ => super.extractSyms(e)
  }

  override def copySyms(e: Any): List[Sym[Any]] = e match {
    case IfThenElse(c,a,b) => Nil // could return a,b but implied by aliasSyms
    case _ => super.copySyms(e)
  }


  override def symsFreq(e: Any): List[(Sym[Any], Double)] = e match {
    case IfThenElse(c, t, e) => freqNormal(c) ++ freqCold(t) ++ freqCold(e)
    case _ => super.symsFreq(e)
  }

  override def boundSyms(e: Any): List[Sym[Any]] = e match {
    case IfThenElse(c, t, e) => effectSyms(t):::effectSyms(e)
    case _ => super.boundSyms(e)
  }

}

trait IfThenElseFatExp extends IfThenElseExp with BaseFatExp {
  self: Booleans =>

  abstract class AbstractFatIfThenElse extends FatDef {
    val cond: Exp[scala.Boolean]
    val thenp: List[Block[Any]]
    val elsep: List[Block[Any]]
    
    var extradeps: List[Exp[Any]] = Nil //HACK
  }

  case class SimpleFatIfThenElse(cond: Exp[scala.Boolean], thenp: List[Block[Any]], elsep: List[Block[Any]]) extends AbstractFatIfThenElse

/* HACK */

  override def syms(e: Any): List[Sym[Any]] = e match {
    case x@SimpleFatIfThenElse(c, t, e) => super.syms(x) ++ syms(x.extradeps)
    case _ => super.syms(e)
  }

/* END HACK */


  override def boundSyms(e: Any): List[Sym[Any]] = e match {
    case SimpleFatIfThenElse(c, t, e) => effectSyms(t):::effectSyms(e)
    case _ => super.boundSyms(e)
  }

  override def symsFreq(e: Any): List[(Sym[Any], Double)] = e match {
    case x@SimpleFatIfThenElse(c, t, e) => freqNormal(c) ++ freqCold(t) ++ freqCold(e)    ++ freqNormal(x.extradeps)
    case _ => super.symsFreq(e)
  }

  // aliasing / sharing
  
  override def aliasSyms(e: Any): List[Sym[Any]] = e match {
    case SimpleFatIfThenElse(c,a,b) => syms(a):::syms(b)
    case _ => super.aliasSyms(e)
  }

  override def containSyms(e: Any): List[Sym[Any]] = e match {
    case SimpleFatIfThenElse(c,a,b) => Nil
    case _ => super.containSyms(e)
  }

  override def extractSyms(e: Any): List[Sym[Any]] = e match {
    case SimpleFatIfThenElse(c,a,b) => Nil
    case _ => super.extractSyms(e)
  }

  override def copySyms(e: Any): List[Sym[Any]] = e match {
    case SimpleFatIfThenElse(c,a,b) => Nil // could return a,b but implied by aliasSyms
    case _ => super.copySyms(e)
  }
}


trait IfThenElseExpOpt extends IfThenElseExp {
  this: BooleansExp with EqualsExp =>
  
  //TODO: eliminate conditional if both branches return same value!

  // it would be nice to handle rewrites in method ifThenElse but we'll need to
  // 'de-reify' blocks in case we rewrite if(true) to thenp. 
  // TODO: make reflect(Reify(..)) do the right thing
  
  override def __ifThenElse[T:Rep](cond: Boolean, thenp: => T, elsep: => T)(implicit pos: SourceContext) = booleanRep.to(cond) match {
    case Const(true) => thenp
    case Const(false) => elsep
    case Def(BoolNot(a)) => __ifThenElse(boolean(a), elsep, thenp)
    case Def(e@NotEqual(a,b)) => __ifThenElse(boolean(equal(a,b)(pos)), elsep, thenp)
    case _ =>
      super.__ifThenElse(cond, thenp, elsep)
  }
}


trait BaseGenIfThenElse extends GenericNestedCodegen {
  val IR: IfThenElseExp
  import IR._

}

trait BaseGenIfThenElseFat extends BaseGenIfThenElse with GenericFatCodegen {
  val IR: IfThenElseFatExp
  import IR._

  override def fatten(e: Stm): Stm = e match {
    case TP(sym, o: AbstractIfThenElse[_]) => 
      TTP(List(sym), List(o), SimpleFatIfThenElse(o.cond, List(o.thenp), List(o.elsep)))
    case TP(sym, p @ Reflect(o: AbstractIfThenElse[_], u, es)) => //if !u.maySimple && !u.mayGlobal =>  // contrary, fusing will not change observable order
      // assume body will reflect, too...
      printdbg("-- fatten effectful if/then/else " + e)
      val e2 = SimpleFatIfThenElse(o.cond, List(o.thenp), List(o.elsep))
      e2.extradeps = es //HACK
      TTP(List(sym), List(p), e2)
    case _ => super.fatten(e)
  }
}


trait ScalaGenIfThenElse extends ScalaGenNested with BaseGenIfThenElse {
  import IR._
 
  override def emitNode(sym: Sym[Any], rhs: Def[Any]) = rhs match {
    case IfThenElse(c,a,b) =>
      stream.println("val " + quote(sym) + " = if (" + quote(c) + ") {")
      emitBlock(a)
      stream.println(quote(getBlockResult(a)))
      stream.println("} else {")
      emitBlock(b)
      stream.println(quote(getBlockResult(b)))
      stream.println("}")
    case _ => super.emitNode(sym, rhs)
  }
}

trait ScalaGenIfThenElseFat extends ScalaGenIfThenElse with ScalaGenFat with BaseGenIfThenElseFat {
  import IR._

  override def emitFatNode(symList: List[Sym[Any]], rhs: FatDef) = rhs match {
    case SimpleFatIfThenElse(c,as,bs) => 
      def quoteList[T](xs: List[Exp[T]]) = if (xs.length > 1) xs.map(quote).mkString("(",",",")") else xs.map(quote).mkString(",")
      if (symList.length > 1) stream.println("// TODO: use vars instead of tuples to return multiple values")
      stream.println("val " + quoteList(symList) + " = if (" + quote(c) + ") {")
      emitFatBlock(as)
      stream.println(quoteList(as.map(getBlockResult)))
      stream.println("} else {")
      emitFatBlock(bs)
      stream.println(quoteList(bs.map(getBlockResult)))
      stream.println("}")
    case _ => super.emitFatNode(symList, rhs)
  }

}


trait ScalaGenIfThenElsePure extends ScalaGenNested {
  val IR: IfThenElsePureExp with Effects
  import IR._

  override def emitNode(sym: Sym[Any], rhs: Def[Any]) = rhs match {
    case IfThenElse(c,a,b) =>
      val blockA = reifyBlock(a)
      val blockB = reifyBlock(b)
      stream.println("val " + quote(sym) + " = if (" + quote(c) + ") {")
      emitBlock(blockA)
      stream.println(quote(getBlockResult(blockA)))
      stream.println("} else {")
      emitBlock(blockB)
      stream.println(quote(getBlockResult(blockB)))
      stream.println("}")
    case _ => super.emitNode(sym, rhs)
  }
}
