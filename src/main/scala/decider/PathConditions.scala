// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2019 ETH Zurich.

package viper.silicon.decider

import debugger.DebugExp
import org.jgrapht.alg.util.Pair
import viper.silicon.Stack
import viper.silicon.common.collections.immutable.InsertionOrderedSet
import viper.silicon.state.terms._
import viper.silicon.utils.Counter
import viper.silicon.verifier.Verifier
import viper.silver.ast
import viper.silver.ast.TrueLit
/*
 * Interfaces
 */

/* TODO: 'contains' functionality currently not needed. If removed, 'allAssumptions' could
 *       probably removed as well.
 *       Benchmark runtime difference!
 */

trait RecordedPathConditions {
  def branchConditions: Stack[Term]
  def branchConditionExps: Stack[Pair[ast.Exp, ast.Exp]]
  def assumptions: InsertionOrderedSet[Term]

  def assumptionExps: InsertionOrderedSet[DebugExp]
  def declarations: InsertionOrderedSet[Decl]

  def contains(assumption: Term): Boolean

  def conditionalized: Seq[Term]

  def conditionalizedExp: Seq[DebugExp]

  def quantified(quantifier: Quantifier,
                 qvars: Seq[Var],
                 triggers: Seq[Trigger],
                 name: String,
                 isGlobal: Boolean,
                 ignore: Term /* TODO: Hack, implement properly */)
                : (Seq[Term], Seq[Quantification])

  def quantifiedExp(quantifier: Quantifier,
                    qvars: Seq[ast.Exp],
                    triggers: Seq[ast.Trigger],
                 name: String,
                 isGlobal: Boolean,
                 ignore: Term /* TODO: Hack, implement properly */)
  : (InsertionOrderedSet[DebugExp], InsertionOrderedSet[DebugExp])
}

trait PathConditionStack extends RecordedPathConditions {
  def setCurrentBranchCondition(condition: Term, conditionExp: Pair[ast.Exp, ast.Exp]): Unit
  def add(assumption: Term): Unit
  def add(declaration: Decl): Unit
  def pushScope(): Unit
  def popScope(): Unit
  def mark(): Mark
  def popUntilMark(mark: Mark): Unit

  def startDebugSubExp(): Unit
  def finishDebugSubExp(description : String): Unit

  def addDebugExp(e: DebugExp): Unit

  def addNonGlobalDebugExp(assumptionDebugExp: DebugExp) : Unit
  def addGlobalDebugExp(assumptionDebugExp: DebugExp) : Unit
  def after(mark: Mark): RecordedPathConditions
  def isEmpty: Boolean
  def duplicate(): PathConditionStack
    /* Public method 'clone' impossible, see https://issues.scala-lang.org/browse/SI-6760 */
}

/*
 * Implementations (mostly mutable!)
 */

