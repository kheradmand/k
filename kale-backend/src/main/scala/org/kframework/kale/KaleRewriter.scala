package org.kframework.kale

import java.util
import java.util.Optional

import org.kframework.RewriterResult
import org.kframework.attributes.Att
import org.kframework.builtin.Sorts
import org.kframework.definition._
import org.kframework.kore
import org.kframework.kore._
import org.kframework.rewriter.SearchType

import collection._
import org.kframework.kore.Unapply.KRewrite

object KaleRewriter {
  val self = this

  def apply(m: Module): KaleRewriter = new KaleRewriter(m)

  private def isEffectivelyAssoc(att: Att): Boolean =
    att.contains(Att.assoc) || att.contains(Att.bag)
}

class KaleRewriter(m: Module) extends org.kframework.rewriter.Rewriter {

  private val productionWithUniqueSorts: Set[Sentence] = (m.sentences.collect({
    case p: Production if p.klabel.isDefined => p
  }) groupBy {_.klabel.get} map {_._2.head}).toSet

  private val syntaxSortDeclarations: Set[Sentence] = m.sentences.collect({
    case p: SyntaxSort => p
  }) toSet

  private val assocProductions: Set[Sentence] = productionWithUniqueSorts.filter(p => KaleRewriter.isEffectivelyAssoc(p.att)).toSet

  private val nonAssocProductions = (productionWithUniqueSorts | syntaxSortDeclarations) &~ assocProductions

  implicit val env = Environment()

  import env._
  import env.builtin._

  val hooks: Map[String, Label] = Map[String, Label](
    "INT.le" -> env.builtin.`<=Int`,
    "INT.add" -> env.builtin.`+Int`,
    "BOOL.and" -> PrimitiveFunction2[Boolean]("_andBool_", BOOLEAN, _ && _)(env),
    "BOOL.or" -> PrimitiveFunction2[Boolean]("_orBool_", BOOLEAN, _ || _)(env),
    "BOOL.not" -> PrimitiveFunction1[Boolean]("notBool_", BOOLEAN, !_)(env)
  )

  case class IsSort(s: Sort)(implicit val env: Environment) extends {
    val name = "is" + s.name
  } with PurelyFunctionalLabel1 with FormulaLabel {
    override def f(_1: Term): Option[Term] =
      kSortOf(_1).map(ss => BOOLEAN(m.subsorts.<=(ss, m.resolve(kore.KORE.Sort(s.name)))))

    private def kSortOf(t: Term): Option[kore.Sort] =
      if (!t.isGround)
        None
      else
        m.sortFor
          .get(KORE.KLabel(t.label.name))
          .orElse(
            t.label match {
              case tokenLabel: GENERIC_TOKEN => Some(m.resolve(kore.KORE.Sort(tokenLabel.sort.name)))
              case BOOLEAN => Some(m.resolve(Sorts.Bool))
              case INT =>
                Some(m.resolve(Sorts.Int))
              case STRING => Some(m.resolve(Sorts.String))
              case `kseq` => Some(m.Sort("KItem"))
              case `emptyKSeq` => Some(m.Sort("K"))
              //            case And =>
              //              val nonFormulas = And.asSubstitutionAndTerms(t)._2.filter(!_.label.isInstanceOf[FormulaLabel])
              //              if (nonFormulas.size == 1)
              //                kSortOf(nonFormulas.head)
              //              else
              //                ??? // we have more than one non-formula term. computer the least sort?
              case Variable => None
            })
  }

  def relativeHook(relativeHook: String): Option[Label] = relativeHook.split('.').toSeq match {
    case Seq(baseLabel: String, hookName: String) => relativeHookByBaseLabel(baseLabel, hookName)
    case _ => None
  }

  def relativeHookByBaseLabel(baseLabel: String, hookName: String): Option[Label] = env.label(baseLabel) match {
    case l: MapLabel => hookName match {
      case "lookup" => Some(l.lookup)
      case _ => None
    }
    case _ => None
  }

