// NOTE(scala-gazelle): embedded from https://github.com/scala/scala/blob/v2.13.16/src/compiler/scala/tools/nsc/typechecker/Namers.scala

/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc. dba Akka
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package scala.tools.nsc
package typechecker

import scala.annotation._
import scala.collection.mutable
import symtab.Flags._
import scala.reflect.internal.util.ListOfNil
import scala.tools.nsc.Reporting.WarningCategory
import scala.util.chaining._

/** This trait declares methods to create symbols and to enter them into scopes.
 *
 *  @author Martin Odersky
 */
trait Namers extends MethodSynthesis {
  self: Analyzer =>

  import global._
  import definitions._

  /** Replaces any Idents for which cond is true with fresh TypeTrees().
   *  Does the same for any trees containing EmptyTrees.
   */
  private class TypeTreeSubstituter(cond: Name => Boolean) extends AstTransformer {
    override def transform(tree: Tree): Tree = tree match {
      case Ident(name) if cond(name) => TypeTree()
      case _                         => super.transform(tree)
    }
    def apply(tree: Tree) = {
      val r = transform(tree)
      if (r exists { case tt: TypeTree => tt.isEmpty case _ => false })
        TypeTree()
      else r
    }
  }

  private def isTemplateContext(ctx: Context): Boolean = ctx.tree match {
    case Template(_, _, _) => true
    case Import(_, _)      => isTemplateContext(ctx.outer)
    case _                 => false
  }

  private class NormalNamer(context: Context) extends Namer(context)
  def newNamer(context: Context): Namer = new NormalNamer(context)

  abstract class Namer(val context: Context) extends MethodSynth with NamerContextErrors { thisNamer =>
    // overridden by the presentation compiler
    def saveDefaultGetter(meth: Symbol, default: Symbol): Unit = { }

    def expandMacroAnnotations(stats: List[Tree]): List[Tree] = stats

    import NamerErrorGen._
    val typer = newTyper(context)

    private lazy val innerNamer =
      if (isTemplateContext(context)) createInnerNamer() else this

    def createNamer(tree: Tree): Namer = {
      val sym = tree match {
        case ModuleDef(_, _, _) => tree.symbol.moduleClass
        case _                  => tree.symbol
      }
      def isConstrParam(vd: ValDef) = {
        (sym hasFlag PARAM | PRESUPER) &&
        !vd.mods.isJavaDefined &&
        sym.owner.isConstructor
      }
      val ownerCtx = tree match {
        case vd: ValDef if isConstrParam(vd) =>
          context.makeConstructorContext
        case _ =>
          context
      }
      newNamer(ownerCtx.makeNewScope(tree, sym))
    }
    def createInnerNamer() = {
      newNamer(context.make(context.tree, owner, newScope))
    }
    def createPrimaryConstructorParameterNamer: Namer = { //todo: can we merge this with SCCmode?
      val classContext = context.enclClass
      val outerContext = classContext.outer.outer
      val paramContext = outerContext.makeNewScope(outerContext.tree, outerContext.owner)

      owner.unsafeTypeParams foreach (paramContext.scope enter _)
      newNamer(paramContext)
    }

    def enclosingNamerWithScope(scope: Scope) = {
      var cx = context
      while (cx != NoContext && cx.scope != scope) cx = cx.outer
      if (cx == NoContext || cx == context) thisNamer
      else newNamer(cx)
    }

    def enterValueParams(vparamss: List[List[ValDef]]): List[List[Symbol]] =
      mmap(vparamss) { param =>
        enterInScope(assignMemberSymbol(param, mask = ValueParameterFlags)) setInfo monoTypeCompleter(param)
      }

    protected def owner       = context.owner
    def contextFile = context.unit.source.file
    def typeErrorHandler[T](tree: Tree, alt: T): PartialFunction[Throwable, T] = {
      case ex: TypeError if !global.propagateCyclicReferences =>
        // H@ need to ensure that we handle only cyclic references
        TypeSigError(tree, ex)
        alt
    }

    // All lazy vals need accessors, including those owned by terms (e.g., in method) or private[this] in a class
    def deriveAccessors(vd: ValDef) = (vd.mods.isLazy || owner.isTrait || (owner.isClass && deriveAccessorsInClass(vd)))

    private def deriveAccessorsInClass(vd: ValDef) =
      !vd.mods.isPrivateLocal && // note, private[this] lazy vals do get accessors -- see outer disjunction of deriveAccessors
      !(vd.name startsWith nme.OUTER) && // outer accessors are added later, in explicitouter
      !isEnumConstant(vd)                // enums can only occur in classes, so only check here


    /** Determines whether this field holds an enum constant.
      * To qualify, the following conditions must be met:
      *  - The field's class has the ENUM flag set
      *  - The field's class extends java.lang.Enum
      *  - The field has the ENUM flag set
      *  - The field is static
      *  - The field is stable
      */
    def isEnumConstant(vd: ValDef) = {
      val ownerHasEnumFlag =
        // Necessary to check because scalac puts Java's static members into the companion object
        // while Scala's enum constants live directly in the class.
        // We don't check for clazz.superClass == JavaEnumClass, because this causes an illegal
        // cyclic reference error. See the commit message for details.
        if (context.unit.isJava) owner.companionClass.hasJavaEnumFlag else owner.hasJavaEnumFlag
      vd.mods.hasAllFlags(JAVA_ENUM | STABLE | STATIC) && ownerHasEnumFlag
    }

    def setPrivateWithin[T <: Symbol](tree: Tree, sym: T, mods: Modifiers): sym.type =
      if (sym.isPrivateLocal) sym
      else {
        val qualClass = if (mods.hasAccessBoundary)
          typer.qualifyingClass(tree, mods.privateWithin, packageOK = true, immediate = false)
        else
          NoSymbol
        sym setPrivateWithin qualClass
      }

    def setPrivateWithin(tree: MemberDef, sym: Symbol): sym.type =
      setPrivateWithin(tree, sym, tree.mods)

    def inConstructorFlag: Long = {
      @tailrec def go(context: Context): Long =
        if (context eq NoContext) 0L else {
          val owner = context.owner
          if (!owner.isTerm || owner.isAnonymousFunction) 0L
          else if (owner.isConstructor) if (context.inConstructorSuffix) 0L else INCONSTRUCTOR
          else if (owner.isEarlyInitialized) INCONSTRUCTOR
          else go(context.outer)
        }

      go(context)
    }

    def moduleClassFlags(moduleFlags: Long) =
      (moduleFlags & ModuleToClassFlags) | inConstructorFlag

    def updatePosFlags(sym: Symbol, pos: Position, flags: Long): sym.type = {
      debuglog("[overwrite] " + sym)
      val newFlags = (sym.flags & LOCKED) | flags
      // !!! needed for: pos/t5954d; the uniques type cache will happily serve up the same TypeRef
      // over this mutated symbol, and we witness a stale cache for `parents`.
      invalidateCaches(sym.rawInfo, Set(sym, sym.moduleClass))
      sym reset NoType setFlag newFlags setPos pos
      sym.moduleClass andAlso (updatePosFlags(_, pos, moduleClassFlags(flags)))

      if (sym.isTopLevel) {
        companionSymbolOf(sym, context) andAlso { companion =>
          val assignNoType = companion.rawInfo match {
            case _: SymLoader => true
            case tp           => tp.isComplete && (runId(sym.validTo) != currentRunId)
          }
          // pre-set linked symbol to NoType, in case it is not loaded together with this symbol.
          if (assignNoType)
            companion setInfo NoType
        }
      }
      sym
    }
    def namerOf(sym: Symbol): Namer = {
      val usePrimary = sym.isTerm && (
           (sym.isParamAccessor)
        || (sym.isParameter && sym.owner.isPrimaryConstructor)
      )

      if (usePrimary) createPrimaryConstructorParameterNamer
      else innerNamer
    }

    private def inCurrentScope(m: Symbol): Boolean = {
      if (owner.isClass) owner == m.owner
      else context.scope.lookupSymbolEntry(m) match {
        case null => false
        case entry => entry.owner eq context.scope
      }
    }

    /** Enter symbol into context's scope and return symbol itself */
    def enterInScope(sym: Symbol): sym.type = enterInScope(sym, context.scope)

    // There is nothing which reconciles a package's scope with
    // the package object's scope.  This is the source of many bugs
    // with e.g. defining a case class in a package object.  When
    // compiling against classes, the class symbol is created in the
    // package and in the package object, and the conflict is undetected.
    // There is also a non-deterministic outcome for situations like
    // an object with the same name as a method in the package object.
    /** Enter symbol into given scope and return symbol itself */
    def enterInScope(sym: Symbol, scope: Scope): sym.type = {
      if (sym.isModule && sym.isSynthetic && sym.owner.isClass && !sym.isTopLevel) {
        val entry = scope.lookupEntry(sym.name.toTypeName)
        if (entry eq null)
          scope enter sym
        else
          scope.enterBefore(sym, entry)
      } else {
        val disallowsOverload = !(sym.isSourceMethod && sym.owner.isClass && !sym.isTopLevel)
        if (disallowsOverload) {
          val prev = scope.lookupEntry(sym.name)
          val dde =
            (prev ne null) && prev.owner == scope &&
            (!prev.sym.isSourceMethod || nme.isSetterName(sym.name) || sym.isTopLevel) &&
            !((sym.owner.isTypeParameter || sym.owner.isAbstractType) && (sym.name string_== nme.WILDCARD))
            // @M: allow repeated use of `_` for higher-order type params
          if (dde) {
            if (sym.isSynthetic || prev.sym.isSynthetic) {
              handleSyntheticNameConflict(sym, prev.sym)
              handleSyntheticNameConflict(prev.sym, sym)
            }
            DoubleDefError(sym, prev.sym)
            sym.setInfo(ErrorType)
            scope.unlink(prev.sym) // retain the new erroneous symbol in scope (was for IDE); see #scala/bug#2779
          }
        }
        scope.enter(sym)
      }
    }

    /** Logic to handle name conflicts of synthetically generated symbols
     *  We handle right now: t6227
     */
    def handleSyntheticNameConflict(sym1: Symbol, sym2: Symbol) = {
      if (sym1.isImplicit && sym1.isMethod && sym2.isModule && sym2.companionClass.isCaseClass)
        validate(sym2.companionClass)
    }

    def enterSym(tree: Tree): Context = pluginsEnterSym(this, tree)

    /** Default implementation of `enterSym`.
     *  Can be overridden by analyzer plugins (see AnalyzerPlugins.pluginsEnterSym for more details)
     */
    def standardEnterSym(tree: Tree): Context = {
      def dispatch() = {
        var returnContext = this.context
        tree match {
          case tree @ PackageDef(_, _)                       => enterPackage(tree)
          case tree @ ClassDef(_, _, _, _)                   => enterClassDef(tree)
          case tree @ ModuleDef(_, _, _)                     => enterModuleDef(tree)
          case tree @ ValDef(_, _, _, _)                     => enterValDef(tree)
          case tree @ DefDef(_, _, _, _, _, _)               => enterDefDef(tree)
          case tree @ TypeDef(_, _, _, _)                    => enterTypeDef(tree)
          case DocDef(_, defn)                               => enterSym(defn)
          case tree @ Import(_, _)                           => enterImport(tree); returnContext = context.makeImportContext(tree)
          case _ =>
        }
        returnContext
      }
      tree.symbol match {
        case NoSymbol => try dispatch() catch typeErrorHandler(tree, this.context)
        case sym      =>
          tree match {
            case tree@Import(_, _) => enterExistingSym(sym, tree).make(tree)
            case _ => enterExistingSym(sym, tree)
          }
      }
    }

    def assignMemberSymbol(tree: MemberDef, mask: Long = -1L): Symbol = {
      val sym = createMemberSymbol(tree, tree.name, mask)
      setPrivateWithin(tree, sym)
      tree.symbol = sym
      sym
    }

    def assignAndEnterFinishedSymbol(tree: MemberDef): Symbol = {
      val sym = enterInScope(assignMemberSymbol(tree))
      sym setInfo completerOf(tree)
      // log("[+info] " + sym.fullLocationString)
      sym
    }

    def createMethod(accessQual: MemberDef, name: TermName, pos: Position, flags: Long): MethodSymbol = {
      val sym = owner.newMethod(name, pos, flags)
      setPrivateWithin(accessQual, sym)
      sym
    }