private class PathConditionStackLayer
    extends Cloneable {

  private var _branchCondition: Option[Term] = None
  private var _branchConditionExp: Option[Pair[ast.Exp, ast.Exp]] = None
  private var _globalAssumptions: InsertionOrderedSet[Term] = InsertionOrderedSet.empty
  private var _nonGlobalAssumptions: InsertionOrderedSet[Term] = InsertionOrderedSet.empty
  private var _globalAssumptionDebugExps: InsertionOrderedSet[DebugExp] = InsertionOrderedSet.empty
  private var _nonGlobalAssumptionDebugExps: InsertionOrderedSet[DebugExp] = InsertionOrderedSet.empty
  private var _declarations: InsertionOrderedSet[Decl] = InsertionOrderedSet.empty

  def branchCondition: Option[Term] = _branchCondition
  def branchConditionExp: Option[Pair[ast.Exp, ast.Exp]] = _branchConditionExp
  def globalAssumptions: InsertionOrderedSet[Term] = _globalAssumptions
  def nonGlobalAssumptions: InsertionOrderedSet[Term] = _nonGlobalAssumptions
  def globalAssumptionDebugExps: InsertionOrderedSet[DebugExp] = _globalAssumptionDebugExps
  def nonGlobalAssumptionDebugExps: InsertionOrderedSet[DebugExp] = _nonGlobalAssumptionDebugExps ++ debugExpStack.flatten
  def declarations: InsertionOrderedSet[Decl] = _declarations

  def assumptions: InsertionOrderedSet[Term] = globalAssumptions ++ nonGlobalAssumptions
  def assumptionDebugExps:  InsertionOrderedSet[DebugExp] = globalAssumptionDebugExps ++ nonGlobalAssumptionDebugExps

  def pathConditions: InsertionOrderedSet[Term] = assumptions ++ branchCondition

  var debugExpStack : Stack[InsertionOrderedSet[DebugExp]] = Stack.empty

  def branchCondition_=(condition: Term): Unit = {
    assert(_branchCondition.isEmpty,
             s"Branch condition is already set (to ${_branchCondition.get}), "
           + s"won't override (with $condition).")

    _branchCondition = Some(condition)
  }

  def branchConditionExp_=(condition: Pair[ast.Exp, ast.Exp]): Unit = {
    assert(_branchConditionExp.isEmpty,
      s"Branch condition is already set (to ${_branchConditionExp.get}), "
        + s"won't override (with $condition).")

    _branchConditionExp = Some(condition)
  }

  def add(assumption: Term): Unit = {
    assert(
      !assumption.isInstanceOf[And],
      s"Unexpectedly found a conjunction (should have been split): $assumption")

    /* TODO: Don't record branch conditions as assumptions */

    if (PathConditions.isGlobal(assumption)) {
      _globalAssumptions += assumption
    } else
      _nonGlobalAssumptions += assumption
  }

  def addNonGlobalDebugExp(debugExp : DebugExp): Unit = {
    _nonGlobalAssumptionDebugExps += debugExp
  }

  def addGlobalDebugExp(debugExp: DebugExp): Unit = {
    _globalAssumptionDebugExps += debugExp
  }

  def startDebugSubExp(): Unit = {
    debugExpStack = InsertionOrderedSet[DebugExp]().empty +: debugExpStack
  }

  private def popDebugSubExp(): InsertionOrderedSet[DebugExp] = {
    val res = debugExpStack.head
    debugExpStack = debugExpStack.tail
    res
  }

  def finishDebugSubExp(description: String): Unit = {
    val children = popDebugSubExp()
    if(children.nonEmpty) {
      val debugExp = DebugExp.createInstance(description = description, children = children)
      addDebugExp(debugExp)
    }
  }

  def addDebugExp(e: DebugExp): Unit = {
    if (e.getAllTerms.nonEmpty && e.getAllTerms.forall(t => PathConditions.isGlobal(t))) {
      addGlobalDebugExp(e)
    } else {
      if (e.getAllTerms.exists(t => PathConditions.isGlobal(t)) && !e.isInternal) {
        // this should not happen
        addGlobalDebugExp(DebugExp.createInstance("failed to distinguish global and non-global terms"))
      }

      if (debugExpStack.isEmpty) {
        addNonGlobalDebugExp(e)
      } else {
        // DebugSubExp -> will be attached to another DebugExp later on
        val d = debugExpStack.head + e
        debugExpStack = d +: debugExpStack.tail
      }
    }
  }

  def add(declaration: Decl): Unit = _declarations += declaration

  def contains(pathCondition: Term): Boolean = {
    assert(
      !pathCondition.isInstanceOf[And],
      s"Unexpectedly found a conjunction (should have been split): $pathCondition")

    if (PathConditions.isGlobal(pathCondition))
      /* Assumption: globals are never used as branch conditions */
      _globalAssumptions.contains(pathCondition)
    else
      _nonGlobalAssumptions.contains(pathCondition) || _branchCondition.contains(pathCondition)
  }

  override def clone(): AnyRef = {
    /* Attention: the original and its clone must not share any mutable data! */
    super.clone()
  }
}

private trait LayeredPathConditionStackLike {
  protected def branchConditions(layers: Stack[PathConditionStackLayer]): Stack[Term] =
    layers.flatMap(_.branchCondition)

  protected def branchConditionExps(layers: Stack[PathConditionStackLayer]): Stack[Pair[ast.Exp, ast.Exp]] =
    layers.flatMap(_.branchConditionExp)

  protected def assumptions(layers: Stack[PathConditionStackLayer]): InsertionOrderedSet[Term] =
    InsertionOrderedSet(layers.flatMap(_.assumptions)) // Note: Performance?

  protected def assumptionExps(layers: Stack[PathConditionStackLayer]): InsertionOrderedSet[DebugExp] =
    InsertionOrderedSet(layers.flatMap(_.assumptionDebugExps)) // Note: Performance?

  protected def declarations(layers: Stack[PathConditionStackLayer]): InsertionOrderedSet[Decl] =
    InsertionOrderedSet(layers.flatMap(_.declarations)) // Note: Performance?

  protected def contains(layers: Stack[PathConditionStackLayer], assumption: Term): Boolean =
    layers exists (_.contains(assumption))