  private val nonAssocLabels: Set[Label] = nonAssocProductions flatMap {
    case SyntaxSort(s, att) => att.get(Att.hook) flatMap hooks.get
    case p@Production(s, items, att) if !att.contains(Att.relativeHook) =>
      implicit val envv = env
      att.get(Att.hook).flatMap(hooks.get).orElse({
        if (att.contains(Att.token)) {
          if (!env.labels.exists(l => l.name == "TOKEN_" + s.name))
            Some(GENERIC_TOKEN(Sort(s.name)))
          else
            None
        } else {
          if (p.klabel.isDefined) {
            val nonTerminals = items.filter(_.isInstanceOf[NonTerminal])
            val label = if (att.contains(Att.Function)) {
              if (p.klabel.get.name.startsWith("is")) {
                IsSort(Sort(p.klabel.get.name.substring(2)))
              } else {
                nonTerminals match {
                  case Seq() => FunctionDefinedByRewritingLabel0(p.klabel.get.name)(env)
                  case Seq(_) => FunctionDefinedByRewritingLabel1(p.klabel.get.name)(env)
                  case Seq(_, _) => FunctionDefinedByRewritingLabel2(p.klabel.get.name)(env)
                  case Seq(_, _, _) => FunctionDefinedByRewritingLabel3(p.klabel.get.name)(env)
                  case Seq(_, _, _, _) => FunctionDefinedByRewritingLabel4(p.klabel.get.name)(env)
                }
              }
            } else {
              nonTerminals match {
                case Seq() => FreeLabel0(p.klabel.get.name)
                case Seq(_) => FreeLabel1(p.klabel.get.name)
                case Seq(_, _) => FreeLabel2(p.klabel.get.name)
                case Seq(_, _, _) => FreeLabel3(p.klabel.get.name)
                case Seq(_, _, _, _) => FreeLabel4(p.klabel.get.name)
              }
            }
            Some(label)
          } else
            None
        }
      })
    case _ => None
  }

  private val uninterpretedTokenLabels: Map[Sort, ConstantLabel[String]] = (nonAssocLabels collect {
    case l@GENERIC_TOKEN(s) => (s, l)
  }).toMap + (Sort("KConfigVar@BASIC-K") -> GENERIC_TOKEN(Sort("KConfigVar@BASIC-K")))

  private val nonConstantLabels: Map[String, NodeLabel] = nonAssocLabels collect {
    case l: NodeLabel => (l.name, l)
  } toMap

  private val emptyKSeq = FreeLabel0(".")(env)
  private val kseq = new AssocWithIdListLabel("~>", emptyKSeq())

  def getLabelForAtt(p: Production, att: String): Label = {
    val labels = nonAssocLabels.filter(l => l.name == p.att.get[String](att).get)
    assert(labels.size == 1)
    labels.head
  }

  assocProductions map {
    case p@Production(s, items, att) =>
      val theUnit = getLabelForAtt(p, "unit").asInstanceOf[Label0]
      val labelName = p.klabel.get.name
      val index = att.get[String]("index")
      if (index.isDefined && att.contains(Att.comm)) {
        def indexFunction(t: Term): Term = t.iterator().toList(index.get.toInt)
        MapLabel(labelName, indexFunction, theUnit())(env)
      } else
        new AssocWithIdListLabel(labelName, theUnit())(env)
  }

  nonAssocProductions collect {
    case p@Production(s, items, att) if att.contains(Att.relativeHook) =>
      relativeHook(att.get(Att.relativeHook).get)
  }

  def convert(body: K): Term = body match {
    case Unapply.KToken(s, sort) => sort match {
      case Sorts.Bool => BOOLEAN(s.toBoolean)
      case Sorts.Int => INT(s.toInt)
      case Sorts.String => STRING(s)
      case _ => uninterpretedTokenLabels(Sort(sort.name))(s)
    }
    case Unapply.KApply(klabel, list) if klabel.name == "#KSequence" => kseq(list map convert)
    case Unapply.KApply(klabel, list) => env.label(klabel.name).asInstanceOf[NodeLabel](list map convert)
    case v: KVariable => Variable(v.name)
    //      val kaleV = Variable(v.name)
    //      v.att.get(Att.sort)
    //        .map(sort => env.And(
    //          kaleV,
    //          Equality(env.label("is" + sort.name).asInstanceOf[Label1](kaleV), BOOLEAN(true)))
    //        )
    //        .getOrElse(kaleV)
    case r: KRewrite => Rewrite(convert(r.left), convert(r.right))
  }