    /** Create a new symbol at the context owner based on the given tree.
     *  A different name can be given.  If the modifier flags should not be
     *  be transferred to the symbol as they are, supply a mask containing
     *  the flags to keep.
     */
    def createMemberSymbol(tree: MemberDef, name: Name, mask: Long): Symbol = {
      val pos         = tree.namePos
      val isParameter = tree.mods.isParameter
      val flags       = tree.mods.flags & mask

      tree match {
        case TypeDef(_, _, _, _) if isParameter     => owner.newTypeParameter(name.toTypeName, pos, flags)
        case TypeDef(_, _, _, _)                    => owner.newTypeSymbol(name.toTypeName, pos, flags)
        case DefDef(_, nme.CONSTRUCTOR, _, _, _, _) => owner.newConstructor(pos, flags)
        case DefDef(_, _, _, _, _, _)               => owner.newMethod(name.toTermName, pos, flags)
        case ClassDef(_, _, _, _)                   => owner.newClassSymbol(name.toTypeName, pos, flags)
        case ModuleDef(_, _, _)                     => owner.newModule(name.toTermName, pos, flags)
        case PackageDef(pid, _)                     => createPackageSymbol(pos, pid)
        case ValDef(_, _, _, _)                     =>
          if (isParameter) owner.newValueParameter(name.toTermName, pos, flags)
          else owner.newValue(name.toTermName, pos, flags)
      }
    }

    def createImportSymbol(tree: Import) =
      NoSymbol.newImport(tree.pos).setInfo(namerOf(tree.symbol).importTypeCompleter(tree))

    /** All PackageClassInfoTypes come from here. */
    def createPackageSymbol(pos: Position, pid: RefTree): Symbol = {
      val pkgOwner = pid match {
        case Ident(_)                 => if (owner.isEmptyPackageClass) rootMirror.RootClass else owner
        case Select(qual: RefTree, _) => createPackageSymbol(pos, qual).moduleClass
        case x                        => throw new MatchError(x)
      }
      val existing = pkgOwner.info.decls.lookup(pid.name)

      if (existing.hasPackageFlag && pkgOwner == existing.owner)
        existing
      else {
        val pkg          = pkgOwner.newPackage(pid.name.toTermName, pos)
        val pkgClass     = pkg.moduleClass
        val pkgClassInfo = new PackageClassInfoType(newPackageScope(pkgClass), pkgClass)

        pkgClass setInfo pkgClassInfo
        pkg setInfo pkgClass.tpe
        enterInScope(pkg, pkgOwner.info.decls)
      }
    }

    private def enterClassSymbol(@unused tree: ClassDef, clazz: ClassSymbol): Symbol = {
      var sourceFile = clazz.sourceFile
      if (sourceFile != null && sourceFile != contextFile)
        devWarning(s"Source file mismatch in $clazz: ${sourceFile} vs. $contextFile")

      clazz.associatedFile = contextFile
      sourceFile = clazz.sourceFile
      if (sourceFile != null) {
        assert(currentRun.canRedefine(clazz) || sourceFile == currentRun.symSource(clazz), sourceFile)
        currentRun.symSource(clazz) = sourceFile
      }
      registerTopLevelSym(clazz)
      assert(clazz.name.toString.indexOf('(') < 0, clazz.name)  // )
      clazz
    }

    def enterClassSymbol(tree: ClassDef): Symbol = {
      val existing = context.scope.lookup(tree.name)
      val isRedefinition = (
           existing.isType
        && existing.isTopLevel
        && context.scope == existing.owner.info.decls
        && currentRun.canRedefine(existing)
      )
      val clazz: Symbol = {
        if (isRedefinition) {
          updatePosFlags(existing, tree.pos, tree.mods.flags)
          setPrivateWithin(tree, existing)
          clearRenamedCaseAccessors(existing)
          existing
        }
        else enterInScope(assignMemberSymbol(tree)) setFlag inConstructorFlag
      }
      clazz match {
        case csym: ClassSymbol if csym.isTopLevel => enterClassSymbol(tree, csym)
        case _                                    => clazz
      }
    }

    /** Given a ClassDef or ModuleDef, verifies there isn't a companion which
     *  has been defined in a separate file.
     */
    def validateCompanionDefs(tree: ImplDef): Unit = {
      val sym = tree.symbol
      if (sym != NoSymbol) {
        val ctx    = if (context.owner.isPackageObjectClass) context.outer else context
        val module = if (sym.isModule) sym else ctx.scope.lookupModule(tree.name)
        val clazz  = if (sym.isClass) sym else ctx.scope.lookupClass(tree.name)
        val fails  = (
             module.isModule
          && clazz.isClass
          && !module.isSynthetic
          && !clazz.isSynthetic
          && (clazz.sourceFile ne null)
          && (module.sourceFile ne null)
          && !module.isCoDefinedWith(clazz)
          && module.exists
          && clazz.exists
          && currentRun.compiles(clazz) == currentRun.compiles(module)
        )
        if (fails) reporter.error(tree.pos,
          sm"""|Companions '$clazz' and '$module' must be defined in same file:
               |  Found in ${clazz.sourceFile.canonicalPath} and ${module.sourceFile.canonicalPath}""")
      }
    }

    def enterModuleDef(tree: ModuleDef): Unit = {
      val sym = enterModuleSymbol(tree)
      sym.moduleClass setInfo namerOf(sym).moduleClassTypeCompleter(tree)
      sym setInfo completerOf(tree)
      validateCompanionDefs(tree)
    }

    /** Enter a module symbol.
     */
    def enterModuleSymbol(tree: ModuleDef): Symbol = {
      val moduleFlags = tree.mods.flags | MODULE

      val existingModule = context.scope lookupModule tree.name
      if (existingModule.isModule && !existingModule.hasPackageFlag && inCurrentScope(existingModule) && (currentRun.canRedefine(existingModule) || existingModule.isSynthetic)) {
        updatePosFlags(existingModule, tree.pos, moduleFlags)
        setPrivateWithin(tree, existingModule)
        existingModule.moduleClass.andAlso(setPrivateWithin(tree, _))
        context.unit.synthetics -= existingModule
        tree.symbol = existingModule
      }
      else {
        enterInScope(assignMemberSymbol(tree))
        val m = tree.symbol
        m.moduleClass setFlag moduleClassFlags(moduleFlags)
        setPrivateWithin(tree, m.moduleClass)
      }

      val m = tree.symbol
      if (m.isTopLevel && !m.hasPackageFlag) {
        // TODO: I've seen crashes where m.moduleClass == NoSymbol
        m.moduleClass.associatedFile = contextFile
        currentRun.symSource(m) = m.moduleClass.sourceFile
        registerTopLevelSym(m)
      }
      m
    }

    def enterSyms(trees: List[Tree]): Unit =
      trees.foldLeft(this: Namer) { (namer, t) =>
        val ctx = namer enterSym t
        // for Import trees, enterSym returns a changed context, so we need a new namer
        if (ctx eq namer.context) namer
        else newNamer(ctx)
      }

    def applicableTypeParams(owner: Symbol): List[Symbol] =
      if (owner.isTerm || owner.isPackageClass) Nil
      else applicableTypeParams(owner.owner) ::: owner.typeParams

    /** If no companion object for clazz exists yet, create one by applying `creator` to
     *  class definition tree.
     *  @return the companion object symbol.
     */
    def ensureCompanionObject(cdef: ClassDef, creator: ClassDef => Tree = companionModuleDef(_)): Symbol =
      pluginsEnsureCompanionObject(this, cdef, creator)

    /** Default implementation of `ensureCompanionObject`.
     *  Can be overridden by analyzer plugins (see AnalyzerPlugins.pluginsEnsureCompanionObject for more details)
     */
    def standardEnsureCompanionObject(cdef: ClassDef, creator: ClassDef => Tree = companionModuleDef(_)): Symbol = {
      val m = companionSymbolOf(cdef.symbol, context)
      // @luc: not sure why "currentRun.compiles(m)" is needed, things breaks
      // otherwise. documentation welcome.
      //
      // @PP: I tried to reverse engineer said documentation.  The only tests
      // which fail are buildmanager tests, as follows.  Given A.scala:
      //   case class Foo()
      // If you recompile A.scala, the Changes Map is
      //   Map(class Foo -> Nil, object Foo -> Nil)
      // But if you remove the 'currentRun.compiles(m)' condition, it is
      //   Map(class Foo -> Nil)
      // What exactly this implies and whether this is a sensible way to
      // enforce it, I don't know.
      //
      // @martin: currentRun.compiles is needed because we might have a stale
      // companion object from another run in scope. In that case we should still
      // overwrite the object. I.e.
      // Compile run #1: object Foo { ... }
      // Compile run #2: case class Foo ...
      // The object Foo is still in scope, but because it is not compiled in current run
      // it should be ditched and a new one created.
      if (m != NoSymbol && currentRun.compiles(m)) m
      else enterSyntheticSym(atPos(cdef.pos.focus)(creator(cdef)))
    }

    private def checkSelectors(tree: Import): Unit = {
      val Import(expr, selectors) = tree
      val base = expr.tpe

      // warn proactively if specific import loses to definition in scope,
      // since it may result in desired implicit not imported into scope.
      def checkNotRedundant(pos: Position, from: Name, to0: Name): Unit = {
        def check(to: Name): Unit = {
          val e = context.scope.lookupEntry(to)

          if (e != null && e.owner == context.scope && e.sym.exists) {
            if (!context.isPackageOwnedInDifferentUnit(e.sym))
              typer.permanentlyHiddenWarning(pos, to0, e.sym)
          } else if (context ne context.enclClass) {
            val defSym = context.prefix.member(to) filter (
              sym => sym.exists && context.isAccessible(sym, context.prefix, superAccess = false))

            defSym andAlso (typer.permanentlyHiddenWarning(pos, to0, _))
          }
        }
        if (!tree.symbol.isSynthetic && expr.symbol != null && !expr.symbol.isInterpreterWrapper) {
          if (base.member(from).exists)
            check(to0)
          if (base.member(from.toTypeName).exists)
            check(to0.toTypeName)
        }
      }
      def checkSelector(s: ImportSelector) = {
        val ImportSelector(from, fromPos, to, _) = s
        def isValid(original: Name, base: Type) = {
          def lookup(name: Name) =
            if (context.unit.isJava)
              NoContext.javaFindMember(base, name, _ => true)._2
            else
              base.nonLocalMember(name)
          lookup(original.toTermName) != NoSymbol || lookup(original.toTypeName) != NoSymbol
        }

        if (!s.isWildcard && !s.isGiven && base != ErrorType) {
          val okay = isValid(from, base) || context.unit.isJava && (      // Java code...
               (nme.isModuleName(from) && isValid(from.dropModule, base)) // - importing Scala module classes
            || isValid(from, base.companion)                              // - importing type members from types
          )
          if (!okay) typer.TyperErrorGen.NotAMemberError(tree, expr, from, context.outer)

          // Setting the position at the import means that if there is
          // more than one hidden name, the second will not be warned.
          // So it is the position of the actual hidden name.
          //
          // Note: java imports have precedence over definitions in the same package
          //       so don't warn for them. There is a corresponding special treatment
          //       in the shadowing rules in typedIdent to (scala/bug#7232). In any case,
          //       we shouldn't be emitting warnings for .java source files.
          if (!context.unit.isJava) {
            val at = if (tree.pos.isRange) tree.pos.withPoint(fromPos) else tree.pos
            checkNotRedundant(at, from, to)
          }
        }
      }
      selectors.foreach(checkSelector)
    }

    def copyMethodCompleter(copyDef: DefDef): TypeCompleter = {
      /* Assign the types of the class parameters to the parameters of the
       * copy method. See comment in `Unapplies.caseClassCopyMeth`
       */
      def assignParamTypes(copyDef: DefDef, sym: Symbol): Unit = {
        val clazz = sym.owner
        val constructorType = clazz.primaryConstructor.tpe
        val subst = SubstSymMap(clazz.typeParams, copyDef.tparams.map(_.symbol))
        val classParamss = constructorType.paramss

        foreach2(copyDef.vparamss, classParamss)((copyParams, classParams) =>
          foreach2(copyParams, classParams)((copyP, classP) =>
            copyP.tpt setType subst(classP.tpe)
          )
        )
      }

      new CompleterWrapper(completerOf(copyDef)) {
        override def complete(sym: Symbol): Unit = {
          assignParamTypes(tree.asInstanceOf[DefDef], sym)
          super.complete(sym)
        }
      }
    }

