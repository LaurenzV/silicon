// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

package viper.silicon.extensions

import viper.silver.ast._
import viper.silver.ast.pretty.PrettyPrintPrimitives
import viper.silver.parser.ReformatPrettyPrinter.show
import viper.silver.parser.{NameAnalyser, PExtender, PKeywordStmt, PKw, PReserved, PStmt, ReformatterContext, Translator, TypeChecker}

/** Keyword used to define `try` statement. */
case object PTryKeyword extends PKw("try") with PKeywordStmt

final case class PTryBlock(kw: PReserved[PTryKeyword.type], body: PStmt)(val pos: (Position, Position) = (NoPosition, NoPosition)) extends PExtender with PStmt {

  override def translateStmt(translator: Translator): Stmt = {
    TryBlock(translator.stmt(body))(translator.liftPos(this))
  }

  override def typecheck(typeChecker: TypeChecker, nameAnalyser: NameAnalyser): Option[Seq[String]] = {
    typeChecker.check(body)

    None
  }

  override def reformat(ctx: ReformatterContext): Cont = show(kw, ctx) <+> show(body, ctx)
}

final case class TryBlock(body: Stmt)
                         (val pos: Position = NoPosition, val info: Info = NoInfo, val errT: ErrorTrafo = NoTrafos)
    extends ExtensionStmt {

  def extensionSubnodes: Seq[Node] = Seq(body)

  def prettyPrint: PrettyPrintPrimitives#Cont = {
    import viper.silver.ast.pretty.FastPrettyPrinter._

    text("try") <+> showBlock(body)
  }
}