  protected def conditionalized(layers: Stack[PathConditionStackLayer]): Seq[Term] = {
    var unconditionalTerms = Vector.empty[Term]
    var conditionalTerms = Vector.empty[Term]
    var implicationLHS: Term = True

    for (layer <- layers.reverseIterator) {
      unconditionalTerms ++= layer.globalAssumptions

      layer.branchCondition match {
        case Some(condition) =>
          implicationLHS = And(implicationLHS, condition)
        case None =>
      }

      conditionalTerms :+=
        Implies(implicationLHS, And(layer.nonGlobalAssumptions))
    }

    unconditionalTerms ++ conditionalTerms
  }

  protected def conditionalizedExp(layers: Stack[PathConditionStackLayer]): Seq[DebugExp] = {
    var unconditionalTerms = Vector.empty[DebugExp]
    var conditionalTerms = Vector.empty[DebugExp]
    var implicationLHS: Term = True
    var implicationLHSExp: ast.Exp = ast.TrueLit()()
    var implicationLHSExpNew: ast.Exp = ast.TrueLit()()

    for (layer <- layers.reverseIterator) {
      unconditionalTerms ++= layer.globalAssumptionDebugExps

      layer.branchConditionExp match {
        case Some(condition) =>
          implicationLHS = And(implicationLHS, layer.branchCondition.get)
          implicationLHSExp = if(implicationLHSExp.equals(TrueLit()())) condition.getFirst
                              else ast.And(implicationLHSExp, condition.getFirst)()
          implicationLHSExpNew = if (implicationLHSExpNew.equals(TrueLit()())) condition.getSecond
          else ast.And(implicationLHSExpNew, condition.getSecond)()
        case None =>
      }

      if(layer.nonGlobalAssumptionDebugExps.nonEmpty && !implicationLHSExp.equals(TrueLit()())) {
        conditionalTerms :+= DebugExp.createImplicationInstance(None, Some(implicationLHSExp), Some(implicationLHSExpNew), Some(implicationLHS),
          false, layer.nonGlobalAssumptionDebugExps ++ layer.debugExpStack.flatten)
      }else{
        conditionalTerms ++= layer.nonGlobalAssumptionDebugExps ++ layer.debugExpStack.flatten
      }
    }

    unconditionalTerms ++ conditionalTerms
  }

  protected def quantified(layers: Stack[PathConditionStackLayer],
                           quantifier: Quantifier,
                           qvars: Seq[Var],
                           triggers: Seq[Trigger],
                           name: String,
                           isGlobal: Boolean,
                           ignore: Term)
                          : (Seq[Term], Seq[Quantification]) = {

    var globals = Vector.empty[Term]
    var nonGlobals = Vector.empty[Quantification]

    val ignores = ignore.topLevelConjuncts

    for (layer <- layers) {
      globals ++= layer.globalAssumptions

      nonGlobals :+=
        Quantification(
          quantifier,
          qvars,
          Implies(layer.branchCondition.getOrElse(True), And(layer.nonGlobalAssumptions -- ignores)),
          triggers,
          name,
          isGlobal)
    }

    (globals, nonGlobals)
  }

  def quantifiedExp(layers: Stack[PathConditionStackLayer],
                    quantifier: Quantifier,
                    qvars: Seq[ast.Exp],
                    triggers: Seq[ast.Trigger],
                    name: String,
                    isGlobal: Boolean,
                    ignore: Term )
                    : (InsertionOrderedSet[DebugExp], InsertionOrderedSet[DebugExp]) = {
    var globals = InsertionOrderedSet.empty[DebugExp]
    var nonGlobals = InsertionOrderedSet.empty[DebugExp]

    for (layer <- layers) {
      globals ++= layer.globalAssumptionDebugExps

      val branchConditionExp = layer.branchConditionExp
        if(branchConditionExp.isDefined){
          var quantBody: InsertionOrderedSet[DebugExp] = InsertionOrderedSet.empty
          if(branchConditionExp.get.getFirst.equals(ast.TrueLit()())) {
            quantBody = layer.nonGlobalAssumptionDebugExps
          }
          else{
            quantBody = InsertionOrderedSet(DebugExp.createImplicationInstance(description = None, originalExp = Some(branchConditionExp.get.getFirst), finalExp = Some(branchConditionExp.get.getSecond), term = layer.branchCondition, isInternal_ = false,
              children = layer.nonGlobalAssumptionDebugExps))
          }

          val quantDebugExp = DebugExp.createQuantifiedInstance(description=None, originalExp=None, finalExp = None, term = None, isInternal_ = false,
            children = InsertionOrderedSet(quantBody), quantifier = quantifier.toString, qvars = qvars, triggers = triggers)
          nonGlobals += quantDebugExp
        }else{
          nonGlobals += DebugExp.createInstance("quantifiedExp", layer.nonGlobalAssumptionDebugExps)
        }
    }

    (globals, nonGlobals)
  }
}