    // for apply/unapply, which may need to disappear when they clash with a user-defined method of matching signature
    def applyUnapplyMethodCompleter(un_applyDef: DefDef, companionContext: Context): TypeCompleter =
      new CompleterWrapper(completerOf(un_applyDef)) {
        override def complete(sym: Symbol): Unit = {
          assert(sym hasAllFlags CASE | SYNTHETIC, sym.defString)

          super.complete(sym)

          // don't propagate e.g. @volatile annot to apply's argument
          def retainOnlyParamAnnots(param: Symbol) =
            param setAnnotations (param.annotations filter AnnotationInfo.mkFilter(ParamTargetClass, defaultRetention = false))

          sym.info.paramss.foreach(_.foreach(retainOnlyParamAnnots))

          // owner won't be locked
          val ownerInfo = companionContext.owner.info

          // If there's a same-named locked symbol, we're currently completing its signature.
          // If `scopePartiallyCompleted`, the program is known to have a type error, since
          // this means a user-defined method is missing a result type while its rhs refers to `sym` or an overload.
          // This is an error because overloaded/recursive methods must have a result type.
          // The method would be overloaded if its signature, once completed, would not match the synthetic method's,
          // or recursive if it turned out we should unlink our synthetic method (matching sig).
          // In any case, error out. We don't unlink the symbol so that `symWasOverloaded` says yes,
          // which would be wrong if the method is in fact recursive, but it seems less confusing.
          val scopePartiallyCompleted = new HasMember(ownerInfo, sym.name, BridgeFlags | SYNTHETIC, LOCKED).apply()

          // Check `scopePartiallyCompleted` first to rule out locked symbols from the owner.info.member call,
          // as FindMember will call info on a locked symbol (while checking type matching to assemble an overloaded type),
          // and throw a TypeError, so that we are aborted.
          // Do not consider deferred symbols, as suppressing our concrete implementation would be an error regardless
          // of whether the signature matches (if it matches, we omitted a valid implementation, if it doesn't,
          // we would get an error for the missing implementation it isn't implemented by some overload other than our synthetic one)
          val suppress = scopePartiallyCompleted || {
            // can't exclude deferred members using DEFERRED flag here (TODO: why?)
            val userDefined = ownerInfo.memberBasedOnName(sym.name, BridgeFlags | SYNTHETIC)

            (userDefined != NoSymbol) && {
              assert(userDefined != sym, "userDefined symbol cannot be the same as symbol of which it is a member")
              val alts = userDefined.alternatives // could be just the one, if this member isn't overloaded
              // don't compute any further `memberInfo`s if there's an error somewhere
              alts.exists(_.isErroneous) || {
                val self = companionContext.owner.thisType
                val memberInfo = self.memberInfo(sym)
                alts.exists(alt => !alt.isDeferred && (self.memberInfo(alt) matches memberInfo))
              }
            }
          }

          if (suppress) {
            sym setInfo ErrorType

            // There are two ways in which we exclude the symbol from being added in typedStats::addSynthetics,
            // because we don't know when the completer runs with respect to this loop in addSynthetics
            //  for (sym <- scope)
            //    for (tree <- context.unit.synthetics.get(sym) if shouldAdd(sym))
            //      if (!sym.initialize.hasFlag(IS_ERROR))
            //        newStats += typedStat(tree)
            // If we're already in the loop, set the IS_ERROR flag and trigger the condition `sym.initialize.hasFlag(IS_ERROR)`
            sym setFlag IS_ERROR
            // Or, if we are not yet in the addSynthetics loop, we can just retract our symbol from the synthetics for this unit.
            companionContext.unit.synthetics -= sym

            // Don't unlink in an error situation to generate less confusing error messages.
            // Ideally, our error reporting would distinguish overloaded from recursive user-defined apply methods without signature,
            // but this would require some form of partial-completion of method signatures, so that we can
            // know what the argument types were, even though we can't complete the result type, because
            // we hit a cycle while trying to compute it (when we get here with locked user-defined symbols, we
            // are in the complete for that symbol, and thus the locked symbol has not yet received enough info;
            // I hesitate to provide more info, because it would involve a WildCard or something for its result type,
            // which could upset other code paths)
            if (!scopePartiallyCompleted)
              companionContext.scope.unlink(sym)

            for (a <- sym.attachments.get[CaseApplyDefaultGetters]; defaultGetter <- a.defaultGetters) {
              companionContext.unit.synthetics -= defaultGetter
              companionContext.scope.unlink(defaultGetter)
            }
          }

          sym.removeAttachment[CaseApplyDefaultGetters] // no longer needed once the completer is done
        }
      }

    def completerOf(tree: MemberDef): TypeCompleter = {
      val mono = namerOf(tree.symbol) monoTypeCompleter tree
      val tparams = treeInfo.typeParameters(tree)
      if (tparams.isEmpty) mono
      else {
        /* @M! TypeDef's type params are handled differently, e.g., in `type T[A[x <: B], B]`, A and B are entered
         * first as both are in scope in the definition of x. x is only in scope in `A[x <: B]`.
         * No symbols are created for the abstract type's params at this point, i.e. the following assertion holds:
         *     !tree.symbol.isAbstractType || tparams.forall(_.symbol == NoSymbol)
         * (tested with the above example, `trait C { type T[A[X <: B], B] }`). See also comment in PolyTypeCompleter.
         */
        if (!tree.symbol.isAbstractType) //@M TODO: change to isTypeMember ?
          createNamer(tree) enterSyms tparams

        new PolyTypeCompleter(tparams, mono, context) //@M
      }
    }

    def enterValDef(tree: ValDef): Unit = {
      val isScala = !context.unit.isJava
      if (isScala) {
        if (nme.isSetterName(tree.name)) ValOrVarWithSetterSuffixError(tree)
        if (tree.mods.isPrivateLocal && tree.mods.isCaseAccessor) PrivateThisCaseClassParameterError(tree)
      }

      if (isScala && deriveAccessors(tree)) enterGetterSetter(tree)
      else assignAndEnterFinishedSymbol(tree)

      if (isEnumConstant(tree)) {
        val annots = annotSig(tree.mods.annotations, tree, _ => true)
        if (annots.nonEmpty) annotate(tree.symbol, annots)
        tree.symbol setInfo ConstantType(Constant(tree.symbol))
        tree.symbol.owner.linkedClassOfClass addChild tree.symbol
      }
    }

    def enterPackage(tree: PackageDef): Unit = {
      val sym = createPackageSymbol(tree.pos, tree.pid)
      tree.symbol = sym
      newNamer(context.make(tree, sym.moduleClass, sym.info.decls)) enterSyms tree.stats
    }

    private def enterImport(tree: Import): Unit = {
      val sym = createImportSymbol(tree)
      tree.symbol = sym
    }

    def enterTypeDef(tree: TypeDef): Unit = assignAndEnterFinishedSymbol(tree)

    def enterDefDef(tree: DefDef): Unit = {
      tree match {
        case DefDef(_, nme.CONSTRUCTOR, _, _, _, _) =>
          assignAndEnterFinishedSymbol(tree)
        case DefDef(mods, name, _, _, _, _) =>
          val sym = enterInScope(assignMemberSymbol(tree))

          val completer =
            if (sym hasFlag SYNTHETIC) {
              if (name == nme.copy) copyMethodCompleter(tree)
              else if (sym hasFlag CASE) applyUnapplyMethodCompleter(tree, context)
              else completerOf(tree)
            } else completerOf(tree)

          sym setInfo completer
      }
      if (mexists(tree.vparamss)(_.mods.hasDefault))
        enterDefaultGetters(tree.symbol, tree, tree.vparamss, tree.tparams)
    }

    def enterClassDef(tree: ClassDef): Unit = {
      val ClassDef(mods, _, _, impl) = tree
      val primaryConstructorArity = treeInfo.firstConstructorArgs(impl.body).size
      tree.symbol = enterClassSymbol(tree)
      tree.symbol setInfo completerOf(tree)

      if (tree.symbol.isJava) patmat.javaClassesByUnit.get(tree.symbol.pos.source).foreach(_.addOne(tree.symbol))

      if (mods.isCase) {
        val m = ensureCompanionObject(tree, caseModuleDef)
        m.moduleClass.updateAttachment(new ClassForCaseCompanionAttachment(tree))
      }
      val hasDefault = impl.body exists treeInfo.isConstructorWithDefault
      if (hasDefault) {
        val m = ensureCompanionObject(tree)
        m.updateAttachment(new ConstructorDefaultsAttachment(tree, null))
      }
      val owner = tree.symbol.owner
      if (settings.warnPackageObjectClasses && owner.isPackageObjectClass && !mods.isImplicit) {
        context.warning(tree.pos,
          "it is not recommended to define classes/objects inside of package objects.\n" +
          "If possible, define " + tree.symbol + " in " + owner.skipPackageObject + " instead.",
          WarningCategory.LintPackageObjectClasses)
      }

      // Suggested location only.
      if (mods.isImplicit) {
        if (primaryConstructorArity == 1) {
          log("enter implicit wrapper "+tree+", owner = "+owner)
          enterImplicitWrapper(tree)
        }
        else reporter.error(tree.pos, "implicit classes must accept exactly one primary constructor parameter")
      }
      validateCompanionDefs(tree)
    }

    // Hooks which are overridden in the presentation compiler
    def enterExistingSym(@unused sym: Symbol, @unused tree: Tree): Context = {
      this.context
    }
    def enterIfNotThere(sym: Symbol): Unit = ()

    def enterSyntheticSym(tree: Tree): Symbol = {
      enterSym(tree)
      context.unit.synthetics(tree.symbol) = tree
      tree.symbol
    }

// --- Lazy Type Assignment --------------------------------------------------

    @nowarn("cat=lint-nonlocal-return")
    def findCyclicalLowerBound(tp: Type): Symbol = {
      tp match {
        case TypeBounds(lo, _) =>
          // check that lower bound is not an F-bound
          // but carefully: class Foo[T <: Bar[_ >: T]] should be allowed
          for (TypeRef(_, sym, _) <- lo) {
            if (settings.breakCycles.value) {
              if (!sym.maybeInitialize) {
                log(s"Cycle inspecting $lo for possible f-bounds: ${sym.fullLocationString}")
                return sym
              }
            }
            else sym.initialize
          }
        case _ =>
      }
      NoSymbol
    }

    def monoTypeCompleter(tree: MemberDef) = new MonoTypeCompleter(tree)
    class MonoTypeCompleter(tree: MemberDef) extends TypeCompleterBase(tree) {
      override def completeImpl(sym: Symbol): Unit = {
        def needsCycleCheck = sym.isNonClassType && !sym.isParameter && !sym.isExistential

        val annotations = annotSig(tree.mods.annotations, tree, _ => true)

        val tp = typeSig(tree, annotations)

        findCyclicalLowerBound(tp) andAlso { sym =>
          if (needsCycleCheck) {
            // neg/t1224:  trait C[T] ; trait A { type T >: C[T] <: C[C[T]] }
            // To avoid an infinite loop on the above, we cannot break all cycles
            log(s"Reinitializing info of $sym to catch any genuine cycles")
            sym reset sym.info
            sym.initialize
          }
        }

        sym.setInfo(if (!sym.isJavaDefined) tp else RestrictJavaArraysMap(tp))

        validate(sym)
      }
    }

    def moduleClassTypeCompleter(tree: ModuleDef) = new ModuleClassTypeCompleter(tree)
    class ModuleClassTypeCompleter(tree: ModuleDef) extends TypeCompleterBase(tree) {
      override def completeImpl(sym: Symbol): Unit = {
        val moduleSymbol = tree.symbol
        assert(moduleSymbol.moduleClass == sym, moduleSymbol.moduleClass)
        moduleSymbol.info // sets moduleClass info as a side effect.
      }
    }

    def importTypeCompleter(tree: Import) = new ImportTypeCompleter(tree)
    class ImportTypeCompleter(imp: Import) extends TypeCompleterBase(imp) {
      override def completeImpl(sym: Symbol): Unit = {
        sym setInfo importSig(imp)
      }
    }

    import AnnotationInfo.{mkFilter => annotationFilter}