  val pairs = m.rules collect {
    case rule@Rule(KRewrite(l@Unapply.KApply(klabel, _), r), requires, ensures, att)
      if m.attributesFor(klabel).contains(Att.`Function`) =>
      val res = (env.label(klabel.name), Rewrite(And(convert(l), Equality(convert(requires), BOOLEAN(true))), convert(r)))
      res
  }

  val groups = pairs groupBy (_._1)

  val functionRules: Map[Label, Set[Rewrite]] = groups map { case (k, v) => (k, v map (_._2)) }

  var renamedFunctionRules: Map[Label, Set[Rewrite]] = functionRules map { case (k, v) => (k, v map env.renameVariables) }

  env.seal()

  def setFunctionRules(functionRules: Map[Label, Set[Rewrite]]) {
    env.labels.collect({
      // TODO: Add an warning for when a function is not defined by either a hook or rules
      case l: FunctionDefinedByRewriting => l.setRules(functionRules.getOrElse(l, Set()))
    })
  }

  setFunctionRules(renamedFunctionRules)

  def reconstruct(inhibitForLabel: Label)(t: Term): Term = t match {
    case Node(label, children) if label != inhibitForLabel => label(children map reconstruct(inhibitForLabel) toIterable)
    case t => t
  }

  def resolveFunctionRHS(functionRules: Map[Label, Set[Rewrite]]): Map[Label, Set[Rewrite]] = {
    functionRules map { case (label, rewrites) => (label, rewrites map (rw => reconstruct(label)(rw).asInstanceOf[Rewrite])) } toMap
  }

  val finalFunctionRules = Util.fixpoint(resolveFunctionRHS)(functionRules)
  setFunctionRules(finalFunctionRules)

  val rules: Set[Rewrite] = m.rules collect {
    case rule@Rule(KRewrite(l@Unapply.KApply(klabel, _), r), requires, ensures, att)
      if !att.contains(Att.`macro`) && !m.attributesFor(klabel).contains(Att.`Function`) =>
      val rw = Rewrite(And(convert(l), Equality(convert(requires), BOOLEAN(true))), convert(r))
      rw
  }

  val unifier = Matcher(env)
  val substitutionApplier = SubstitutionApply(env)
  val rewriterConstructor = Rewriter(substitutionApplier, unifier, env) _

  println("\nFunction rules\n")

  finalFunctionRules.foreach({ case (l, rules) => println(l); println(rules.map("  " + _).mkString("\n")) })

  println("\nRewriting rules\n")

  println(rules.mkString("\n"))

  val rewrite = rewriterConstructor(rules)

  def convertBack(term: Term): K = {
    println(term)
    ???
  }

  override def execute(k: K, depth: Optional[Integer]): RewriterResult = {
    var i = 0
    var term: Term = null
    var next = convert(k)
    val startTime = System.nanoTime()
    do {
      term = next
      if (i % 100 == 0)
        println("step " + i + " : " + next)
      next = rewrite.executionStep(term)
      i += 1
    } while (term != next)
    val endTime = System.nanoTime()
    println((endTime - startTime) / Math.pow(10, 9))
    new RewriterResult(Optional.of(0), convertBack(term))
  }

  override def `match`(k: K, rule: Rule): util.List[_ <: util.Map[_ <: KVariable, _ <: K]] = ???

  override def search(initialConfiguration: K, depth: Optional[Integer], bound: Optional[Integer], pattern: Rule, searchType: SearchType): util.List[_ <: util.Map[_ <: KVariable, _ <: K]] = ???

  override def executeAndMatch(k: K, depth: Optional[Integer], rule: Rule): (RewriterResult, util.List[_ <: util.Map[_ <: KVariable, _ <: K]]) = ???

  override def prove(rules: util.List[Rule]): util.List[K] = ???
}