private class DefaultRecordedPathConditions(from: Stack[PathConditionStackLayer])
    extends LayeredPathConditionStackLike
       with RecordedPathConditions {

  val branchConditions: Stack[Term] = branchConditions(from)
  val branchConditionExps: Stack[Pair[ast.Exp, ast.Exp]] = branchConditionExps(from)
  val assumptions: InsertionOrderedSet[Term] = assumptions(from)
  val assumptionExps: InsertionOrderedSet[DebugExp] = assumptionExps(from)
  val declarations: InsertionOrderedSet[Decl] = declarations(from)

  def contains(assumption: Term): Boolean = contains(from, assumption)

  val conditionalized: Seq[Term] = conditionalized(from)
  val conditionalizedExp: Seq[DebugExp] = conditionalizedExp(from)

  def quantified(quantifier: Quantifier,
                 qvars: Seq[Var],
                 triggers: Seq[Trigger],
                 name: String,
                 isGlobal: Boolean,
                 ignore: Term)
                : (Seq[Term], Seq[Quantification]) = {

    quantified(from, quantifier, qvars, triggers, name, isGlobal, ignore)
  }

  def quantifiedExp(quantifier: Quantifier,
                    qvars: Seq[ast.Exp],
                    triggers: Seq[ast.Trigger],
                    name: String,
                    isGlobal: Boolean,
                    ignore: Term /* TODO: Hack, implement properly */)
                    : (InsertionOrderedSet[DebugExp], InsertionOrderedSet[DebugExp]) = {

    quantifiedExp(from, quantifier, qvars, triggers, name, isGlobal, ignore)
  }
}