    def implicitFactoryMethodCompleter(tree: DefDef, classSym: Symbol) = new CompleterWrapper(completerOf(tree)) {
      override def complete(methSym: Symbol): Unit = {
        super.complete(methSym)
        val annotations = classSym.initialize.annotations

        methSym setAnnotations (annotations filter annotationFilter(MethodTargetClass, defaultRetention = false))
        classSym setAnnotations (annotations filter annotationFilter(ClassTargetClass, defaultRetention = true))
      }
    }

    // complete the type of a value definition (may have a method symbol, for those valdefs that never receive a field,
    // as specified by Field.noFieldFor)
    def valTypeCompleter(tree: ValDef) = new ValTypeCompleter(tree)
    class ValTypeCompleter(tree: ValDef) extends TypeCompleterBase(tree) {
      override def completeImpl(fieldOrGetterSym: Symbol): Unit = {
        val mods = tree.mods
        val isGetter = fieldOrGetterSym.isMethod
        val annots =
          if (mods.annotations.isEmpty) Nil
          else {
            // if this is really a getter, retain annots targeting either field/getter
            val pred: AnnotationInfo => Boolean =
              if (isGetter) accessorAnnotsFilter(tree.mods)
              else annotationFilter(FieldTargetClass, !mods.isParamAccessor)
            annotSig(mods.annotations, tree, pred)
          }

        // must use typeSig, not memberSig (TODO: when do we need to switch namers?)
        val sig = typeSig(tree, annots)

        fieldOrGetterSym setInfo (if (isGetter) NullaryMethodType(sig) else sig)

        checkBeanAnnot(tree, annots)

        validate(fieldOrGetterSym)
      }
    }

    // knowing `isBean`, we could derive `isSetter` from `valDef.name`
    def accessorTypeCompleter(valDef: ValDef, missingTpt: Boolean, isBean: Boolean, isSetter: Boolean) = new AccessorTypeCompleter(valDef, missingTpt, isBean, isSetter)
    class AccessorTypeCompleter(valDef: ValDef, missingTpt: Boolean, isBean: Boolean, isSetter: Boolean) extends TypeCompleterBase(valDef) {
      override def completeImpl(accessorSym: Symbol): Unit = {
        context.unit.synthetics get accessorSym match {
          case Some(ddef: DefDef) =>
            // `accessorSym` is the accessor for which we're completing the info (tree == ddef),
            // while `valDef` is the field definition that spawned the accessor
            // NOTE: `valTypeCompleter` handles abstract vals, trait vals and lazy vals, where the ValDef carries the getter's symbol

            valDef.symbol.rawInfo match {
              case c: ValTypeCompleter =>
                // If the field and accessor symbols are distinct, i.e., we're not in a trait, invoke the
                // valTypeCompleter. This ensures that field annotations are set correctly (scala/bug#10471).
                c.completeImpl(valDef.symbol)
              case _ =>
            }
            val valSig =
              if (valDef.symbol.isInitialized) valDef.symbol.info // re-use an already computed type
              else typeSig(valDef, Nil) // Don't pass any annotations to set on the valDef.symbol, just compute the type sig (TODO: dig deeper and see if we can use memberSig)

            // patch up the accessor's tree if the valdef's tpt was not known back when the tree was synthesized
            // can't look at `valDef.tpt` here because it may have been completed by now (this is why we pass in `missingTpt`)
            // HACK: a param accessor `ddef.tpt.tpe` somehow gets out of whack with `accessorSym.info`, so always patch it back...
            //       (the tpt is typed in the wrong namer, using the class as owner instead of the outer context, which is where param accessors should be typed)
            if (missingTpt || accessorSym.isParamAccessor) {
              if (!isSetter) ddef.tpt setType valSig
              else if (ddef.vparamss.nonEmpty && ddef.vparamss.head.nonEmpty) ddef.vparamss.head.head.tpt setType valSig
              else throw new TypeError(valDef.pos, s"Internal error: could not complete parameter/return type for $ddef from $accessorSym")
            }

            val mods = valDef.mods
            val annots =
              if (mods.annotations.isEmpty) Nil
              else annotSig(mods.annotations, valDef, accessorAnnotsFilter(valDef.mods, isSetter, isBean))

            // for a setter, call memberSig to attribute the parameter (for a bean, we always use the regular method sig completer since they receive method types)
            // for a regular getter, make sure it gets a NullaryMethodType (also, no need to recompute it: we already have the valSig)
            val sig =
              if (isSetter || isBean) typeSig(ddef, annots)
              else {
                if (annots.nonEmpty) annotate(accessorSym, annots)

                NullaryMethodType(valSig)
              }

            accessorSym setInfo pluginsTypeSigAccessor(sig, typer, valDef, accessorSym)

            if (!isBean && accessorSym.isOverloaded)
              if (isSetter) ddef.rhs.setType(ErrorType)
              else GetterDefinedTwiceError(accessorSym)


            validate(accessorSym)

          case _ =>
            throw new TypeError(valDef.pos, s"Internal error: no synthetic tree found for bean accessor $accessorSym")
        }
      }
    }

    private def checkBeanAnnot(tree: ValDef, annotSigs: List[AnnotationInfo]) = {
      val mods = tree.mods
      // neg/t3403: check that we didn't get a sneaky type alias/renamed import that we couldn't detect
      // because we only look at names during synthesis (in deriveBeanAccessors)
      // (TODO: can we look at symbols earlier?)
      val hasNamedBeanAnnots = (mods hasAnnotationNamed tpnme.BeanPropertyAnnot) || (mods hasAnnotationNamed tpnme.BooleanBeanPropertyAnnot)
      if (!hasNamedBeanAnnots && annotSigs.exists(ann => (ann.matches(BeanPropertyAttr)) || ann.matches(BooleanBeanPropertyAttr)))
        BeanPropertyAnnotationLimitationError(tree)
    }

    // see scala.annotation.meta's package class for more info
    // Annotations on ValDefs can be targeted towards the following: field, getter, setter, beanGetter, beanSetter, param.
    // The defaults are:
    //   - (`val`-, `var`- or plain) constructor parameter annotations end up on the parameter, not on any other entity.
    //   - val/var member annotations solely end up on the underlying field, except in traits and for all lazy vals,
    //     where there is no field, and the getter thus holds annotations targeting both getter & field.
    //     As soon as there is a field/getter (in subclasses mixing in the trait, or after expanding the lazy val during the fields phase),
    //     we triage the annotations.
    //
    // TODO: these defaults can be surprising for annotations not meant for accessors/fields -- should we revisit?
    // (In order to have `@foo val X` result in the X getter being annotated with `@foo`, foo needs to be meta-annotated with @getter)
    private def accessorAnnotsFilter(mods: Modifiers, isSetter: Boolean = false, isBean: Boolean = false): AnnotationInfo => Boolean = {
      val canTriageAnnotations = isSetter || !fields.getterTreeAnnotationsTargetFieldAndGetter(owner, mods)

      def filterAccessorAnnotations: AnnotationInfo => Boolean =
        if (canTriageAnnotations)
          annotationFilter(if (isSetter) SetterTargetClass else GetterTargetClass, defaultRetention = false)
        else (ann =>
          annotationFilter(FieldTargetClass, defaultRetention = true)(ann) ||
            annotationFilter(GetterTargetClass, defaultRetention = true)(ann))

      def filterBeanAccessorAnnotations: AnnotationInfo => Boolean =
        if (canTriageAnnotations)
          annotationFilter(if (isSetter) BeanSetterTargetClass else BeanGetterTargetClass, defaultRetention = false)
        else (ann =>
          annotationFilter(FieldTargetClass, defaultRetention = true)(ann) ||
            annotationFilter(BeanGetterTargetClass, defaultRetention = true)(ann))

      if (isBean) filterBeanAccessorAnnotations else filterAccessorAnnotations
    }

    def selfTypeCompleter(tree: Tree) = new SelfTypeCompleter(tree)
    class SelfTypeCompleter(tree: Tree) extends TypeCompleterBase(tree) {
      override def completeImpl(sym: Symbol): Unit = {
        val selftpe = typer.typedType(tree).tpe
        sym setInfo {
          if (selftpe.typeSymbol isNonBottomSubClass sym.owner) selftpe
          else intersectionType(List(sym.owner.tpe, selftpe))
        }
      }
    }

    private def refersToSymbolLessAccessibleThan(tp: Type, sym: Symbol): Boolean = {
      val accessibilityReference =
        if (sym.isValue && sym.owner.isClass && sym.isPrivate) sym.getterIn(sym.owner)
        else sym

      @tailrec def loop(tp: Type): Boolean = tp match {
        case SingleType(pre, sym) => sym.isLessAccessibleThan(accessibilityReference) || loop(pre)
        case ThisType(sym)        => sym.isLessAccessibleThan(accessibilityReference)
        case p: SimpleTypeProxy   => loop(p.underlying)
        case _ => false
      }
      loop(tp)
    }

    /*
     * This method has a big impact on the eventual compiled code.
     * At this point many values have the most specific possible
     * type (e.g. in val x = 42, x's type is Int(42), not Int) but
     * most need to be widened (which deconsts) to avoid undesirable
     * propagation of those singleton types.
     *
     * However, the compilation of pattern matches into switch
     * statements depends on constant folding, which will only take
     * place for those values which aren't deconsted.  The "final"
     * modifier is the present means of signaling that a constant
     * value should not deconsted, so it has a use even in situations
     * whether it is otherwise redundant (such as in a singleton.)
     */
    private def widenIfNecessary(sym: Symbol, tpe: Type, pt: Type): Type = {
      // Are we inferring the result type of a stable symbol, whose type doesn't refer to a hidden symbol?
      // If we refer to an inaccessible symbol, let's hope widening will result in an expressible type.
      // (A LiteralType should be widened because it's too precise for a definition's type.)
      val mayKeepSingletonType =
        tpe match {
          case ConstantType(_) | AnnotatedType(_, ConstantType(_)) => false
          case _ => sym.isStable && !refersToSymbolLessAccessibleThan(tpe, sym)
        }

      // Only final vals may be constant folded, so deconst inferred type of other members.
      @inline def keepSingleton = if (sym.isFinal) tpe else tpe.deconst

      // Only widen if the definition can't keep its inferred singleton type,
      // (Also keep singleton type if so indicated by the expected type `pt`
      //  OPT: 99.99% of the time, `pt` will be `WildcardType`).
      if (mayKeepSingletonType || (sym.isFinal && sym.isVal && !sym.isLazy) || ((pt ne WildcardType) && !(tpe.widen <:< pt)) || sym.isDefaultGetter) keepSingleton
      else tpe.widen
    }

    /** Computes the type of the body in a ValDef or DefDef, and
     *  assigns the type to the tpt's node.  Returns the type.
     *
     *  Under `-Xsource-features`, use `pt`, the type of the overridden member.
     *  But preserve the precise type of a whitebox macro.
     *  For `def f = macro g`, here we see `def f = xp(g)` the expansion,
     *  not the `isMacro` case: `openMacros` will be nonEmpty.
     *  For `def m = f`, retrieve the typed RHS and check if it is an expansion;
     *  in that case, check if the expandee `f` is whitebox and preserve
     *  the precise type if it is. The user must provide an explicit type
     *  to "opt out" of the inferred narrow type; in Scala 3, they would
     *  inline the def to "opt in".
     */
    private def assignTypeToTree(tree: ValOrDefDef, defnTyper: Typer, pt: Type): Type = {
      val rhsTpe = tree match {
        case ddef: DefDef if tree.symbol.isTermMacro => defnTyper.computeMacroDefType(ddef, pt) // unreached, see methodSig
        case _ => defnTyper.computeType(tree.rhs, pt)
      }
      tree.tpt.defineType {
        // infer from overridden symbol, contingent on Xsource; exclude constants and whitebox macros
        val inferOverridden = currentRun.isScala3 &&
          !pt.isWildcard && pt != NoType && !pt.isErroneous &&
          !(tree.isInstanceOf[ValDef] && tree.symbol.isFinal && isConstantType(rhsTpe)) &&
          openMacros.isEmpty && {
            context.unit.transformed.get(tree.rhs) match {
              case Some(t) if t.hasAttachment[MacroExpansionAttachment] =>
                val xp = macroExpandee(t)
                xp.symbol == null || isBlackbox(xp.symbol)
              case _ => true
            }
          }
        val legacy = dropIllegalStarTypes(widenIfNecessary(tree.symbol, rhsTpe, pt))
        // <:< check as a workaround for scala/bug#12968
        def warnIfInferenceChanged(): Unit = if (!(legacy =:= pt || legacy <:< pt && pt <:< legacy)) {
          val pts = pt.toString
          val leg = legacy.toString
          val help = if (pts != leg) s" instead of $leg" else ""
          val msg = s"in Scala 3 (or with -Xsource-features:infer-override), the inferred type changes to $pts$help"
          val src = tree.pos.source
          val pos = {
            val eql = src.indexWhere(_ == '=', start = tree.rhs.pos.start, step = -1)
            val declEnd = src.indexWhere(!_.isWhitespace, start = eql - 1, step = -1) + 1
            Some(declEnd).filter(_ > 0).map(src.position)
          }
          val action = pos.map(p => runReporting.codeAction("add explicit type", p.focus, s": $leg", msg)).getOrElse(Nil)
          runReporting.warning(tree.pos, msg, WarningCategory.Scala3Migration, tree.symbol, action)
        }
        if (inferOverridden && currentRun.sourceFeatures.inferOverride) pt
        else {
          if (inferOverridden) warnIfInferenceChanged()
          legacy.tap(InferredImplicitError(tree, _, context))
        }
      }.setPos(tree.pos.focus)
      tree.tpt.tpe
    }

