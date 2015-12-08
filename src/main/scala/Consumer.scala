/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper
package silicon

import org.slf4s.Logging
import silver.ast
import silver.verifier.{VerificationError, PartialVerificationError}
import silver.verifier.reasons.{ReceiverNotInjective, InsufficientPermission, NegativePermission, AssertionFalse,
    ReceiverNull, NamedMagicWandChunkNotFound, MagicWandChunkNotFound}
import interfaces.state.{StateFactory, Store, Heap, PathConditions, State, StateFormatter, Chunk}
import interfaces.{Consumer, Evaluator, VerificationResult, Failure}
import interfaces.decider.Decider
import interfaces.state.factoryUtils.Ø
import reporting.Bookkeeper
import state.{QuantifiedChunk, SymbolConvert, DirectChunk, DefaultContext, MagicWandChunk, MagicWandChunkIdentifier}
import state.terms._
import state.terms.predef.`?r`
import supporters.{LetHandler, Brancher, ChunkSupporter, HeuristicsSupporter, MagicWandSupporter}
import supporters.qps.QuantifiedChunkSupporter

trait DefaultConsumer[ST <: Store[ST], H <: Heap[H],
                      PC <: PathConditions[PC], S <: State[ST, H, S]]
    extends NoOpStatefulComponent
       with Consumer[Chunk, ST, H, S, DefaultContext[H]]
    { this: Logging with Evaluator[ST, H, S, DefaultContext[H]]
                    with Brancher[ST, H, S, DefaultContext[H]]
                    with ChunkSupporter[ST, H, PC, S]
                    with LetHandler[ST, H, S, DefaultContext[H]]
                    with MagicWandSupporter[ST, H, PC, S]
                    with HeuristicsSupporter[ST, H, PC, S]
                    with LetHandler[ST, H, S, DefaultContext[H]] =>

  private type C = DefaultContext[H]
  private type CH = Chunk

  protected implicit val manifestH: Manifest[H]

  protected val decider: Decider[ST, H, PC, S, C]
  import decider.assume

  protected val stateFactory: StateFactory[ST, H, S]
  import stateFactory._

  protected val symbolConverter: SymbolConvert
  import symbolConverter.toSort

  protected val quantifiedChunkSupporter: QuantifiedChunkSupporter[ST, H, PC, S]
  protected val stateFormatter: StateFormatter[ST, H, S, String]
  protected val bookkeeper: Bookkeeper
  protected val config: Config

  private val qpForallCache = MMap[(ast.Forall, Set[QuantifiedChunk]), (Var, Term, Term, Seq[Term], H, QuantifiedChunk, C)]()

  /*
   * ATTENTION: The DirectChunks passed to the continuation correspond to the
   * chunks as they existed when the consume took place. More specifically,
   * the amount of permissions that come with these chunks is NOT the amount
   * that has been consumed, but the amount that was found in the heap.
   */
  def consume(σ: S, p: Term, φ: ast.Exp, pve: PartialVerificationError, c: C)
             (Q: (S, Term, List[CH], C) => VerificationResult)
             : VerificationResult =

    consume(σ, σ.h, p, φ.whenExhaling, pve, c)((h1, t, dcs, c1) =>
      Q(σ \ h1, t, dcs, c1))

  def consumes(σ: S,
               p: Term,
               φs: Seq[ast.Exp],
               pvef: ast.Exp => PartialVerificationError,
               c: C)
              (Q: (S, Term, List[CH], C) => VerificationResult)
              : VerificationResult =

    consumes(σ, σ.h, p, φs map (_.whenExhaling), pvef, c)(Q)

  private def consumes(σ: S,
                       h: H,
                       p: Term,
                       φs: Seq[ast.Exp],
                       pvef: ast.Exp => PartialVerificationError,
                       c: C)
                       (Q: (S, Term, List[CH], C) => VerificationResult)
                       : VerificationResult =

    /* Note: See the code comment about produce vs. produces in
     * DefaultProducer.produces. The same applies to consume vs. consumes.
     */

    if (φs.isEmpty)
      Q(σ \ h, Unit, Nil, c)
    else {
      val φ = φs.head

      if (φs.tail.isEmpty)
        consume(σ, h, p, φ, pvef(φ), c)((h1, s1, dcs1, c1) =>
          Q(σ \ h1, s1, dcs1, c1))
      else
        consume(σ, h, p, φ, pvef(φ), c)((h1, s1, dcs1, c1) =>
          consumes(σ, h1, p, φs.tail, pvef, c1)((h2, s2, dcs2, c2) => {
            Q(h2, Combine(s1, s2), dcs1 ::: dcs2, c2)}))
    }

  protected def consume(σ: S, h: H, p: Term, φ: ast.Exp, pve: PartialVerificationError, c: C)
                       (Q: (H, Term, List[CH], C) => VerificationResult)
                       : VerificationResult = {

    if (!φ.isInstanceOf[ast.And]) {
      log.debug(s"\nCONSUME ${utils.ast.sourceLineColumn(φ)}: $φ")
      log.debug(stateFormatter.format(σ))
      log.debug("h = " + stateFormatter.format(h))
      if (c.reserveHeaps.nonEmpty)
        log.debug("hR = " + c.reserveHeaps.map(stateFormatter.format).mkString("", ",\n     ", ""))
    }

    val consumed = φ match {
      case ast.And(a1, a2) if !φ.isPure || config.handlePureConjunctsIndividually() =>
        consume(σ, h, p, a1, pve, c)((h1, s1, dcs1, c1) =>
          consume(σ, h1, p, a2, pve, c1)((h2, s2, dcs2, c2) => {
            Q(h2, Combine(s1, s2), dcs1 ::: dcs2, c2)}))

      case ast.Implies(e0, a0) if !φ.isPure =>
        val σC = σ \ magicWandSupporter.getEvalHeap(σ, h, c)
        eval(σC, e0, pve, c)((t0, c1) =>
          branch(σC, t0, c1,
            (c2: C) => consume(σ, h, p, a0, pve, c2)(Q),
            (c2: C) => Q(h, Unit, Nil, c2)))

      case ast.CondExp(e0, a1, a2) if !φ.isPure =>
        val σC = σ \ magicWandSupporter.getEvalHeap(σ, h, c)
        eval(σC, e0, pve, c)((t0, c1) =>
          branch(σC, t0, c1,
            (c2: C) => consume(σ, h, p, a1, pve, c2)(Q),
            (c2: C) => consume(σ, h, p, a2, pve, c2)(Q)))

      case ast.QuantifiedPermissionSupporter.ForallRefPerm(qvar, condition, rcvr, field, loss, forall, fa) =>
        val tQVar = decider.fresh(qvar.name, toSort(qvar.typ))
        val γQVar = Γ(ast.LocalVar(qvar.name)(qvar.typ), tQVar)
        val σQVar = σ \+ γQVar
        val πPre = decider.π
        val c0 = c.copy(quantifiedVariables = tQVar +: c.quantifiedVariables)
        decider.locally[(Term, Term, Term, Iterable[Term], Quantification, C)](QB =>
          eval(σQVar, condition, pve, c0)((tCond, c1) =>
            if (decider.check(σQVar, Not(tCond), config.checkTimeout())) {
              /* The condition cannot be satisfied, hence we don't need to consume anything. */
              val c2 = c1.copy(quantifiedVariables = c1.quantifiedVariables.tail)
              Q(h, Unit, Nil, c2)
            } else {
              decider.assume(tCond)
              val c2 = c1.copy(branchConditions = tCond +: c1.branchConditions)
              eval(σQVar, rcvr, pve, c2)((tRcvr, c3) =>
                decider.assert(σ, tRcvr !== Null()) {
                  case true =>
                    eval(σQVar, loss, pve, c3)((pLoss, c4) =>
                      decider.assert(σ, perms.IsNonNegative(pLoss)) {
                        case true =>
                          val πDelta = decider.π -- πPre - tCond /* Removing tCond is crucial since it is not an auxiliary term */
                            val (tAuxTopLevel, tAuxNested) = state.utils.partitionAuxiliaryTerms(πDelta)
                          val tAuxQuantNoTriggers = Forall(tQVar, And(tAuxNested), Nil, s"prog.l${utils.ast.sourceLine(forall)}-aux")
                          val c5 = c4.copy(quantifiedVariables = c4.quantifiedVariables.tail,
                                           branchConditions = c4.branchConditions.tail)
                          QB(tCond, tRcvr, pLoss, tAuxTopLevel, tAuxQuantNoTriggers, c5)
                        case false =>
                          Failure[ST, H, S](pve dueTo NegativePermission(loss))})
                  case false =>
                    Failure[ST, H, S](pve dueTo ReceiverNull(fa))})})
        ){case (tCond, tRcvr, pLoss, tAuxTopLevel, tAuxQuantNoTriggers, c1) =>
            val hints = quantifiedChunkSupporter.extractHints(Some(tQVar), Some(tCond), tRcvr)
            val chunkOrderHeuristics = quantifiedChunkSupporter.hintBasedChunkOrderHeuristic(hints)
            val invFct =
              quantifiedChunkSupporter.getFreshInverseFunction(tQVar, tRcvr, tCond, c1.quantifiedVariables)
            decider.prover.logComment("Top-level auxiliary terms")
            assume(tAuxTopLevel)
            decider.prover.logComment("Nested auxiliary terms")
            assume(tAuxQuantNoTriggers.copy(triggers = invFct.invOfFct.triggers)) /* NOTE: It might be necessary to do the same as in DefaultProducer */
  //          val (quantifiedChunks, _) = quantifiedChunkSupporter.splitHeap(h2, field.name)
  //          qpForallCache.get((forall, toSet(quantifiedChunks))) match {
                /* TODO: Re-enable caching. Needs to take context.branchConditions into account as well. Something else? */
  //            case Some((tQVarCached, tRcvrCached, tCondCached, invAxiomsCached, hCached, chCached, cCached))
  //              if tRcvr == tRcvrCached.replace(tQVarCached, tQVar) && tCond == tCondCached.replace(tQVarCached, tQVar) =>
  //              assume(invAxiomsCached)
  //              Q(hCached, chCached.fvf, /*ch :: */Nil, cCached)
  //            case _ =>
            /* TODO: Can we omit/simplify the injectivity check in certain situations? */
            val receiverInjective = quantifiedChunkSupporter.injectivityAxiom(tQVar, tCond, tRcvr)
            decider.prover.logComment("Check receiver injectivity")
            decider.assert(σ, receiverInjective) {
              case true =>
                decider.prover.logComment("Definitional axioms for inverse functions")
                assume(invFct.definitionalAxioms)
                val inverseReceiver = invFct(`?r`) // e⁻¹(r)
                quantifiedChunkSupporter.splitLocations(σ, h, field, Some(tQVar), inverseReceiver, tCond, tRcvr, PermTimes(pLoss, p), chunkOrderHeuristics, c1) {
                  case Some((h1, ch, fvfDef, c2)) =>
                    val fvfDomain = fvfDef.domainDefinitions(invFct)
                    decider.prover.logComment("Definitional axioms for field value function")
                    assume(fvfDomain ++ fvfDef.valueDefinitions)
                    //                if (!config.disableQPCaching())
                    //                  qpForallCache.update((forall, toSet(quantifiedChunks)), (tQVar, tRcvr, tCond, invFct.definitionalAxioms, h3, ch, c2))
                    val fr1 = c2.functionRecorder.recordQPTerms(c2.quantifiedVariables,
                                                                c2.branchConditions,
                                                                invFct.definitionalAxioms ++ fvfDomain ++ fvfDef.valueDefinitions)
                    val fr2 = if (true/*fvfDef.freshFvf*/) fr1.recordFvf(field, fvfDef.fvf) else fr1
                    val c3 = c2.copy(functionRecorder = fr2)
                    Q(h1, ch.fvf, /*ch :: */Nil, c3)
                  case None =>
                    Failure[ST, H, S](pve dueTo InsufficientPermission(fa))}
              case false =>
                Failure[ST, H, S](pve dueTo ReceiverNotInjective(fa))}
            //}
        }

      case ast.AccessPredicate(fa @ ast.FieldAccess(eRcvr, field), perm)
          if c.qpFields.contains(field) =>

        eval(σ, eRcvr, pve, c)((tRcvr, c1) =>
          eval(σ, perm, pve, c1)((tPerm, c2) => {
            val condPerms = quantifiedChunkSupporter.singletonConditionalPermissions(tRcvr, tPerm)
            val hints = quantifiedChunkSupporter.extractHints(None, None, tRcvr)
            val chunkOrderHeuristics = quantifiedChunkSupporter.hintBasedChunkOrderHeuristic(hints)
            quantifiedChunkSupporter.splitSingleLocation(σ, h, field, tRcvr, PermTimes(tPerm, p), chunkOrderHeuristics, c2) {
              case Some((h1, ch, fvfDef, c3)) =>
                assume(fvfDef.domainDefinitions ++ fvfDef.valueDefinitions)
                Q(h1, ch.valueAt(tRcvr), /*ch :: */ Nil, c3)
              case None => Failure[ST, H, S](pve dueTo InsufficientPermission(fa))
            }}))

      case let: ast.Let if !let.isPure =>
        val σC = σ \ magicWandSupporter.getEvalHeap(σ, h, c)
        handle[ast.Exp](σC, let, pve, c)((γ1, body, c1) => {
          val c2 =
            if (c1.recordEffects)
              c1.copy(letBoundVars = c1.letBoundVars ++ γ1.values)
            else
              c1
          consume(σ \+ γ1, h, p, body, pve, c2)(Q)})

      case ast.AccessPredicate(locacc, perm) =>
        val σC = σ \ magicWandSupporter.getEvalHeap(σ, h, c)
        withChunkIdentifier(σC, locacc, true, pve, c)((id, c1) =>
          eval(σC, perm, pve, c1)((tPerm, c2) =>
            decider.assert(σC, perms.IsNonNegative(tPerm)){
              case true =>
                chunkSupporter.consume(σC, h, id, PermTimes(p, tPerm), pve, c2, locacc, Some(φ))(Q)
              case false =>
                Failure[ST, H, S](pve dueTo NegativePermission(perm))}))

      case _: ast.InhaleExhaleExp =>
        Failure[ST, H, S](utils.consistency.createUnexpectedInhaleExhaleExpressionError(φ))

      /* Handle wands or wand-typed variables */
      case _ if φ.typ == ast.Wand && magicWandSupporter.isDirectWand(φ) =>
        def QL(σ: S, h: H, id: MagicWandChunkIdentifier, wand: ast.MagicWand, ve: VerificationError, c: C) = {
          heuristicsSupporter.tryOperation[H, Term, List[Chunk]](s"consume wand $wand")(σ, h, c)((σ, h, c, QS) => {
            val σC = σ \ magicWandSupporter.getEvalHeap(σ, h, c)
            val hs =
              if (c.exhaleExt) c.reserveHeaps
              else Stack(h)

            magicWandSupporter.doWithMultipleHeaps(hs, c)((h1, c1) =>
              decider.getChunk[MagicWandChunk](σC, h1, id, c1) match {
                case someChunk @ Some(ch) => (someChunk, h1 - ch, c1)
                case _ => (None, h1, c1)
              }
            ){case (Some(ch), hs1, c1) =>
                assert(c1.exhaleExt == c.exhaleExt)
                if (c.exhaleExt) {
                  /* transfer: move ch into h = σUsed*/
                  assert(hs1.size == c.reserveHeaps.size)
                  QS(h + ch, decider.fresh(sorts.Snap), List(ch), c1.copy(reserveHeaps = hs1))
                } else {
                  assert(hs1.size == 1)
                  assert(c.reserveHeaps == c1.reserveHeaps)
                  QS(hs1.head, decider.fresh(sorts.Snap), List(ch), c1)
                }

              case _ => Failure[ST, H, S](ve)}
          })(Q)
        }

        φ match {
          case wand: ast.MagicWand =>
            magicWandSupporter.createChunk(σ, wand, pve, c)((chWand, c1) => {
              val ve = pve dueTo MagicWandChunkNotFound(wand)
              QL(σ, h, chWand.id, wand, ve, c1)})
          case v: ast.AbstractLocalVar =>
            val tWandChunk = σ.γ(v).asInstanceOf[MagicWandChunkTerm].chunk
            val ve = pve dueTo NamedMagicWandChunkNotFound(v)
            QL(σ, h, tWandChunk.id, tWandChunk.ghostFreeWand, ve, c)
          case _ => sys.error(s"Expected a magic wand, but found node $φ")
        }

      case pckg @ ast.Packaging(eWand, eIn) =>
//        val pve = PackagingFailed(pckg)
        magicWandSupporter.packageWand(σ, eWand, pve, c)((chWand, c1) => {
          val h2 = h + chWand /* h2 = σUsed'' */
          val topReserveHeap = c1.reserveHeaps.head + h2
          val c2 = c1.copy(exhaleExt = c.exhaleExt,
                           reserveHeaps = topReserveHeap +: c1.reserveHeaps.drop(2),
                           lhsHeap = None,
                           consumedChunks = Nil +: c1.consumedChunks.drop(2))
          val σEmp = Σ(σ.γ, Ø, σ.g)
          consume(σEmp, σEmp.h, FullPerm(), eIn, pve, c2)((h3, _, _, c3) =>
            Q(h3, decider.fresh(sorts.Snap), Nil, c3))})

      case ast.Applying(eWandOrVar, eIn) =>
        val (eWand, eLHSAndWand, γ1) = eWandOrVar match {
          case _eWand: ast.MagicWand =>
            (_eWand, ast.And(_eWand.left, _eWand)(_eWand.left.pos, _eWand.left.info), σ.γ)
          case v: ast.AbstractLocalVar =>
            val chWand = σ.γ(v).asInstanceOf[MagicWandChunkTerm].chunk
            val _eWand = chWand.ghostFreeWand
            (_eWand, ast.And(_eWand.left, _eWand)(v.pos, v.info), Γ(chWand.bindings))
              /* Note that wand reference v is most likely not bound in tChunk.bindings.
               * Since wands cannot be recursive, this shouldn't be a problem,
               * as long as v doesn't need to be looked during
               * magicWandSupporter.applyingWand (for whatever reason).
               */
          case _ => sys.error(s"Expected a magic wand, but found node $φ")
        }

        heuristicsSupporter.tryOperation[S, H](s"applying $eWand")(σ, h, c)((σ, h, c, QS) =>
          magicWandSupporter.applyingWand(σ, γ1, eWand, eLHSAndWand, pve, c)(QS)){case (σ1, h1, c1) =>
            consume(σ1, h1, FullPerm(), eIn, pve, c1)((h4, _, _, c4) =>
              Q(h4, decider.fresh(sorts.Snap), Nil, c4))}

      case ast.Folding(acc @ ast.PredicateAccessPredicate(ast.PredicateAccess(eArgs, predicateName), ePerm),
                       eIn) =>

        heuristicsSupporter.tryOperation[S, H](s"folding $acc")(σ, h, c)((σ, h, c, QS) =>
          magicWandSupporter.foldingPredicate(σ, acc, pve, c)(QS)){case (σ1, h1, c1) =>
            consume(σ1, h1, FullPerm(), eIn, pve, c1)((h4, _, _, c4) =>
              Q(h4, decider.fresh(sorts.Snap), Nil, c4))}

      case ast.Unfolding(acc @ ast.PredicateAccessPredicate(ast.PredicateAccess(eArgs, predicateName), ePerm),
                       eIn) if c.exhaleExt && !φ.isPure =>

        heuristicsSupporter.tryOperation[S, H](s"unfolding $acc")(σ, h, c)((σ, h, c, QS) =>
          magicWandSupporter.unfoldingPredicate(σ, acc, pve, c)(QS)){case (σ1, h1, c1) =>
            consume(σ1, h1, FullPerm(), eIn, pve, c1)((h4, _, _, c4) =>
              Q(h4, decider.fresh(sorts.Snap), Nil, c4))}

      case _ =>
        val σC = σ \ magicWandSupporter.getEvalHeap(σ, h, c)
        val c0 = c.copy(reserveHeaps = Nil, exhaleExt = false)
        evalAndAssert(σC, h, φ, pve, c0)((h1, t, dcs, c1) => {
          val c2 = c1.copy(reserveHeaps = c.reserveHeaps, exhaleExt = c.exhaleExt)
          Q(h1, t, dcs, c2)
        })
    }

    consumed
  }

  /* The expression is evaluated in the initial heap (σ.h), partially consumed heap (h)
   * is made available to the evaluation via the context (C.partiallyConsumedHeap).
   */
  private def evalAndAssert(σ: S, h: H, e: ast.Exp, pve: PartialVerificationError, c: C)
                           (Q: (H, Term, List[DirectChunk], C) => VerificationResult)
                           : VerificationResult = {

    val c0 = c.copy(partiallyConsumedHeap = Some(h))

    decider.tryOrFail[S](σ, c0)((σ1, c1, QS, QF) => {
      eval(σ1, e, pve, c1)((t, c2) =>
        decider.assert(σ1, t) {
          case true =>
            assume(t)
            QS(σ1, c2)
          case false =>
            QF(Failure[ST, H, S](pve dueTo AssertionFalse(e)))
        })
    })((σ1, c1) => {
      Q(h, Unit, Nil, c1.copy(partiallyConsumedHeap = c.partiallyConsumedHeap))
    })
  }

  /* Lifetime */

  override def reset() {
    super.reset()

    qpForallCache.clear()
  }
}