private[decider] class LayeredPathConditionStack
    extends LayeredPathConditionStackLike
       with PathConditionStack
       with Cloneable {

  /* private */ var layers: Stack[PathConditionStackLayer] = Stack.empty
  private var markToLength: Map[Mark, Int] = Map.empty
  private var scopeMarks: List[Mark] = List.empty
  private var markCounter = new Counter(0)

  /* Set of assumptions across all layers. Maintained separately to improve performance. */
  private var allAssumptions = InsertionOrderedSet.empty[Term]

  pushScope() /* Create an initial layer on the stack */

  def setCurrentBranchCondition(condition: Term, conditionExp: Pair[ast.Exp, ast.Exp]): Unit = {
    /* TODO: Split condition into top-level conjuncts as well? */

    layers.head.branchCondition = condition
    layers.head.branchConditionExp = conditionExp
  }

  def startDebugSubExp(): Unit = {
    layers.head.startDebugSubExp()
  }

  def finishDebugSubExp(description: String): Unit = {
    layers.head.finishDebugSubExp(description)
  }

  def addDebugExp(e: DebugExp): Unit = {
    layers.head.addDebugExp(e)
  }

  def addNonGlobalDebugExp(assumptionDebugExp: DebugExp) : Unit = {
    layers.head.addNonGlobalDebugExp(assumptionDebugExp)
  }

  def addGlobalDebugExp(assumptionDebugExp: DebugExp): Unit = {
    layers.head.addGlobalDebugExp(assumptionDebugExp)
  }

  def add(assumption: Term): Unit = {
    /* TODO: Would be cleaner to not add assumptions that are already set as branch conditions */

    val tlcs = assumption.topLevelConjuncts

    tlcs foreach layers.head.add
    allAssumptions ++= tlcs
  }

  def add(declaration: Decl): Unit = {
    layers.head.add(declaration)
  }

  def pushScope(): Unit = {
    val scopeMark = pushLayer()
    scopeMarks = scopeMark :: scopeMarks
  }

  def popScope(): Unit = {
    val scopeMark = scopeMarks.head
    scopeMarks = scopeMarks.tail

    popLayersAndRemoveMark(scopeMark)
  }

  private def pushLayer(): Mark = {
    val mark = markCounter.next()

    markToLength += (mark -> layers.length)

    val debugExpStackTmp = if(layers.nonEmpty && Verifier.config.enableDebugging()) layers.head.debugExpStack else Stack.empty

    layers = new PathConditionStackLayer() +: layers

    layers.head.debugExpStack = debugExpStackTmp
    mark
  }

  def popUntilMark(mark: Mark): Unit = {
    assert(markToLength.contains(mark), "Cannot pop unknown mark")
    popLayersAndRemoveMark(mark)
  }

  private def popLayersAndRemoveMark(mark: Mark): Unit = {
    val targetLength = markToLength(mark)
    val dropLength = layers.length - targetLength

    markToLength = markToLength - mark

//    /* Remove marks pointing to popped layers (including mark itself) */
//    markToLength = markToLength filter (_._2 < targetLength)
//      /* TODO: Performance? Do lazily, e.g. when isEmpty is called? */

    var i = 0
    layers =
      layers.dropWhile(layer => {
        i += 1
        allAssumptions --= layer.assumptions
        i < dropLength
        /* If i < dropLength is false, the current - and last-to-drop - layer won't be
         * dropped, but its assumptions have already been removed from allAssumptions.
         * Subsequently taking the tail of the remaining layers results in also
         * dropping the last layer that needs to be dropped.
         */
      }).tail
  }

  def branchConditions: Stack[Term] = layers.flatMap(_.branchCondition)

  override def branchConditionExps: Stack[Pair[ast.Exp, ast.Exp]] = layers.flatMap(_.branchConditionExp)

  def assumptions: InsertionOrderedSet[Term] = allAssumptions

  def assumptionExps: InsertionOrderedSet[DebugExp] = InsertionOrderedSet(layers.flatMap(_.assumptionDebugExps))

  def declarations: InsertionOrderedSet[Decl] =
    InsertionOrderedSet(layers.flatMap(_.declarations)) // Note: Performance?

  def contains(assumption: Term): Boolean = allAssumptions.contains(assumption)

  def conditionalized: Seq[Term] = conditionalized(layers)

  def conditionalizedExp: Seq[DebugExp] = conditionalizedExp(layers)

  def quantified(quantifier: Quantifier,
                 qvars: Seq[Var],
                 triggers: Seq[Trigger],
                 name: String,
                 isGlobal: Boolean,
                 ignore: Term)
                : (Seq[Term], Seq[Quantification]) = {

    quantified(layers, quantifier, qvars, triggers, name, isGlobal, ignore)
  }

  def quantifiedExp(quantifier: Quantifier,
                    qvars: Seq[ast.Exp],
                    triggers: Seq[ast.Trigger],
                 name: String,
                 isGlobal: Boolean,
                 ignore: Term)
  : (InsertionOrderedSet[DebugExp], InsertionOrderedSet[DebugExp]) = {

    quantifiedExp(layers, quantifier, qvars, triggers, name, isGlobal, ignore)
  }

  def mark(): Mark = pushLayer()

  def after(mark: Mark): RecordedPathConditions = {
    val afterLength = layers.length - markToLength(mark)
    val afterLayers = layers.take(afterLength)

    new DefaultRecordedPathConditions(afterLayers)
  }

  def isEmpty: Boolean = (
       layers.forall(_.branchCondition.isEmpty)
    && allAssumptions.isEmpty
    && (markToLength.keySet -- scopeMarks).isEmpty)

  override def duplicate(): LayeredPathConditionStack = {
    /* Attention: The original and its clone must not share any mutable data! */

    val clonedStack = new LayeredPathConditionStack

    /* Sharing immutable data is safe */
    clonedStack.allAssumptions = allAssumptions
    clonedStack.markToLength = markToLength
    clonedStack.scopeMarks = scopeMarks

    /* Mutable data is cloned */
    clonedStack.markCounter = markCounter.clone()
    clonedStack.layers = layers map (_.clone().asInstanceOf[PathConditionStackLayer])

    clonedStack
  }

  override def toString: String =  {
    val sb = new StringBuilder(s"${this.getClass.getSimpleName}:\n")
    val sep = s" ${"-" * 10}\n"

    sb.append(sep)

    sb.append(s"  height: ${layers.length}\n")
    sb.append(s"  allAssumptions:\n")
    for (assumption <- allAssumptions) {
      sb.append(s"    $assumption\n")
    }

    sb.append(sep)

    for (layer <- layers) {
      sb.append(s"  branch condition: ${layer.branchCondition}\n")
      sb.append( "  assumptions:\n")
      for (assumption <- layer.assumptions) {
        sb.append(s"    $assumption\n")
      }
    }

    sb.append(sep)

    val marks = markToLength.keySet -- scopeMarks
    sb.append("  marks:\n")
    marks foreach (m => {
      sb.append(s"    $m -> ${markToLength(m)} (${scopeMarks.contains(m)})\n")
    })

    sb.result()
  }
}

private object PathConditions {
  def isGlobal(assumption: Term): Boolean = {
    assumption match {
      case quantification: Quantification => quantification.isGlobal
      case _: IsReadPermVar => true
      case _ => false
    }
  }
}