    // owner is the class with the self type
    def enterSelf(self: ValDef): Unit = {
      val ValDef(_, name, tpt, _) = self
      if (self eq noSelfType)
        return

      val hasName = name != nme.WILDCARD
      val hasType = !tpt.isEmpty
      if (!hasType)
        tpt defineType NoType

      val sym = (
        if (hasType || hasName) {
          owner.typeOfThis = if (hasType) selfTypeCompleter(tpt) else owner.tpe_*
          val selfSym = owner.thisSym setPos self.pos
          if (hasName) selfSym setName name else selfSym
        }
        else {
          val symName = if (name != nme.WILDCARD) name else nme.this_
          owner.newThisSym(symName, owner.pos) setInfo owner.tpe
        }
      )
      self.symbol = context.scope enter sym
    }

    private def templateSig(templ: Template): Type = {
      val clazz = context.owner
      val parentTrees = typer.typedParentTypes(templ)
      val pending = mutable.ListBuffer[AbsTypeError]()
      parentTrees foreach { tpt =>
        val ptpe = tpt.tpe
        if (!ptpe.isError && !phase.erasedTypes) {
          val psym = ptpe.typeSymbol
          if (psym.isSealed) {
            val sameSourceFile = context.unit.source.file == psym.sourceFile
            val okChild =
              if (psym.isJava)
                psym.attachments.get[PermittedSubclassSymbols] match {
                  case Some(permitted) => permitted.permits.exists(_ == clazz)
                  case _ => sameSourceFile
                }
              else
                sameSourceFile
            if (okChild)
              psym.addChild(clazz)
            else
              pending += ParentSealedInheritanceError(tpt, psym)
          }
          if (psym.isLocalToBlock && psym.isClass)
            psym.addChild(clazz)
        }
      }
      pending.foreach(ErrorUtils.issueTypeError)

      val parents = {
        def checkParent(tpt: Tree): Type = if (tpt.tpe.isError) AnyRefTpe else tpt.tpe
        parentTrees map checkParent
      }

      enterSelf(templ.self)

      val decls = newScope
      val templateNamer = newNamer(context.make(templ, clazz, decls))
      templateNamer enterSyms templ.body

      // add apply and unapply methods to companion objects of case classes,
      // unless they exist already; here, "clazz" is the module class
      if (clazz.isModuleClass)
        clazz.attachments.get[ClassForCaseCompanionAttachment] foreach { cma =>
          val cdef = cma.caseClass
          assert(cdef.mods.isCase, "expected case class: "+ cdef)
          addApplyUnapply(cdef, templateNamer)
        }

      // add the copy method to case classes; this needs to be done here, not in SyntheticMethods, because
      // the namer phase must traverse this copy method to create default getters for its parameters.
      // here, clazz is the ClassSymbol of the case class (not the module). (!clazz.hasModuleFlag) excludes
      // the moduleClass symbol of the companion object when the companion is a "case object".
      if (clazz.isCaseClass && !clazz.hasModuleFlag) {
        val modClass = companionSymbolOf(clazz, context).moduleClass
        modClass.attachments.get[ClassForCaseCompanionAttachment] foreach { cma =>
          val cdef = cma.caseClass
          def hasCopy = decls.containsName(nme.copy) || parents.exists { p => val ov = p.member(nme.copy); ov.exists && !ov.isDeferred }

          // scala/bug#5956 needs (cdef.symbol == clazz): there can be multiple class symbols with the same name
          if (cdef.symbol == clazz && !hasCopy)
            addCopyMethod(cdef, templateNamer)
        }
      }

      // if default getters (for constructor defaults) need to be added to that module, here's the namer
      // to use. clazz is the ModuleClass. sourceModule works also for classes defined in methods.
      val module = clazz.sourceModule
      for (cda <- module.attachments.get[ConstructorDefaultsAttachment]) {
        debuglog(s"Storing the template namer in the ConstructorDefaultsAttachment of ${module.debugLocationString}.")
        if (cda.defaults.nonEmpty) {
          for (sym <- cda.defaults) {
            decls.enter(sym)
          }
          cda.defaults.clear()
        }
        cda.companionModuleClassNamer = templateNamer
      }

      val classTp = ClassInfoType(parents, decls, clazz)
      templateNamer.expandMacroAnnotations(templ.body)
      pluginsTypeSig(classTp, templateNamer.typer, templ, WildcardType)
    }

    private def classSig(cdef: ClassDef): Type = {
      val clazz = cdef.symbol
      val ClassDef(_, _, tparams, impl) = cdef
      val tparams0   = typer.reenterTypeParams(tparams)
      val resultType = templateSig(impl)

      val res = GenPolyType(tparams0, resultType)

      val pluginsTp = pluginsTypeSig(res, typer, cdef, WildcardType)
      cdef.getAndRemoveAttachment[PermittedSubclasses].foreach { permitted =>
        clazz.updateAttachment[PermittedSubclassSymbols] {
          PermittedSubclassSymbols(permitted.permits.map(typer.typed(_, Mode.NOmode).symbol))
        }
      }

      // Already assign the type to the class symbol (monoTypeCompleter will do it again).
      // Allows isDerivedValueClass to look at the info.
      clazz setInfo pluginsTp
      if (clazz.isDerivedValueClass) {
        log("Ensuring companion for derived value class " + cdef.name + " at " + cdef.pos.show)
        clazz setFlag FINAL
        // Don't force the owner's info lest we create cycles as in scala/bug#6357.
        enclosingNamerWithScope(clazz.owner.rawInfo.decls).ensureCompanionObject(cdef)
      }

      if (settings.YmacroAnnotations.value && treeInfo.isMacroAnnotation(cdef))
        typer.typedMacroAnnotation(cdef)

      pluginsTp
    }

    private def moduleSig(mdef: ModuleDef): Type = {
      val moduleSym = mdef.symbol
      // The info of both the module and the moduleClass symbols need to be assigned. monoTypeCompleter assigns
      // the result of typeSig to the module symbol. The module class info is assigned here as a side-effect.
      val result = templateSig(mdef.impl)
      val pluginsTp = pluginsTypeSig(result, typer, mdef, WildcardType)
      // Assign the moduleClass info (templateSig returns a ClassInfoType)
      val clazz = moduleSym.moduleClass
      clazz setInfo pluginsTp
      // clazz.tpe_* returns a `ModuleTypeRef(clazz)`, a typeRef that links to the module class `clazz`
      // (clazz.info would the ClassInfoType, which is not what should be assigned to the module symbol)
      clazz.tpe_*
    }


    // make a java method type if meth.isJavaDefined
    private def methodTypeFor(meth: Symbol, vparamSymss: List[List[Symbol]], restpe: Type) = {
      def makeMethodType(vparams: List[Symbol], restpe: Type) = {
        vparams foreach (p => p setInfo p.tpe)
        MethodType(vparams, restpe)
      }
      if (vparamSymss.isEmpty) NullaryMethodType(restpe)
      else if (meth.isJavaDefined) vparamSymss.foldRight(restpe)(makeMethodType)
      else vparamSymss.foldRight(restpe)(MethodType(_, _))
    }


    /**
     * The method type for `ddef`.
     *
     * If a PolyType(tparams, restp) is returned, `tparams` are the external symbols (not type skolems),
     * i.e. instances of AbstractTypeSymbol. All references in `restp` to the type parameters are TypeRefs
     * to these non-skolems.
     *
     * For type-checking the rhs (in case the result type is inferred), the type skolems of the type parameters
     * are entered in scope. Equally, the parameter symbols entered into scope have types which refer to those
     * skolems: when type-checking the rhs, references to parameters need to have types that refer to the skolems.
     * In summary, typing an rhs happens with respect to the skolems.
     *
     * This means that the method's result type computed by the typer refers to skolems. In order to put it
     * into the method type (the result of methodSig), typeRefs to skolems have to be replaced by references
     * to the non-skolems.
     */
    private def methodSig(ddef: DefDef): Type = {
      val DefDef(_, _, tparams, vparamss, tpt, _) = ddef

      val meth = owner
      val methOwner = meth.owner

      /* tparams already have symbols (created in enterDefDef/completerOf), namely the skolemized ones (created
       * by the PolyTypeCompleter constructor, and assigned to tparams). reenterTypeParams enters the type skolems
       * into scope and returns the non-skolems.
       */
      val tparamSyms = typer.reenterTypeParams(tparams)
      val tparamSkolems = tparams.map(_.symbol)

      /*
       * Creates a method type using tparamSyms and vparamsSymss as argument symbols and `respte` as result type.
       * All typeRefs to type skolems are replaced by references to the corresponding non-skolem type parameter,
       * so the resulting type is a valid external method type, it does not contain (references to) skolems.
       *
       * tparamSyms are deskolemized symbols  -- TODO: check that their infos don't refer to method args?
       * vparamss refer (if they do) to skolemized tparams
       */
      def deskolemizedPolySig(vparamSymss: List[List[Symbol]], restpe: Type) =
        GenPolyType(tparamSyms, methodTypeFor(meth, vparamSymss, restpe).substSym(tparamSkolems, tparamSyms))

      if (tpt.isEmpty && meth.name == nme.CONSTRUCTOR) {
        tpt defineType context.enclClass.owner.tpe_*
        tpt setPos meth.pos.focus
      }

      /* since the skolemized tparams are in scope, the TypeRefs in types of vparamSymss refer to the type skolems
       * note that for parameters with missing types, `methodSig` reassigns types of these symbols (the parameter
       * types from the overridden method).
       */
      val vparamSymss: List[List[Symbol]] = enterValueParams(vparamss)

      val resTpGiven =
        if (tpt.isEmpty) WildcardType
        else {
          val tptTyped = typer.typedType(tpt)
          context.unit.transformed(tpt) = tptTyped
          tptTyped.tpe
        }

      // ignore missing types unless we can look to overridden method to recover the missing information
      val canOverride = methOwner.isClass && !meth.isConstructor
      val inferResTp  = canOverride && tpt.isEmpty

      /*
       * Find the overridden method that matches a schematic method type,
       * which has WildcardTypes for unspecified return or parameter types.
       * For instance, in `def f[T](a: T, b) = ...`, the type schema is
       *
       *   PolyType(T, MethodType(List(a: T, b: WildcardType), WildcardType))
       *
       * where T are non-skolems.
       *
       * NOTE: mutates info of symbol of vparamss that don't specify a type
       */
      def methodSigApproxUnknownArgs(): Type =
        deskolemizedPolySig(vparamSymss, resTpGiven)

      // Must be lazy about the schema to avoid cycles in neg/t5093.scala
      def computeOverridden(immediate: Boolean) =
        if (!canOverride) NoSymbol
        else safeNextOverriddenSymbolLazySchema(meth, methodSigApproxUnknownArgs _, immediate)

      val overridden = computeOverridden(immediate = false)
      /*
       * If `meth` doesn't have an explicit return type, extract the return type from the method
       * overridden by `meth` (if there's an unique one). This type is later used as the expected
       * type for computing the type of the rhs. The resulting type references type skolems for
       * type parameters (consistent with the result of `typer.typedType(tpt).tpe`).
       *
       * If the result type is missing, assign a MethodType to `meth` that's constructed using this return type.
       * This allows omitting the result type for recursive methods.
       */
      val resTpFromOverride =
        if (!inferResTp || overridden == NoSymbol || overridden.isOverloaded) resTpGiven
        else {
          overridden.cookJavaRawInfo() // #3404 xform java rawtypes into existentials

          val (overriddenTparams, overriddenTp) =
            methOwner.thisType.memberType(overridden) match {
              case PolyType(tparams, mt) => (tparams, mt.substSym(tparams, tparamSkolems))
              case mt => (Nil, mt)
            }

          @tailrec @inline def applyFully(tp: Type, paramss: List[List[Symbol]]): Type =
            if (paramss.isEmpty) tp match {
              case NullaryMethodType(rtpe) => rtpe
              case MethodType(Nil, rtpe)   => rtpe
              case tp                      => tp
            }
            else applyFully(tp.resultType(paramss.head.map(_.tpe)), paramss.tail)

          if (inferResTp) {
            // scala/bug#7668 Substitute parameters from the parent method with those of the overriding method.
            val overriddenResTp = applyFully(overriddenTp, vparamSymss).substSym(overriddenTparams, tparamSkolems)

            // provisionally assign `meth` a method type with inherited result type
            // that way, we can leave out the result type even if method is recursive.
            // this also prevents cycles in implicit search, see comment in scala/bug#10471
            meth setInfo deskolemizedPolySig(vparamSymss, overriddenResTp)
            overriddenResTp
          } else resTpGiven
        }

      // issue an error for missing parameter types
      // (computing resTpFromOverride may have required inferring some, meanwhile)
      mforeach(vparamss) { vparam =>
        if (vparam.tpt.isEmpty) {
          MissingParameterOrValTypeError(vparam)
          vparam.tpt defineType ErrorType
        }
      }

      // If we, or the overridden method has defaults, add getters for them
      if (mexists(vparamss)(_.symbol.hasDefault) || mexists(overridden.paramss)(_.hasDefault))
        addDefaultGetters(meth, ddef, vparamss, tparams,  overridden)

      // macro defs need to be typechecked in advance
      // because @macroImpl annotation only gets assigned during typechecking
      // otherwise macro defs wouldn't be able to robustly coexist with their clients
      // because a client could be typechecked before a macro def that it uses
      if (meth.isMacro) typer.computeMacroDefType(ddef, resTpFromOverride) // note: `pt` argument ignored in `computeMacroDefType`

      if (vparamSymss.lengthCompare(0) > 0) { // OPT fast path for methods of 0-1 parameter lists
        val checkDependencies = new DependentTypeChecker(context)(this)
        checkDependencies check vparamSymss
      }

      val resTp = {
        // When return type is inferred, we don't just use resTpFromOverride -- it must be packed and widened.
        // Here, C.f has type String (unless -Xsource-features:infer-override):
        //   trait T { def f: Object }; class C extends T { def f = "" }
        // using resTpFromOverride as expected type allows for the following (C.f has type A):
        //   trait T { def f: A }; class C extends T { implicit def b2a(t: B): A = ???; def f = new B }
        val resTpComputedUnlessGiven =
          if (tpt.isEmpty) assignTypeToTree(ddef, typer, resTpFromOverride)
          else resTpGiven

        // #2382: return type of default getters are always @uncheckedVariance
        if (meth.hasDefault) resTpComputedUnlessGiven.withAnnotation(AnnotationInfo(uncheckedVarianceClass.tpe, List(), List()))
        else resTpComputedUnlessGiven
      }

      // Add a () parameter section if this overrides some method with () parameters
      val vparamSymssOrEmptyParamsFromOverride = {
        // check the first override for paren purposes
        def overridesNilary: Boolean = {
          val toCheck = if (currentRun.isScala3) computeOverridden(immediate = true) else overridden
          // must check `.info.isInstanceOf[MethodType]`, not `.isMethod`, to exclude NullaryMethodType.
          // Note that the matching MethodType of a NullaryMethodType must be nilary not nelary.
          toCheck != NoSymbol && toCheck.alternatives.exists(_.info.isInstanceOf[MethodType])
        }
        if (vparamSymss.isEmpty && overridesNilary) {
          meth.updateAttachment(NullaryOverrideAdapted)
          ListOfNil
        } else vparamSymss
      }

      val methSig = deskolemizedPolySig(vparamSymssOrEmptyParamsFromOverride, resTp)
      val unlink = methOwner.isJava && meth.isSynthetic && meth.isConstructor && methOwner.superClass == JavaRecordClass &&
        methOwner.info.decl(meth.name).alternatives.exists(c => c != meth && c.tpe.matches(methSig))
      if (unlink) {
        methOwner.info.decls.unlink(meth)
        ErrorType
      } else
        pluginsTypeSig(methSig, typer, ddef, resTpGiven)
    }

    /**
     * For every default argument, insert a method symbol computing that default
     */
    def enterDefaultGetters(meth: Symbol, @unused ddef: DefDef, vparamss: List[List[ValDef]], @unused tparams: List[TypeDef]): Unit = {
      val methOwner  = meth.owner
      val search = DefaultGetterNamerSearch(context, meth, initCompanionModule = false)
      var posCounter = 1

      mforeach(vparamss){(vparam) =>
        // true if the corresponding parameter of the base class has a default argument
        if (vparam.mods.hasDefault) {
          val name = nme.defaultGetterName(meth.name, posCounter)

          search.createAndEnter { owner: Symbol =>
            methOwner.resetFlag(INTERFACE) // there's a concrete member now
            val default = owner.newMethodSymbol(name, vparam.pos, paramFlagsToDefaultGetter(meth.flags))
            default.setPrivateWithin(meth.privateWithin)
            default.referenced = meth
            default.setInfo(ErrorType)
            if (meth.name == nme.apply && meth.hasAllFlags(CASE | SYNTHETIC)) {
              val att = meth.attachments.get[CaseApplyDefaultGetters].getOrElse({
                val a = new CaseApplyDefaultGetters()
                meth.updateAttachment(a)
                a
              })
              att.defaultGetters += default
            }
            if (default.owner.isTerm)
              saveDefaultGetter(meth, default)
            default
          }
        }
        posCounter += 1
      }
    }

    /**
     * For every default argument, insert a method computing that default
     *
     * Also adds the "override" and "defaultparam" (for inherited defaults) flags
     * Typer is too late, if an inherited default is used before the method is
     * typechecked, the corresponding param would not yet have the "defaultparam"
     * flag.
     */
    private def addDefaultGetters(meth: Symbol, ddef: DefDef, vparamss: List[List[ValDef]], @unused tparams: List[TypeDef], overridden: Symbol): Unit = {
      val DefDef(_, _, rtparams0, rvparamss0, _, _) = resetAttrs(deriveDefDef(ddef)(_ => EmptyTree).duplicate): @unchecked
      // having defs here is important to make sure that there's no sneaky tree sharing
      // in methods with multiple default parameters
      def rtparams  = rtparams0.map(_.duplicate)
      def rvparamss = rvparamss0.map(_.map(_.duplicate))
      val search    = DefaultGetterNamerSearch(context, meth, initCompanionModule = true)
      val overrides = overridden != NoSymbol && !overridden.isOverloaded
      // value parameters of the base class (whose defaults might be overridden)
      var baseParamss = (vparamss, overridden.tpe.paramss) match {
        // match empty and missing parameter list
        case (Nil, ListOfNil) => Nil
        case (ListOfNil, Nil) => ListOfNil
        case (_, paramss)     => paramss
      }
      assert(
        !overrides || vparamss.length == baseParamss.length,
        "" + meth.fullName + ", "+ overridden.fullName
      )

      var posCounter = 1

      // For each value parameter, create the getter method if it has a
      // default argument. previous denotes the parameter lists which
      // are on the left side of the current one. These get added to the
      // default getter. Example:
      //
      //   def foo(a: Int)(b: Int = a)      becomes
      //   foo$default$1(a: Int) = a
      //
      vparamss.foldLeft(Nil: List[List[ValDef]]) { (previous, vparams) =>
        assert(!overrides || vparams.length == baseParamss.head.length, ""+ meth.fullName + ", "+ overridden.fullName)
        val rvparams = rvparamss(previous.length)
        var baseParams = if (overrides) baseParamss.head else Nil
        foreach2(vparams, rvparams){ (vparam, rvparam) =>
          val sym = vparam.symbol
          // true if the corresponding parameter of the base class has a default argument
          val baseHasDefault = overrides && baseParams.head.hasDefault
          if (sym.hasDefault) {
            // Create a "default getter", i.e. a DefDef that will calculate vparam.rhs
            // for those who are going to call meth without providing an argument corresponding to vparam.
            // After the getter is created, a corresponding synthetic symbol is created and entered into the parent namer.
            //
            // In the ideal world, this DefDef would be a simple one-liner that just returns vparam.rhs,
            // but in scalac things are complicated in two different ways.
            //
            // 1) Because the underlying language is quite sophisticated, we must allow for those sophistications in our getter.
            //    Namely: a) our getter has to copy type parameters from the associated method (or the associated class
            //    if meth is a constructor), because vparam.rhs might refer to one of them, b) our getter has to copy
            //    preceding value parameter lists from the associated method, because again vparam.rhs might refer to one of them.
            //
            // 2) Because we have already assigned symbols to type and value parameters that we have to copy, we must jump through
            //    hoops in order to destroy them and allow subsequent naming create new symbols for our getter. Previously this
            //    was done in an overly brutal way akin to resetAllAttrs, but now we utilize a resetLocalAttrs-based approach.
            //    Still far from ideal, but at least enables things like run/macro-default-params that were previously impossible.

            val oflag = if (baseHasDefault) OVERRIDE else 0
            val name = nme.defaultGetterName(meth.name, posCounter)

            val defVparamss = mmap(rvparamss.take(previous.length)){ rvp =>
              copyValDef(rvp)(mods = rvp.mods &~ DEFAULTPARAM, rhs = EmptyTree)
            }
            search.addGetter(rtparams) {
              (parentNamer: Namer, defTparams: List[TypeDef]) =>
                val defTpt =
                // don't mess with tpt's of case copy default getters, because assigning something other than TypeTree()
                // will break the carefully orchestrated naming/typing logic that involves copyMethodCompleter and caseClassCopyMeth
                  if (meth.isCaseCopy) TypeTree()
                  else {
                    // If the parameter type mentions any type parameter of the method, let the compiler infer the
                    // return type of the default getter => allow "def foo[T](x: T = 1)" to compile.
                    // This is better than always using Wildcard for inferring the result type, for example in
                    //    def f(i: Int, m: Int => Int = identity _) = m(i)
                    // if we use Wildcard as expected, we get "Nothing => Nothing", and the default is not usable.
                    // TODO: this is a very brittle approach; I sincerely hope that Denys's research into hygiene
                    //       will open the doors to a much better way of doing this kind of stuff
                    val eraseAllMentionsOfTparams = new TypeTreeSubstituter(x => defTparams.exists(_.name == x))
                    eraseAllMentionsOfTparams(rvparam.tpt match {
                      // default getter for by-name params
                      case AppliedTypeTree(_, List(arg)) if sym.hasFlag(BYNAMEPARAM) => arg
                      case t => t
                    })
                  }
                val defRhs = rvparam.rhs

                val defaultTree = atPos(vparam.pos.focus) {
                  DefDef(Modifiers(paramFlagsToDefaultGetter(meth.flags), ddef.mods.privateWithin) | oflag, name, defTparams, defVparamss, defTpt, defRhs)
                }
                def referencesThis(sym: Symbol) = sym match {
                  case term: TermSymbol => term.referenced == meth
                  case _ => false
                }
                val defaultGetterSym = parentNamer.context.scope.lookup(name).filter(referencesThis)
                assert(defaultGetterSym != NoSymbol, (parentNamer.owner, name))
                defaultTree.setSymbol(defaultGetterSym)
                defaultGetterSym.setInfo(parentNamer.completerOf(defaultTree))
                defaultTree
            }
          }
          else if (baseHasDefault) {
            // the parameter does not have a default itself, but the
            // corresponding parameter in the base class does.
            sym.setFlag(DEFAULTPARAM)
          }
          posCounter += 1
          if (overrides) baseParams = baseParams.tail
        }
        if (overrides) baseParamss = baseParamss.tail
        previous :+ vparams
      }
    }

    private object DefaultGetterNamerSearch {
      def apply(c: Context, meth: Symbol, initCompanionModule: Boolean) = if (meth.isConstructor) new DefaultGetterInCompanion(c, meth, initCompanionModule)
      else new DefaultMethodInOwningScope(c, meth)
    }
    private abstract class DefaultGetterNamerSearch {
      def addGetter(rtparams0: List[TypeDef])(create: (Namer, List[TypeDef]) => Tree): Unit

      def createAndEnter(f: Symbol => Symbol): Unit
    }
    private class DefaultGetterInCompanion(@unused c: Context, meth: Symbol, initCompanionModule: Boolean) extends DefaultGetterNamerSearch {
      private val module = companionSymbolOf(meth.owner, context)
      if (initCompanionModule) module.initialize
      private val cda: Option[ConstructorDefaultsAttachment] = module.attachments.get[ConstructorDefaultsAttachment]
      private val moduleNamer = cda.flatMap(x => Option(x.companionModuleClassNamer))

      def createAndEnter(f: Symbol => Symbol): Unit = {
        val default = f(module.moduleClass)
        moduleNamer match {
          case Some(namer) =>
            namer.enterInScope(default)
          case None =>
            cda match {
              case Some(attachment) =>
                // defer entry until the companion module body it type completed
                attachment.defaults += default
              case None =>
              // ignore error to fix #3649 (prevent crash in erroneous source code)
            }
        }
      }
      def addGetter(rtparams0: List[TypeDef])(create: (Namer, List[TypeDef]) => Tree): Unit = {
        cda match {
          case Some(attachment) =>
            moduleNamer match {
              case Some(namer) =>
                val cdef = attachment.classWithDefault
                val ClassDef(_, _, rtparams, _) = resetAttrs(deriveClassDef(cdef)(_ => Template(Nil, noSelfType, Nil)).duplicate): @unchecked
                val defTparams = rtparams.map(rt => copyTypeDef(rt)(mods = rt.mods &~ (COVARIANT | CONTRAVARIANT)))
                val tree = create(namer, defTparams)
                namer.enterSyntheticSym(tree)
              case None =>
            }
          case None =>
        }

      }
    }
    private class DefaultMethodInOwningScope(@unused c: Context, meth: Symbol) extends DefaultGetterNamerSearch {
      private lazy val ownerNamer: Namer = {
        val ctx = context.nextEnclosing(c => c.scope.toList.contains(meth)) // TODO use lookup rather than toList.contains
        assert(ctx != NoContext, meth)
        newNamer(ctx)
      }
      def createAndEnter(f: Symbol => Symbol): Unit = {
        ownerNamer.enterInScope(f(ownerNamer.context.owner))
      }
      def addGetter(rtparams0: List[TypeDef])(create: (Namer, List[TypeDef]) => Tree): Unit = {
        val tree = create(ownerNamer, rtparams0)
        ownerNamer.enterSyntheticSym(tree)
      }
    }

    private def valDefSig(vdef: ValDef): Type = {
      val ValDef(_, _, tpt, rhs) = vdef
      def inferredValTpt: Type = {
        // enterGetterSetter assigns the getter's symbol to a ValDef when there's no underlying field
        // (a deferred val or most vals defined in a trait -- see Field.noFieldFor)
        val isGetter = vdef.symbol hasFlag ACCESSOR

        val pt: Type = {
          val valOwner = owner.owner
          if (!valOwner.isClass) WildcardType
          else {
            // normalize to getter so that we correctly consider a val overriding a def
            // (a val's name ends in a " ", so can't compare to def)
            val overridingSym = if (isGetter) vdef.symbol else vdef.symbol.getterIn(valOwner)

            // We're called from an accessorTypeCompleter, which is completing the info for the accessor's symbol,
            // which may or may not be `vdef.symbol` (see isGetter above)
            val overridden = safeNextOverriddenSymbol(overridingSym)

            if (overridden == NoSymbol || overridden.isOverloaded) WildcardType
            else valOwner.thisType.memberType(overridden).resultType
          }
        }

        def patchSymInfo(tp: Type): Unit =
          if (pt ne WildcardType) // no patching up to do if we didn't infer a prototype
            vdef.symbol.setInfo { if (isGetter) NullaryMethodType(tp) else tp }

        patchSymInfo(pt)

        if (vdef.hasAttachment[MultiDefAttachment.type])
          vdef.symbol.updateAttachment(MultiDefAttachment)

        // derives the val's result type from type checking its rhs under the expected type `pt`
        // vdef.tpt is mutated, and `vdef.tpt.tpe` is `assignTypeToTree`'s result
        val tptFromRhsUnderPt = assignTypeToTree(vdef, typer, pt)

        // need to re-align with assignTypeToTree, as the type we're returning from valDefSig (tptFromRhsUnderPt)
        // may actually go to the accessor, not the valdef (and if assignTypeToTree returns a subtype of `pt`,
        // we would be out of synch between field and its accessors), and thus the type completer won't
        // fix the symbol's info for us -- we set it to tmpInfo above, which may need to be improved to tptFromRhsUnderPt
        if (!isGetter) patchSymInfo(tptFromRhsUnderPt)

        tptFromRhsUnderPt
      }
      val result: Type =
        if (tpt.isEmpty) {
          if (rhs.isEmpty) { MissingParameterOrValTypeError(tpt); ErrorType }
          else inferredValTpt
        } else {
          val tptTyped = typer.typedType(tpt)
          context.unit.transformed(tpt) = tptTyped
          tptTyped.tpe
        }
//      println(s"val: $result / ${vdef.tpt.tpe} / ")
      pluginsTypeSig(result, typer, vdef, if (tpt.isEmpty) WildcardType else result)
    }

    // Pretend we're an erroneous symbol, for now, so that we match while finding the overridden symbol,
    // but are not considered during implicit search.
    // `immediate` for immediate override only, not narrowest override
    private def safeNextOverriddenSymbol(sym: Symbol, schema: Type = ErrorType, immediate: Boolean = false): Symbol = {
      val savedInfo = sym.rawInfo
      val savedFlags = sym.rawflags
      try {
        sym setInfo schema
        // pick the overridden symbol with narrowest type; dotty uses intersection
        if (!immediate && currentRun.isScala3) {
          def typeOf(s: Symbol): Type = {
            val t = if (s.isMethod) s.asMethod.returnType else s.tpe
            t.asSeenFrom(sym.owner.thisType, s.owner)
          }
          sym.allOverriddenSymbols match {
            case Nil => NoSymbol
            case overridden :: candidates =>
              candidates.foldLeft(overridden)((acc, o) => if (typeOf(o) <:< typeOf(acc)) o else acc)
          }
        }
        else
          sym.nextOverriddenSymbol
      } finally {
        sym setInfo savedInfo // setInfo resets the LOCKED flag, so restore saved flags as well
        sym.rawflags = savedFlags
      }
    }

    private def safeNextOverriddenSymbolLazySchema(sym: Symbol, schema: () => Type, immediate: Boolean): Symbol =
      safeNextOverriddenSymbol(sym, new LazyType { override def complete(sym: Symbol): Unit = sym setInfo schema() }, immediate)


    //@M! an abstract type definition (abstract type member/type parameter)
    // may take type parameters, which are in scope in its bounds
    private def typeDefSig(tdef: TypeDef) = {
      val TypeDef(_, _, tparams, rhs) = tdef
      // log("typeDefSig(" + tpsym + ", " + tparams + ")")
      val tparamSyms = typer.reenterTypeParams(tparams) //@M make tparams available in scope (just for this abstypedef)
      val tp = typer.typedType(rhs).tpe match {
        case TypeBounds(lt, rt) if lt.isError || rt.isError  => TypeBounds.empty
        case TypeBounds(lt, rt) if tdef.symbol.hasFlag(JAVA) => TypeBounds(lt, rt)
        case tp => tp
      }
      // see neg/bug1275, #3419
      // used to do a rudimentary kind check here to ensure overriding in refinements
      // doesn't change a type member's arity (number of type parameters), e.g.
      //
      //    trait T { type X[A] }
      //    type S = T { type X }
      //    val x: S
      //
      // X in x.X[A] will get rebound to the X in the refinement, which
      // does not take any type parameters. This mismatch does not crash
      // the compiler (anymore), but leads to weird type errors, as
      // x.X[A] will become NoType internally. It's not obvious the
      // error refers to the X in the refinement and not the original X.
      //
      // However, separate compilation requires the symbol info to be
      // loaded to do this check, but loading the info will probably
      // lead to spurious cyclic errors.  So omit the check.
      val res = GenPolyType(tparamSyms, tp)
      pluginsTypeSig(res, typer, tdef, WildcardType)
    }

    private def importSig(imp: Import) = {
      val Import(expr, selectors) = imp
      val expr1 = typer.typedQualifier(expr)

      if (expr1.isErrorTyped)
        ErrorType
      else {
        expr1 match {
          case This(_) =>
            // scala/bug#8207 okay, typedIdent expands Ident(self) to C.this which doesn't satisfy the next case
            // TODO should we change `typedIdent` not to expand to the `Ident` to a `This`?
          case _ if treeInfo.isStableIdentifierPattern(expr1) =>
          case _ =>
            typer.TyperErrorGen.UnstableTreeError(expr1)
        }

        val newImport = treeCopy.Import(imp, expr1, selectors)
        checkSelectors(newImport)
        context.unit.transformed(imp) = newImport
        registerImport(context, newImport)
        // copy symbol and type attributes back into old expression
        // so that the structure builder will find it.
        expr setSymbol expr1.symbol setType expr1.tpe
        ImportType(expr1)
      }
    }

    /** Given a case class
     *   case class C[Ts] (ps: Us)
     *  Add the following methods to toScope:
     *  1. if case class is not abstract, add
     *   <synthetic> <case> def apply[Ts](ps: Us): C[Ts] = new C[Ts](ps)
     *  2. add a method
     *   <synthetic> <case> def unapply[Ts](x: C[Ts]) = <ret-val>
     *  where <ret-val> is the caseClassUnapplyReturnValue of class C (see UnApplies.scala)
     *
     * @param cdef is the class definition of the case class
     * @param namer is the namer of the module class (the comp. obj)
     */
    def addApplyUnapply(cdef: ClassDef, namer: Namer): Unit = {
      if (!cdef.symbol.hasAbstractFlag)
        namer.enterSyntheticSym(caseModuleApplyMeth(cdef))

      val primaryConstructorArity = treeInfo.firstConstructorArgs(cdef.impl.body).size
      if (primaryConstructorArity <= MaxTupleArity)
        namer.enterSyntheticSym(caseModuleUnapplyMeth(cdef))
    }

    def addCopyMethod(cdef: ClassDef, namer: Namer): Unit = {
      caseClassCopyMeth(cdef) foreach namer.enterSyntheticSym
    }

    /**
     * TypeSig is invoked by monoTypeCompleters. It returns the type of a definition which
     * is then assigned to the corresponding symbol (typeSig itself does not need to assign
     * the type to the symbol, but it can if necessary).
     */
    def typeSig(tree: Tree, annotSigs: List[AnnotationInfo]): Type = {
      if (annotSigs.nonEmpty) annotate(tree.symbol, annotSigs)

      try tree match {
        case member: MemberDef => createNamer(tree).memberSig(member)
        case imp: Import       => importSig(imp)
        case x                 => throw new MatchError(x)
      } catch typeErrorHandler(tree, ErrorType)
    }

    /* For definitions, transform Annotation trees to AnnotationInfos, assign
     * them to the sym's annotations. Type annotations: see Typer.typedAnnotated
     * We have to parse definition annotations here (not in the typer when traversing
     * the MemberDef tree): the typer looks at annotations of certain symbols; if
     * they were added only in typer, depending on the compilation order, they may
     * or may not be visible.
     */
    def annotSig(annotations: List[Tree], annotee: Tree, pred: AnnotationInfo => Boolean): List[AnnotationInfo] =
      annotations.filterNot(_ eq null).map { ann =>
        val ctx = typer.context
        // need to be lazy, #1782. enteringTyper to allow inferView in annotation args, scala/bug#5892.
        def computeInfo: AnnotationInfo = enteringTyper {
          val annotSig = newTyper(ctx.makeNonSilent(ann)).typedAnnotation(ann, Some(annotee))
          if (pred(annotSig)) annotSig else UnmappableAnnotation // UnmappableAnnotation will be dropped in typedValDef and typedDefDef
        }
        ann match {
          case treeInfo.Applied(Select(New(tpt), _), _, _) =>
            // We can defer typechecking the arguments of annotations. This is important to avoid cycles in
            // checking `hasAnnotation(UncheckedStable)` during typechecking.
            def computeSymbol = enteringTyper {
              val tptCopy = tpt.duplicate
              val silentTyper  = newTyper(ctx.makeSilent(newtree = tptCopy))
              // Discard errors here, we'll report them in `computeInfo`.
              val tpt1 = silentTyper.typedTypeConstructor(tptCopy)
              tpt1.tpe.finalResultType.typeSymbol
            }
            AnnotationInfo.lazily(computeSymbol, computeInfo)
          case _ =>
            AnnotationInfo.lazily(computeInfo)
        }
      }

    private def annotate(sym: Symbol, annotSigs: List[AnnotationInfo]): Unit = {
      sym setAnnotations annotSigs

      // TODO: meta-annotations to indicate where module annotations should go (module vs moduleClass)
      if (sym.isModule) sym.moduleClass setAnnotations annotSigs
      else if (sym.isTypeSkolem) sym.deSkolemize setAnnotations annotSigs
    }

    // TODO OPT: move to method on MemberDef?
    private def memberSig(member: MemberDef) =
      member match {
        case ddef: DefDef    => methodSig(ddef)
        case vdef: ValDef    => valDefSig(vdef)
        case tdef: TypeDef   => typeDefSig(tdef)
        case cdef: ClassDef  => classSig(cdef)
        case mdef: ModuleDef => moduleSig(mdef)
        case x: PackageDef   => throw new MatchError(x) // skip PackageDef
      }

    def includeParent(tpe: Type, parent: Symbol): Type = tpe match {
      case PolyType(tparams, restpe) =>
        PolyType(tparams, includeParent(restpe, parent))
      case ClassInfoType(parents, decls, clazz) =>
        if (parents exists (_.typeSymbol == parent)) tpe
        else ClassInfoType(parents :+ parent.tpe, decls, clazz)
      case _ =>
        tpe
    }

    /** Convert Java generic array type T[] to (T with Object)[]
     *  (this is necessary because such arrays have a representation which is incompatible
     *   with arrays of primitive types.)
     *
     *  @note the comparison to Object only works for abstract types bounded by classes that are strict subclasses of Object
     *  if the bound is exactly Object, it will have been converted to Any, and the comparison will fail
     *
     *  see also sigToType
     */
    private object RestrictJavaArraysMap extends TypeMap {
      def apply(tp: Type): Type = tp match {
        case TypeRef(pre, ArrayClass, List(elemtp))
        if elemtp.typeSymbol.isAbstractType && !(elemtp <:< ObjectTpe) =>
          TypeRef(pre, ArrayClass, List(intersectionType(List(elemtp, ObjectTpe))))
        case _ =>
          mapOver(tp)
      }
    }

    /** Check that symbol's definition is well-formed. This means:
     *   - no conflicting modifiers
     *   - `abstract` modifier only for classes
     *   - `override` modifier never for classes
     *   - `def` modifier never for parameters of case classes
     *   - declarations only in mixins or abstract classes (when not @native)
     */
    def validate(sym: Symbol): Unit = {
      import SymValidateErrors._
      def fail(kind: SymValidateErrors.Value) = SymbolValidationError(sym, kind)

      def checkNoConflict(flag1: Long, flag2: Long) = {
        if (sym hasAllFlags flag1 | flag2)
          IllegalModifierCombination(sym, flag1, flag2)
      }
      if (sym.isImplicit) {
        if (sym.isConstructor)
          fail(ImplicitConstr)
        if (!(sym.isTerm || (sym.isClass && !sym.isTrait)))
          fail(ImplicitNotTermOrClass)
        if (sym.isTopLevel)
          fail(ImplicitAtToplevel)
      }
      if (sym.isClass) {
        checkNoConflict(IMPLICIT, CASE)
        if (sym.isAnyOverride && !sym.hasFlag(TRAIT))
          fail(OverrideClass)
      } else {
        if (sym.isSealed)
          fail(SealedNonClass)
        if (sym.hasFlag(ABSTRACT))
          fail(AbstractNonClass)
      }

      if (sym.isConstructor && sym.isAnyOverride)
        fail(OverrideConstr)
      if (sym.isAbstractOverride) {
          if (!sym.owner.isTrait)
            fail(AbstractOverride)
          if(sym.isType)
            fail(AbstractOverrideOnTypeMember)
      }
      if (sym.isLazy && sym.hasFlag(PRESUPER))
        fail(LazyAndEarlyInit)
      if (sym.info.typeSymbol == FunctionClass(0) && sym.isValueParameter && sym.owner.isCaseClass)
        fail(ByNameParameter)
      if (sym.isTrait && sym.isFinal && !sym.isSubClass(AnyValClass))
        checkNoConflict(ABSTRACT, FINAL)

      if (sym.isDeferred) {
        def checkWithDeferred(flag: Long) = {
          if (sym hasFlag flag)
            AbstractMemberWithModiferError(sym, flag)
        }
        // Is this symbol type always allowed the deferred flag?
        def symbolAllowsDeferred = (
             sym.isValueParameter
          || sym.isTypeParameterOrSkolem
          || (sym.isAbstractType && sym.owner.isClass)
          || context.tree.isInstanceOf[ExistentialTypeTree]
        )
        // Does the symbol owner require no undefined members?
        def ownerRequiresConcrete = (
             !sym.owner.isClass
          ||  sym.owner.isModuleClass
          ||  sym.owner.isAnonymousClass
        )
        if (sym hasAnnotation NativeAttr)
          sym resetFlag DEFERRED
        else {
          if (!symbolAllowsDeferred && ownerRequiresConcrete) fail(AbstractVar)

          checkWithDeferred(PRIVATE)
          checkWithDeferred(FINAL)
        }
      }

      if (!sym.isJavaEnum)
        checkNoConflict(FINAL, SEALED)
      checkNoConflict(PRIVATE, PROTECTED)
      // checkNoConflict(PRIVATE, OVERRIDE) // this one leads to bad error messages like #4174, so catch in refchecks
      // checkNoConflict(PRIVATE, FINAL)    // can't do this because FINAL also means compile-time constant
      // checkNoConflict(ABSTRACT, FINAL)   // this one gives a bad error for non-@inline classes which extend AnyVal
      // @PP: I added this as a check because these flags are supposed to be converted to ABSOVERRIDE before arriving here.
      checkNoConflict(ABSTRACT, OVERRIDE)
    }
  }

  abstract class TypeCompleter extends LazyType {
    def tree: Tree
    override def forceDirectSuperclasses(): Unit =
      tree.foreach {
        case dt: DefTree => global.withPropagateCyclicReferences(Option(dt.symbol).map(_.maybeInitialize))
        case _ =>
      }
  }

  @deprecated("Instantiate TypeCompleterBase (for monomorphic, non-wrapping completer) or CompleterWrapper directly.", "2.12.2")
  def mkTypeCompleter(t: Tree)(c: Symbol => Unit) = new TypeCompleterBase(t) {
    def completeImpl(sym: Symbol) = c(sym)
  }

  // NOTE: only meant for monomorphic definitions,
  // do not use to wrap existing completers (see CompleterWrapper for that)
  abstract class TypeCompleterBase[T <: Tree](val tree: T) extends LockingTypeCompleter with FlagAgnosticCompleter

  trait LockingTypeCompleter extends TypeCompleter {
    def completeImpl(sym: Symbol): Unit

    override def complete(sym: Symbol) = {
      lockedCount += 1
      try completeImpl(sym)
      finally lockedCount -= 1
    }
  }

  /**
   * A class representing a lazy type with known type parameters. `ctx` is the namer context in which the
   * `owner` is defined.
   *
   * Constructing a PolyTypeCompleter for a DefDef creates type skolems for the type parameters and
   * assigns them to the `tparams` trees.
   */
  class PolyTypeCompleter(tparams: List[TypeDef], restp: TypeCompleter, ctx: Context) extends LockingTypeCompleter with FlagAgnosticCompleter {
    // @M. If `owner` is an abstract type member, `typeParams` are all NoSymbol (see comment in `completerOf`),
    // otherwise, the non-skolemized (external) type parameter symbols
    override val typeParams = tparams map (_.symbol)

    /* The definition tree (poly ClassDef, poly DefDef or HK TypeDef) */
    override val tree = restp.tree

    private val defnSym = tree.symbol

    if (defnSym.isTerm) {
      // for polymorphic DefDefs, create type skolems and assign them to the tparam trees.
      val skolems = deriveFreshSkolems(typeParams)
      foreach2(tparams, skolems)(_ setSymbol _)
    }

    def completeImpl(sym: Symbol) = {
      // @M an abstract type's type parameters are entered.
      // TODO: change to isTypeMember ?
      if (defnSym.isAbstractType)
        newNamer(ctx.makeNewScope(tree, tree.symbol)) enterSyms tparams //@M
      restp complete sym
    }
  }

  /**
    * Wrap an existing completer to do some post/pre-processing of the completed type.
    *
    * @param completer
    */
  class CompleterWrapper(completer: TypeCompleter) extends TypeCompleter {
    // override important when completer.isInstanceOf[PolyTypeCompleter]!
    override val typeParams = completer.typeParams

    val tree = completer.tree

    override def complete(sym: Symbol): Unit = {
      completer.complete(sym)
    }
  }

  // Can we relax these restrictions? For motivation, see
  //    test/files/pos/depmet_implicit_oopsla_session_2.scala
  //    neg/depmet_try_implicit.scala
  //
  // We should allow forward references since type selections on
  // implicit args are like type parameters.
  //    def foo[T](a: T, x: w.T2)(implicit w: ComputeT2[T])
  // is more compact than:
  //    def foo[T, T2](a: T, x: T2)(implicit w: ComputeT2[T, T2])
  // moreover, the latter is not an encoding of the former, which hides type
  // inference of T2, so you can specify T while T2 is purely computed
  private class DependentTypeChecker(ctx: Context)(namer: Namer) extends TypeTraverser {
    private[this] val okParams = mutable.Set[Symbol]()
    private[this] val method   = ctx.owner

    def traverse(tp: Type) = tp match {
      case SingleType(_, sym) =>
        if (sym.owner == method && sym.isValueParameter && !okParams(sym))
          namer.NamerErrorGen.IllegalDependentMethTpeError(sym)(ctx)

      case _ => mapOver(tp)
    }
    def check(vparamss: List[List[Symbol]]): Unit = {
      for (vps <- vparamss) {
        for (p <- vps)
          this(p.info)
        // can only refer to symbols in earlier parameter sections
        okParams ++= vps
      }
    }
  }

  /** The companion class or companion module of `original`.
   *  Calling .companionModule does not work for classes defined inside methods.
   *
   *  !!! Then why don't we fix companionModule? Does the presence of these
   *  methods imply all the places in the compiler calling sym.companionModule are
   *  bugs waiting to be reported? If not, why not? When exactly do we need to
   *  call this method?
   */
  def companionSymbolOf(original: Symbol, ctx: Context): Symbol = if (original == NoSymbol) NoSymbol else {
    val owner = original.owner
    // scala/bug#7264 Force the info of owners from previous compilation runs.
    //         Doing this generally would trigger cycles; that's what we also
    //         use the lower-level scan through the current Context as a fall back.
    if (!currentRun.compiles(owner)) owner.initialize

    if (original.isModuleClass) original.sourceModule
    else if (!owner.isTerm && owner.hasCompleteInfo)
      original.companionSymbol
    else
      ctx.lookupCompanionInIncompleteOwner(original)
  }

  /** A version of `Symbol#linkedClassOfClass` that works with local companions, ala `companionSymbolOf`. */
  final def linkedClassOfClassOf(original: Symbol, ctx: Context): Symbol =
    if (original.isModuleClass)
      companionSymbolOf(original.sourceModule, ctx)
    else
      companionSymbolOf(original, ctx).moduleClass
}
