package io.shiftleft.py2cpg

import io.shiftleft.codepropertygraph.generated.nodes
import io.shiftleft.codepropertygraph.generated.nodes.NewNode

object MethodParameters {
  def empty(): MethodParameters = {
    new MethodParameters(0, Nil)
  }
}
case class MethodParameters(
    posStartIndex: Int,
    positionalParams: Iterable[nodes.NewMethodParameterIn]
)

import io.shiftleft.codepropertygraph.generated.{
  ControlStructureTypes,
  DispatchTypes,
  ModifierTypes,
  Operators,
  nodes
}
import io.shiftleft.passes.DiffGraph
import io.shiftleft.py2cpg.memop.{
  AstNodeToMemoryOperationMap,
  Load,
  MemoryOperationCalculator,
  Store
}
import io.shiftleft.pythonparser.ast

import scala.collection.mutable

class PythonAstVisitor(fileName: String) extends PythonAstVisitorHelpers {

  private val diffGraph = new DiffGraph.Builder()
  protected val nodeBuilder = new NodeBuilder(diffGraph)
  protected val edgeBuilder = new EdgeBuilder(diffGraph)

  protected val contextStack = new ContextStack()

  private var memOpMap: AstNodeToMemoryOperationMap = _

  // As key only ast.FunctionDef and ast.AsyncFunctionDef are used but there
  // is no more specific type than ast.istmt.
  private val functionDefToMethod = mutable.Map.empty[ast.istmt, nodes.NewMethod]

  def getDiffGraph: DiffGraph = {
    diffGraph.build()
  }

  private def createIdentifierLinks(): Unit = {
    contextStack.createIdentifierLinks(
      nodeBuilder.localNode,
      nodeBuilder.closureBindingNode,
      edgeBuilder.astEdge,
      edgeBuilder.refEdge,
      edgeBuilder.captureEdge
    )
  }

  def convert(astNode: ast.iast): NewNode = {
    astNode match {
      case module: ast.Module => convert(module)
    }
  }

  def convert(mod: ast.imod): NewNode = {
    mod match {
      case node: ast.Module => convert(node)
    }
  }

  // Entry method for the visitor.
  def convert(module: ast.Module): NewNode = {
    val memOpCalculator = new MemoryOperationCalculator()
    module.accept(memOpCalculator)
    memOpMap = memOpCalculator.astNodeToMemOp

    val fileNode = nodeBuilder.fileNode(fileName)
    val namespaceBlockNode = nodeBuilder.namespaceBlockNode(fileName)
    edgeBuilder.astEdge(namespaceBlockNode, fileNode, 1)
    contextStack.setFileNamespaceBlock(namespaceBlockNode)

    val methodFullName = calculateFullNameFromContext("<module>")

    val moduleMethodNode =
      createMethod(
        "<module>",
        methodFullName,
        parameterProvider = () => MethodParameters.empty(),
        bodyProvider = () => module.stmts.map(convert),
        decoratorList = Nil,
        returns = None,
        isAsync = false,
        methodRefNode = None,
        returnTypeHint = None,
        LineAndColumn(1, 1)
      )

    createIdentifierLinks()

    moduleMethodNode
  }

  private def unhandled(node: ast.iast with ast.iattributes): NewNode = {
    val unhandledAsUnknown = true
    if (unhandledAsUnknown) {
      nodeBuilder.unknownNode(node.toString, node.getClass.getName, lineAndColOf(node))
    } else {
      throw new NotImplementedError()
    }
  }

  def convert(stmt: ast.istmt): NewNode = {
    stmt match {
      case node: ast.FunctionDef      => convert(node)
      case node: ast.AsyncFunctionDef => convert(node)
      case node: ast.ClassDef         => convert(node)
      case node: ast.Return           => convert(node)
      case node: ast.Delete           => convert(node)
      case node: ast.Assign           => convert(node)
      case node: ast.AnnAssign        => convert(node)
      case node: ast.AugAssign        => convert(node)
      case node: ast.For              => convert(node)
      case node: ast.AsyncFor         => convert(node)
      case node: ast.While            => convert(node)
      case node: ast.If               => convert(node)
      case node: ast.With             => unhandled(node)
      case node: ast.AsyncWith        => unhandled(node)
      case node: ast.Raise            => unhandled(node)
      case node: ast.Try              => convert(node)
      case node: ast.Assert           => convert(node)
      case node: ast.Import           => convert(node)
      case node: ast.ImportFrom       => convert(node)
      case node: ast.Global           => convert(node)
      case node: ast.Nonlocal         => convert(node)
      case node: ast.Expr             => convert(node)
      case node: ast.Pass             => convert(node)
      case node: ast.Break            => convert(node)
      case node: ast.Continue         => convert(node)
      case node: ast.RaiseP2          => unhandled(node)
      case node: ast.ErrorStatement   => convert(node)
    }
  }

  def convert(functionDef: ast.FunctionDef): NewNode = {
    val methodIdentifierNode =
      createIdentifierNode(functionDef.name, Store, lineAndColOf(functionDef))
    val (methodNode, methodRefNode) = createMethodAndMethodRef(
      functionDef.name,
      createParameterProcessingFunction(
        functionDef.args,
        isStaticMethod(functionDef.decorator_list)
      ),
      () => functionDef.body.map(convert),
      functionDef.decorator_list,
      functionDef.returns,
      isAsync = false,
      lineAndColOf(functionDef)
    )
    functionDefToMethod.put(functionDef, methodNode)
    createAssignment(methodIdentifierNode, methodRefNode, lineAndColOf(functionDef))
  }

  def convert(functionDef: ast.AsyncFunctionDef): NewNode = {
    val methodIdentifierNode =
      createIdentifierNode(functionDef.name, Store, lineAndColOf(functionDef))
    val (methodNode, methodRefNode) = createMethodAndMethodRef(
      functionDef.name,
      createParameterProcessingFunction(
        functionDef.args,
        isStaticMethod(functionDef.decorator_list)
      ),
      () => functionDef.body.map(convert),
      functionDef.decorator_list,
      functionDef.returns,
      isAsync = true,
      lineAndColOf(functionDef)
    )
    functionDefToMethod.put(functionDef, methodNode)
    createAssignment(methodIdentifierNode, methodRefNode, lineAndColOf(functionDef))
  }

  private def isStaticMethod(decoratorList: Iterable[ast.iexpr]): Boolean = {
    decoratorList.exists {
      case name: ast.Name if name.id == "staticmethod" => true
      case _                                           => false
    }
  }

  private def createParameterProcessingFunction(
      parameters: ast.Arguments,
      isStatic: Boolean
  ): () => MethodParameters = {
    val startIndex = if (contextStack.isClassContext && !isStatic) 0 else 1

    // TODO implement non position arguments and vararg.
    () =>
      new MethodParameters(
        startIndex,
        parameters.posonlyargs.map(convert) ++ parameters.args.map(convert)
      )
  }

  // TODO handle decoratorList
  // TODO handle returns
  private def createMethodAndMethodRef(
      methodName: String,
      parameterProvider: () => MethodParameters,
      bodyProvider: () => Iterable[nodes.NewNode],
      decoratorList: Iterable[ast.iexpr],
      returns: Option[ast.iexpr],
      isAsync: Boolean,
      lineAndColumn: LineAndColumn
  ): (nodes.NewMethod, nodes.NewMethodRef) = {
    val methodFullName = calculateFullNameFromContext(methodName)

    val methodRefNode =
      nodeBuilder.methodRefNode("def " + methodName + "(...)", methodFullName, lineAndColumn)

    val methodNode =
      createMethod(
        methodName,
        methodFullName,
        parameterProvider,
        bodyProvider,
        decoratorList,
        returns,
        isAsync = true,
        Some(methodRefNode),
        returnTypeHint = None,
        lineAndColumn
      )

    (methodNode, methodRefNode)
  }

  // It is important that the nodes returned by all provider function are created
  // during the function invocation and not in advance. Because only
  // than the context information is correct.
  private def createMethod(
      name: String,
      fullName: String,
      parameterProvider: () => MethodParameters,
      bodyProvider: () => Iterable[nodes.NewNode],
      decoratorList: Iterable[ast.iexpr],
      returns: Option[ast.iexpr],
      isAsync: Boolean,
      methodRefNode: Option[nodes.NewMethodRef],
      returnTypeHint: Option[String],
      lineAndColumn: LineAndColumn
  ): nodes.NewMethod = {
    val methodNode = nodeBuilder.methodNode(name, fullName, fileName, lineAndColumn)
    edgeBuilder.astEdge(methodNode, contextStack.astParent, contextStack.order.getAndInc)

    val blockNode = nodeBuilder.blockNode("", lineAndColumn)
    edgeBuilder.astEdge(blockNode, methodNode, 1)

    contextStack.pushMethod(name, methodNode, blockNode, methodRefNode)

    val virtualModifierNode = nodeBuilder.modifierNode(ModifierTypes.VIRTUAL)
    edgeBuilder.astEdge(virtualModifierNode, methodNode, 0)

    val methodParameter = parameterProvider()
    val parameterOrder = new AutoIncIndex(methodParameter.posStartIndex)

    methodParameter.positionalParams.foreach { parameterNode =>
      contextStack.addParameter(parameterNode)
      edgeBuilder.astEdge(parameterNode, methodNode, parameterOrder.getAndInc)
    }

    val methodReturnNode = nodeBuilder.methodReturnNode(returnTypeHint, lineAndColumn)
    edgeBuilder.astEdge(methodReturnNode, methodNode, 2)

    val bodyOrder = new AutoIncIndex(1)
    bodyProvider().foreach { bodyStmt =>
      edgeBuilder.astEdge(bodyStmt, blockNode, bodyOrder.getAndInc)
    }

    contextStack.pop()

    // For every method we create a corresponding TYPE and TYPE_DECL and
    // a binding for the method into TYPE_DECL.
    val typeNode = nodeBuilder.typeNode(name, fullName)
    val typeDeclNode = nodeBuilder.typeDeclNode(name, fullName, fileName)
    edgeBuilder.astEdge(typeDeclNode, contextStack.astParent, contextStack.order.getAndInc)
    createBinding(methodNode, typeDeclNode)

    methodNode
  }

  // For a classDef we do:
  // 1. Create a metaType, metaTypeDecl and metaTypeRef.
  // 2. Create a function containing the code of the classDef body.
  // 3. Create a block which contains a call to the body function
  //    and an assignment of the metaTypeRef to an identifier with the class name.
  // 4. Create type and typeDecl for the instance class.
  // 5. Create and link members in metaTypeDecl and instanceTypeDecl
  def convert(classDef: ast.ClassDef): NewNode = {
    // Create type for the meta class object
    val metaTypeDeclName = classDef.name + "<meta>"
    val metaTypeDeclFullName = calculateFullNameFromContext(metaTypeDeclName)

    val metaTypeNode = nodeBuilder.typeNode(metaTypeDeclName, metaTypeDeclFullName)
    val metaTypeDeclNode =
      nodeBuilder.typeDeclNode(metaTypeDeclName, metaTypeDeclFullName, fileName)
    edgeBuilder.astEdge(metaTypeDeclNode, contextStack.astParent, contextStack.order.getAndInc)

    // Create <body> function which contains the code defining the class
    contextStack.pushClass(classDef.name, metaTypeDeclNode)
    val classBodyFunctionName = classDef.name + "<body>"
    val (_, methodRefNode) = createMethodAndMethodRef(
      classBodyFunctionName,
      parameterProvider = () => MethodParameters.empty(),
      bodyProvider = () => classDef.body.map(convert),
      Iterable.empty,
      None,
      isAsync = false,
      lineAndColOf(classDef)
    )

    // Create call to <body> function and assignment of the meta class object to a identifier named
    // like the class.
    val callToClassBodyFunction = createCall(methodRefNode, lineAndColOf(classDef))
    val metaTypeRefNode =
      createTypeRef(metaTypeDeclName, metaTypeDeclFullName, lineAndColOf(classDef))
    val classIdentifierAssignNode =
      createAssignmentToIdentifier(classDef.name, metaTypeRefNode, lineAndColOf(classDef))

    val classBlock = createBlock(
      callToClassBodyFunction :: classIdentifierAssignNode :: Nil,
      lineAndColOf(classDef)
    )

    // Create type for class instances
    val instanceTypeDeclName = classDef.name
    val instanceTypeDeclFullName = calculateFullNameFromContext(instanceTypeDeclName)

    val instanceType = nodeBuilder.typeNode(instanceTypeDeclName, instanceTypeDeclFullName)
    val instanceTypeDecl =
      nodeBuilder.typeDeclNode(instanceTypeDeclName, instanceTypeDeclFullName, fileName)
    edgeBuilder.astEdge(instanceTypeDecl, contextStack.astParent, contextStack.order.getAndInc)

    // Create meta class call handling method and bind it to meta class type.
    val functions = classDef.body.collect { case func: ast.FunctionDef => func }

    // __init__ method has to be in functions because "async def __init__" is invalid.
    val initFunctionOption = functions.find(_.name == "__init__")

    val positionalInitParamCount =
      initFunctionOption
        .map(initFunc => initFunc.args.posonlyargs.size + initFunc.args.args.size)
        .getOrElse(0)

    val metaClassCallHandlerMethod = createMetaClassCallHandlerMethod(
      positionalInitParamCount,
      metaTypeDeclName,
      metaTypeDeclFullName,
      instanceTypeDeclFullName,
      lineAndColOf(classDef)
    )

    createBinding(metaClassCallHandlerMethod, metaTypeDeclNode)

    // Create fake __new__ regardless whether there is an actual implementation in the code.
    // We do this to model the __init__ call in a visible way for the data flow tracker.
    // This is done because very often the __init__ call is hidden in a super().__new__ call
    // and we cant yet handle super().
    val fakeNewMethod = createFakeNewMethod(
      positionalInitParamCount,
      lineAndColOf(classDef)
    )

    val fakeNewMember = nodeBuilder.memberNode("<fakeNew>", fakeNewMethod.fullName)
    edgeBuilder.astEdge(fakeNewMember, metaTypeDeclNode, contextStack.order.getAndInc)

    // Create binding into class instance type for each method.
    // Also create bindings into meta class type to enable calls like "MyClass.func(obj, p1)".
    // For non static methods we create an adapter method which basically only shifts the parameters
    // one to the left and makes sure that the meta class object is not passed to func as instance
    // parameter.
    classDef.body.foreach {
      case func: ast.FunctionDef =>
        createMemberBindingsAndAdapter(
          func,
          func.name,
          func.args,
          func.decorator_list,
          instanceTypeDecl,
          metaTypeDeclNode
        )
      case func: ast.AsyncFunctionDef =>
        createMemberBindingsAndAdapter(
          func,
          func.name,
          func.args,
          func.decorator_list,
          instanceTypeDecl,
          metaTypeDeclNode
        )
    }

    contextStack.pop()

    classBlock
  }

  private def createMemberBindingsAndAdapter(
      function: ast.istmt,
      functionName: String,
      functionArgs: ast.Arguments,
      functionDecoratorList: Iterable[ast.iexpr],
      instanceTypeDecl: nodes.NewNode,
      metaTypeDecl: nodes.NewNode
  ): Unit = {
    val memberForInstance =
      nodeBuilder.memberNode(functionName, functionDefToMethod.apply(function).fullName)
    edgeBuilder.astEdge(memberForInstance, instanceTypeDecl, contextStack.order.getAndInc)

    val methodForMetaClass =
      if (isStaticMethod(functionDecoratorList)) {
        functionDefToMethod.apply(function)
      } else {
        createMetaClassAdapterMethod(
          functionName,
          functionDefToMethod.apply(function).fullName,
          functionArgs,
          lineAndColOf(function)
        )
      }

    val memberForMeta = nodeBuilder.memberNode(functionName, methodForMetaClass.fullName)
    edgeBuilder.astEdge(memberForMeta, metaTypeDecl, contextStack.order.getAndInc)
  }

  /** Creates an adapter method which adapts the meta class version of a method to the instance
    * class version.
    * Consider class:
    * class MyClass():
    *   def func(self, p1):
    *     pass
    *
    * The syntax to call func via the meta class is: MyClass.func(someInstance, p1), whereas the
    * call via the instance itself is: someInstance.func(p1).
    * To adapt between those two we generate:
    * def func<metaClassAdapter>(cls, self, p1):
    *   return STATIC_CALL(MyClass.func(self, p1))
    * @return
    */
  private def createMetaClassAdapterMethod(
      adaptedMethodName: String,
      adaptedMethodFullName: String,
      functionArguments: ast.Arguments,
      lineAndColumn: LineAndColumn
  ): nodes.NewMethod = {
    val adapterMethodName = adaptedMethodName + "<metaClassAdapter>"
    val adapterMethodFullName = calculateFullNameFromContext(adapterMethodName)

    createMethod(
      adapterMethodName,
      adapterMethodFullName,
      parameterProvider = () => {
        MethodParameters(
          0,
          nodeBuilder.methodParameterNode("cls", lineAndColumn) :: Nil ++
            functionArguments.posonlyargs.map(convert) ++ functionArguments.args.map(convert)
        )
      },
      bodyProvider = () => {
        val arguments = mutable.ArrayBuffer.empty[nodes.NewIdentifier]
        functionArguments.posonlyargs.foreach { arg =>
          arguments.append(createIdentifierNode(arg.arg, Load, lineAndColumn))
        }
        functionArguments.args.map { arg =>
          arguments.append(createIdentifierNode(arg.arg, Load, lineAndColumn))
        }
        val staticCall = createStaticCall(
          adaptedMethodName,
          adaptedMethodFullName,
          lineAndColumn,
          arguments.toSeq: _*
        )
        val returnNode = createReturn(Some(staticCall), lineAndColumn)
        returnNode :: Nil
      },
      decoratorList = Nil,
      returns = None,
      isAsync = false,
      methodRefNode = None,
      returnTypeHint = None,
      lineAndColumn
    )
  }

  /** Creates the method which handles a call to the meta class object. This process
    * is also known as creating a new instance object, e.g. obj = MyClass(p1).
    * The purpose of the generated function is to adapt between the special cased
    * instance creation call and a normal call to __new__ (for now <fakeNew>).
    * So the <metaClassCallHandler> look like:
    * def <metaClassCallHandler>(p1):
    *   return DYNAMIC_CALL(receiver=TYPE_REF(meta class).<fakeNew>, instance = TYPE_REF(meta class), p1)
    */
  private def createMetaClassCallHandlerMethod(
      positionalInitParameterCount: Int,
      metaTypeDeclName: String,
      metaTypeDeclFullName: String,
      instanceTypeDeclFullName: String,
      lineAndColumn: LineAndColumn
  ): nodes.NewMethod = {
    val methodName = "<metaClassCallHandler>"
    val methodFullName = calculateFullNameFromContext(methodName)

    createMethod(
      methodName,
      methodFullName,
      parameterProvider = () => {
        val positionalParameters = mutable.ArrayBuffer.empty[nodes.NewMethodParameterIn]
        for (i <- 1 until positionalInitParameterCount) {
          positionalParameters.append(nodeBuilder.methodParameterNode("p" + i, lineAndColumn))
        }
        MethodParameters(1, positionalParameters)

      },
      bodyProvider = () => {
        val argumentIdentifiers = mutable.ArrayBuffer.empty[nodes.NewIdentifier]
        for (i <- 1 until positionalInitParameterCount) {
          argumentIdentifiers.append(createIdentifierNode("p" + i, Load, lineAndColumn))
        }

        val fakeNewCall = createInstanceCall(
          createFieldAccess(
            createTypeRef(metaTypeDeclName, metaTypeDeclFullName, lineAndColumn),
            "<fakeNew>",
            lineAndColumn
          ),
          createTypeRef(metaTypeDeclName, metaTypeDeclFullName, lineAndColumn),
          lineAndColumn,
          argumentIdentifiers.toSeq: _*
        )

        val returnNode = createReturn(Some(fakeNewCall), lineAndColumn)

        returnNode :: Nil
      },
      decoratorList = Nil,
      returns = None,
      isAsync = false,
      methodRefNode = None,
      returnTypeHint = Some(instanceTypeDeclFullName),
      lineAndColumn
    )
  }

  /** Creates a <fakeNew> method which mimics the behaviour of a default __new__ method
    * (the one you would get if no implementation is present). The reason we use a fake
    * version of the __new__ method it that we wont be able to correctly track through
    * most custom __new__ implementations as they usually call "super.__init__()" and
    * we cannot yet handle "super". The fake __new__ looks like:
    * def <fakeNew>(cls, p1):
    *   __newInstance = STATIC_CALL(<operator>.alloc)
    *   cls.__init__(__newIstance, p1)
    *   return __newInstance
    */
  private def createFakeNewMethod(
      positionalInitParameterCount: Int,
      lineAndColumn: LineAndColumn
  ): nodes.NewMethod = {

    val newMethodName = "<fakeNew>"
    val newMethodStubFullName = calculateFullNameFromContext(newMethodName)
    createMethod(
      newMethodName,
      newMethodStubFullName,
      parameterProvider = () => {
        val positionalParameters = mutable.ArrayBuffer.empty[nodes.NewMethodParameterIn]
        positionalParameters.append(nodeBuilder.methodParameterNode("cls", lineAndColumn))
        // Create one parameter node for each parameter of __init__ except for self
        // which is not passed through but created in __new__.
        // Thus we only iterate [1, positionalInitParameterCount)
        for (i <- 1 until positionalInitParameterCount) {
          positionalParameters.append(nodeBuilder.methodParameterNode("p" + i, lineAndColumn))
        }
        MethodParameters(0, positionalParameters)
      },
      bodyProvider = () => {
        val allocatorCall =
          createNAryOperatorCall(() => ("<operator>.alloc", "<operator>.alloc"), Nil, lineAndColumn)
        val assignmentToNewInstance = createAssignment(
          createIdentifierNode("__newInstance", Store, lineAndColumn),
          allocatorCall,
          lineAndColumn
        )

        val argumentIdentifiers = mutable.ArrayBuffer.empty[nodes.NewIdentifier]
        argumentIdentifiers.append(createIdentifierNode("__newInstance", Load, lineAndColumn))
        for (i <- 1 until positionalInitParameterCount) {
          argumentIdentifiers.append(createIdentifierNode("p" + i, Load, lineAndColumn))
        }

        val initCall = createXDotYCall(
          () => createIdentifierNode("cls", Load, lineAndColumn),
          "__init__",
          xMayHaveSideEffects = false,
          lineAndColumn,
          argumentIdentifiers.toSeq: _*
        )

        val returnNode = createReturn(
          Some(createIdentifierNode("__newInstance", Load, lineAndColumn)),
          lineAndColumn
        )

        assignmentToNewInstance :: initCall :: returnNode :: Nil
      },
      decoratorList = Nil,
      returns = None,
      isAsync = false,
      methodRefNode = None,
      returnTypeHint = None,
      lineAndColumn
    )
  }

  def convert(ret: ast.Return): NewNode = {
    createReturn(ret.value.map(convert), lineAndColOf(ret))
  }

  def convert(delete: ast.Delete): NewNode = {
    val deleteArgs = delete.targets.map(convert)

    val code = "del " + deleteArgs.map(codeOf).mkString(", ")
    val callNode = nodeBuilder.callNode(
      code,
      "<operator>.delete",
      DispatchTypes.STATIC_DISPATCH,
      lineAndColOf(delete)
    )

    addAstChildrenAsArguments(callNode, 1, deleteArgs)
    callNode
  }

  def convert(assign: ast.Assign): nodes.NewNode = {
    val loweredNodes =
      createValueToTargetsDecomposition(assign.targets, convert(assign.value), lineAndColOf(assign))

    if (loweredNodes.size == 1) {
      // Simple assignment can be returned directly.
      loweredNodes.head
    } else {
      createBlock(
        loweredNodes,
        lineAndColOf(assign)
      )
    }
  }

  // TODO for now we ignore the annotation part and just emit the pure
  // assignment.
  def convert(annotatedAssign: ast.AnnAssign): NewNode = {
    val targetNode = convert(annotatedAssign.target)

    annotatedAssign.value match {
      case Some(value) =>
        val valueNode = convert(value)
        createAssignment(targetNode, valueNode, lineAndColOf(annotatedAssign))
      case None =>
        // If there is no value, this is just an expr: annotation and since
        // we for now ignore the annotation we emit just the expr because
        // it may have side effects.
        targetNode
    }
  }

  def convert(augAssign: ast.AugAssign): NewNode = {
    val targetNode = convert(augAssign.target)
    val valueNode = convert(augAssign.value)

    val (operatorCode, operatorFullName) =
      augAssign.op match {
        case ast.Add  => ("+=", Operators.assignmentPlus)
        case ast.Sub  => ("-=", Operators.assignmentMinus)
        case ast.Mult => ("*=", Operators.assignmentMultiplication)
        case ast.MatMult =>
          ("@=", "<operator>.assignmentMatMult") // TODO make this a define and add policy for this
        case ast.Div    => ("/=", Operators.assignmentDivision)
        case ast.Mod    => ("%=", Operators.assignmentModulo)
        case ast.Pow    => ("**=", Operators.assignmentExponentiation)
        case ast.LShift => ("<<=", Operators.assignmentShiftLeft)
        case ast.RShift => ("<<=", Operators.assignmentArithmeticShiftRight)
        case ast.BitOr  => ("|=", Operators.assignmentOr)
        case ast.BitXor => ("^=", Operators.assignmentXor)
        case ast.BitAnd => ("&=", Operators.assignmentAnd)
        case ast.FloorDiv =>
          (
            "//=",
            "<operator>.assignmentFloorDiv"
          ) // TODO make this a define and add policy for this
      }

    createAugAssignment(
      targetNode,
      operatorCode,
      valueNode,
      operatorFullName,
      lineAndColOf(augAssign)
    )
  }

  // TODO write test
  def convert(forStmt: ast.For): NewNode = {
    createForLowering(
      forStmt.target,
      forStmt.iter,
      Iterable.empty,
      forStmt.body.map(convert),
      forStmt.orelse.map(convert),
      isAsync = false,
      lineAndColOf(forStmt)
    )
  }

  def convert(forStmt: ast.AsyncFor): NewNode = {
    createForLowering(
      forStmt.target,
      forStmt.iter,
      Iterable.empty,
      forStmt.body.map(convert),
      forStmt.orelse.map(convert),
      isAsync = true,
      lineAndColOf(forStmt)
    )
  }

  // Lowering of for x in y: <statements>:
  // {
  //   iterator = y.__iter__()
  //   while (UNKNOWN condition):
  //     <loweringOf>(x = iterator.__next__())
  //     <statements>
  // }
  // If "ifs" are present the lower of for x in y if z if a: ..,:
  // {
  //   iterator = y.__iter__()
  //   while (UNKNOWN condition):
  //     if (!(z and a)): continue
  //     <loweringOf>(x = iterator.__next__())
  //     <statements>
  // }
  protected def createForLowering(
      target: ast.iexpr,
      iter: ast.iexpr,
      ifs: Iterable[ast.iexpr],
      bodyNodes: Iterable[nodes.NewNode],
      orelseNodes: Iterable[nodes.NewNode],
      isAsync: Boolean,
      lineAndColumn: LineAndColumn
  ): nodes.NewNode = {
    val iterVariableName = getUnusedName()
    val iterExprIterCallNode =
      createXDotYCall(
        () => convert(iter),
        "__iter__",
        xMayHaveSideEffects = !iter.isInstanceOf[ast.Name],
        lineAndColumn
      )
    val iterAssignNode =
      createAssignmentToIdentifier(iterVariableName, iterExprIterCallNode, lineAndColumn)

    val conditionNode = nodeBuilder.unknownNode("iteratorNonEmptyOrException", "", lineAndColumn)

    val controlStructureNode =
      nodeBuilder.controlStructureNode(
        "while ... : ...",
        ControlStructureTypes.WHILE,
        lineAndColumn
      )
    edgeBuilder.conditionEdge(conditionNode, controlStructureNode)

    val iterNextCallNode =
      createXDotYCall(
        () => createIdentifierNode(iterVariableName, Load, lineAndColumn),
        "__next__",
        xMayHaveSideEffects = false,
        lineAndColumn
      )

    val loweredAssignNodes =
      createValueToTargetsDecomposition(Iterable.single(target), iterNextCallNode, lineAndColumn)

    val blockStmtNodes = mutable.ArrayBuffer.empty[nodes.NewNode]
    blockStmtNodes.appendAll(loweredAssignNodes)

    if (ifs.nonEmpty) {
      val ifNotContinueNode = convert(
        new ast.If(
          new ast.UnaryOp(
            ast.Not,
            new ast.BoolOp(ast.And, ifs.to(mutable.Seq), ifs.head.attributeProvider),
            ifs.head.attributeProvider
          ),
          mutable.ArrayBuffer.empty[ast.istmt].append(new ast.Continue(ifs.head.attributeProvider)),
          mutable.Seq.empty[ast.istmt],
          ifs.head.attributeProvider
        )
      )

      blockStmtNodes.append(ifNotContinueNode)
    }
    bodyNodes.foreach(blockStmtNodes.append)

    val bodyBlockNode = createBlock(blockStmtNodes, lineAndColumn)
    addAstChildNodes(controlStructureNode, 1, conditionNode, bodyBlockNode)

    if (orelseNodes.nonEmpty) {
      val elseBlockNode = createBlock(orelseNodes, lineAndColumn)
      addAstChildNodes(controlStructureNode, 3, elseBlockNode)
    }

    createBlock(iterAssignNode :: controlStructureNode :: Nil, lineAndColumn)
  }

  def convert(astWhile: ast.While): nodes.NewNode = {
    val conditionNode = convert(astWhile.test)
    val bodyStmtNodes = astWhile.body.map(convert)

    val controlStructureNode =
      nodeBuilder.controlStructureNode(
        "while ... : ...",
        ControlStructureTypes.WHILE,
        lineAndColOf(astWhile)
      )
    edgeBuilder.conditionEdge(conditionNode, controlStructureNode)

    val bodyBlockNode = createBlock(bodyStmtNodes, lineAndColOf(astWhile))
    addAstChildNodes(controlStructureNode, 1, conditionNode, bodyBlockNode)

    if (astWhile.orelse.nonEmpty) {
      val elseStmtNodes = astWhile.orelse.map(convert)
      val elseBlockNode =
        createBlock(elseStmtNodes, lineAndColOf(astWhile.orelse.head))
      addAstChildNodes(controlStructureNode, 3, elseBlockNode)
    }

    controlStructureNode
  }

  def convert(astIf: ast.If): nodes.NewNode = {
    val conditionNode = convert(astIf.test)
    val bodyStmtNodes = astIf.body.map(convert)

    val controlStructureNode =
      nodeBuilder.controlStructureNode(
        "if ... : ...",
        ControlStructureTypes.IF,
        lineAndColOf(astIf)
      )
    edgeBuilder.conditionEdge(conditionNode, controlStructureNode)

    val bodyBlockNode = createBlock(bodyStmtNodes, lineAndColOf(astIf))
    addAstChildNodes(controlStructureNode, 1, conditionNode, bodyBlockNode)

    if (astIf.orelse.nonEmpty) {
      val elseStmtNodes = astIf.orelse.map(convert)
      val elseBlockNode = createBlock(elseStmtNodes, lineAndColOf(astIf.orelse.head))
      addAstChildNodes(controlStructureNode, 3, elseBlockNode)
    }

    controlStructureNode
  }

  def convert(withStmt: ast.With): NewNode = ???

  def convert(withStmt: ast.AsyncWith): NewNode = ???

  def convert(raise: ast.Raise): NewNode = ???

  def convert(tryStmt: ast.Try): NewNode = {
    val controlStructureNode =
      nodeBuilder.controlStructureNode("try: ...", ControlStructureTypes.TRY, lineAndColOf(tryStmt))

    val bodyBlockNode = createBlock(tryStmt.body.map(convert), lineAndColOf(tryStmt))
    val handlersBlockNode = createBlock(tryStmt.handlers.map(convert), lineAndColOf(tryStmt))
    val finalBlockNode = createBlock(tryStmt.finalbody.map(convert), lineAndColOf(tryStmt))
    val orElseBlockNode = createBlock(tryStmt.orelse.map(convert), lineAndColOf(tryStmt))

    addAstChildNodes(
      controlStructureNode,
      1,
      bodyBlockNode,
      handlersBlockNode,
      finalBlockNode,
      orElseBlockNode
    )

    controlStructureNode
  }

  def convert(assert: ast.Assert): NewNode = {
    val testNode = convert(assert.test)
    val msgNode = assert.msg.map(convert)

    val code = "assert " + codeOf(testNode) + msgNode.map(m => ", " + codeOf(m)).getOrElse("")
    val callNode = nodeBuilder.callNode(
      code,
      "<operator>.assert",
      DispatchTypes.STATIC_DISPATCH,
      lineAndColOf(assert)
    )

    addAstChildrenAsArguments(callNode, 1, testNode)
    if (msgNode.isDefined) {
      addAstChildrenAsArguments(callNode, 2, msgNode)
    }
    callNode
  }

  // Lowering of import x:
  //   x = import("", "x")
  // Lowering of import x as y:
  //   y = import("", "x")
  // Lowering of import x, y:
  //   {
  //     x = import("", "x")
  //     y = import("", "y")
  //   }
  def convert(importStmt: ast.Import): NewNode = {
    createTransformedImport("", importStmt.names, lineAndColOf(importStmt))
  }

  // Lowering of from x import y:
  //   y = import("x", "y")
  // Lowering of from x import y as z:
  //   z = import("x", "y")
  // Lowering of from x import y, z:
  //   {
  //     y = import("x", "y")
  //     z = import("x", "z")
  //   }
  def convert(importFrom: ast.ImportFrom): NewNode = {
    var moduleName = ""

    for (i <- 0 until importFrom.level) {
      moduleName = moduleName.appended('.')
    }
    moduleName += importFrom.module.getOrElse("")

    createTransformedImport(moduleName, importFrom.names, lineAndColOf(importFrom))
  }

  def convert(global: ast.Global): NewNode = {
    global.names.foreach(contextStack.addGlobalVariable)
    val code = global.names.mkString("global ", ", ", "")
    nodeBuilder.unknownNode(code, global.getClass.getName, lineAndColOf(global))
  }

  def convert(nonLocal: ast.Nonlocal): NewNode = {
    nonLocal.names.foreach(contextStack.addNonLocalVariable)
    val code = nonLocal.names.mkString("nonlocal ", ", ", "")
    nodeBuilder.unknownNode(code, nonLocal.getClass.getName, lineAndColOf(nonLocal))
  }

  def convert(expr: ast.Expr): nodes.NewNode = {
    convert(expr.value)
  }

  def convert(pass: ast.Pass): nodes.NewNode = {
    nodeBuilder.callNode(
      "pass",
      "<operator>.pass",
      DispatchTypes.STATIC_DISPATCH,
      lineAndColOf(pass)
    )
  }

  def convert(astBreak: ast.Break): nodes.NewNode = {
    nodeBuilder.controlStructureNode("break", ControlStructureTypes.BREAK, lineAndColOf(astBreak))
  }

  def convert(astContinue: ast.Continue): nodes.NewNode = {
    nodeBuilder.controlStructureNode(
      "continue",
      ControlStructureTypes.CONTINUE,
      lineAndColOf(astContinue)
    )
  }

  def convert(raise: ast.RaiseP2): NewNode = ???

  def convert(errorStatement: ast.ErrorStatement): NewNode = {
    nodeBuilder.unknownNode(
      errorStatement.toString,
      errorStatement.getClass.getName,
      lineAndColOf(errorStatement)
    )
  }

  def convert(expr: ast.iexpr): NewNode = {
    expr match {
      case node: ast.BoolOp        => convert(node)
      case node: ast.NamedExpr     => convert(node)
      case node: ast.BinOp         => convert(node)
      case node: ast.UnaryOp       => convert(node)
      case node: ast.Lambda        => convert(node)
      case node: ast.IfExp         => convert(node)
      case node: ast.Dict          => convert(node)
      case node: ast.Set           => convert(node)
      case node: ast.ListComp      => convert(node)
      case node: ast.SetComp       => convert(node)
      case node: ast.DictComp      => convert(node)
      case node: ast.GeneratorExp  => unhandled(node)
      case node: ast.Await         => convert(node)
      case node: ast.Yield         => unhandled(node)
      case node: ast.YieldFrom     => unhandled(node)
      case node: ast.Compare       => convert(node)
      case node: ast.Call          => convert(node)
      case node: ast.Constant      => convert(node)
      case node: ast.Attribute     => convert(node)
      case node: ast.Subscript     => convert(node)
      case node: ast.Starred       => unhandled(node)
      case node: ast.Name          => convert(node)
      case node: ast.List          => convert(node)
      case node: ast.Tuple         => convert(node)
      case node: ast.Slice         => unhandled(node)
      case node: ast.StringExpList => unhandled(node)
    }
  }

  def convert(boolOp: ast.BoolOp): nodes.NewNode = {
    def boolOpToCodeAndFullName(operator: ast.iboolop): () => (String, String) = { () =>
      {
        operator match {
          case ast.And => ("and", Operators.logicalAnd)
          case ast.Or  => ("or", Operators.logicalOr)
        }
      }
    }

    val operandNodes = boolOp.values.map(convert)
    createNAryOperatorCall(boolOpToCodeAndFullName(boolOp.op), operandNodes, lineAndColOf(boolOp))
  }

  // TODO test
  def convert(namedExpr: ast.NamedExpr): NewNode = {
    val targetNode = convert(namedExpr.target)
    val valueNode = convert(namedExpr.value)

    createAssignment(targetNode, valueNode, lineAndColOf(namedExpr))
  }

  def convert(binOp: ast.BinOp): nodes.NewNode = {
    val lhsNode = convert(binOp.left)
    val rhsNode = convert(binOp.right)

    val opCodeAndFullName =
      binOp.op match {
        case ast.Add  => ("+", Operators.addition)
        case ast.Sub  => ("-", Operators.subtraction)
        case ast.Mult => ("*", Operators.multiplication)
        case ast.MatMult =>
          ("@", "<operator>.matMult") // TODO make this a define and add policy for this
        case ast.Div    => ("/", Operators.division)
        case ast.Mod    => ("%", Operators.modulo)
        case ast.Pow    => ("**", Operators.exponentiation)
        case ast.LShift => ("<<", Operators.shiftLeft)
        case ast.RShift => ("<<", Operators.arithmeticShiftRight)
        case ast.BitOr  => ("|", Operators.or)
        case ast.BitXor => ("^", Operators.xor)
        case ast.BitAnd => ("&", Operators.and)
        case ast.FloorDiv =>
          ("//", "<operator>.floorDiv") // TODO make this a define and add policy for this
      }

    createBinaryOperatorCall(lhsNode, () => opCodeAndFullName, rhsNode, lineAndColOf(binOp))
  }

  def convert(unaryOp: ast.UnaryOp): nodes.NewNode = {
    val operandNode = convert(unaryOp.operand)

    val (operatorCode, methodFullName) =
      unaryOp.op match {
        case ast.Invert => ("~", Operators.not)
        case ast.Not    => ("not ", Operators.logicalNot)
        case ast.UAdd   => ("+", Operators.plus)
        case ast.USub   => ("-", Operators.minus)
      }

    val code = operatorCode + codeOf(operandNode)
    val callNode = nodeBuilder.callNode(
      code,
      methodFullName,
      DispatchTypes.STATIC_DISPATCH,
      lineAndColOf(unaryOp)
    )

    addAstChildrenAsArguments(callNode, 1, operandNode)

    callNode
  }

  def convert(lambda: ast.Lambda): NewNode = {
    // TODO test lambda expression.
    val (_, methodRefNode) = createMethodAndMethodRef(
      "<lambda>",
      createParameterProcessingFunction(lambda.args, isStatic = false),
      () => Iterable.single(convert(new ast.Return(lambda.body, lambda.attributeProvider))),
      decoratorList = Nil,
      returns = None,
      isAsync = false,
      lineAndColOf(lambda)
    )
    methodRefNode
  }

  // TODO test
  def convert(ifExp: ast.IfExp): NewNode = {
    val bodyNode = convert(ifExp.body)
    val testNode = convert(ifExp.test)
    val orElseNode = convert(ifExp.orelse)

    val code = codeOf(bodyNode) + " if " + codeOf(testNode) + " else " + codeOf(orElseNode)
    val callNode = nodeBuilder.callNode(
      code,
      Operators.conditional,
      DispatchTypes.STATIC_DISPATCH,
      lineAndColOf(ifExp)
    )

    // testNode is first argument to match semantics of Operators.conditional.
    addAstChildrenAsArguments(callNode, 1, testNode, bodyNode, orElseNode)

    callNode
  }

  /** Lowering of {x:1, y:2, **z}:
    *   {
    *     tmp = dict()
    *     tmp[x] = 1
    *     tmp[y] = 2
    *     tmp.update(z)
    *     tmp
    *   }
    */
  // TODO test
  def convert(dict: ast.Dict): NewNode = {
    val tmpVariableName = getUnusedName()
    val dictConstructorCallNode =
      createCall(createIdentifierNode("dict", Load, lineAndColOf(dict)), lineAndColOf(dict))
    val dictVariableAssigNode =
      createAssignmentToIdentifier(tmpVariableName, dictConstructorCallNode, lineAndColOf(dict))

    val dictElementAssignNodes = dict.keys.zip(dict.values).map { case (key, value) =>
      key match {
        case Some(key) =>
          val indexAccessNode = createIndexAccess(
            createIdentifierNode(tmpVariableName, Load, lineAndColOf(dict)),
            convert(key),
            lineAndColOf(dict)
          )

          createAssignment(indexAccessNode, convert(value), lineAndColOf(dict))
        case None =>
          createXDotYCall(
            () => createIdentifierNode(tmpVariableName, Load, lineAndColOf(dict)),
            "update",
            xMayHaveSideEffects = false,
            lineAndColOf(dict),
            convert(value)
          )
      }
    }

    val dictInstanceReturnIdentifierNode =
      createIdentifierNode(tmpVariableName, Load, lineAndColOf(dict))

    val blockElements = mutable.ArrayBuffer.empty[nodes.NewNode]
    blockElements.append(dictVariableAssigNode)
    blockElements.appendAll(dictElementAssignNodes)
    blockElements.append(dictInstanceReturnIdentifierNode)
    createBlock(blockElements, lineAndColOf(dict))
  }

  /** Lowering of {1, 2}:
    *   {
    *     tmp = set()
    *     tmp.add(1)
    *     tmp.add(2)
    *     tmp
    *   }
    */
  // TODO test
  def convert(set: ast.Set): nodes.NewNode = {
    val tmpVariableName = getUnusedName()

    val setConstructorCall =
      createCall(createIdentifierNode("set", Load, lineAndColOf(set)), lineAndColOf(set))
    val setInstanceAssignment =
      createAssignmentToIdentifier(tmpVariableName, setConstructorCall, lineAndColOf(set))

    val appendCallNodes = set.elts.map { setElement =>
      createXDotYCall(
        () => createIdentifierNode(tmpVariableName, Load, lineAndColOf(set)),
        "add",
        xMayHaveSideEffects = false,
        lineAndColOf(set),
        convert(setElement)
      )
    }

    val setInstanceIdForReturn = createIdentifierNode(tmpVariableName, Load, lineAndColOf(set))

    val blockElements = mutable.ArrayBuffer.empty[nodes.NewNode]
    blockElements.append(setInstanceAssignment)
    blockElements.appendAll(appendCallNodes)
    blockElements.append(setInstanceIdForReturn)
    createBlock(blockElements, lineAndColOf(set))
  }

  /** Lowering of [x for y in l for x in y]:
    * {
    *   tmp = list()
    *   <loweringOf>(
    *   for y in l:
    *     for x in y:
    *       tmp.append(x)
    *   )
    *   tmp
    * }
    */
  // TODO test
  def convert(listComp: ast.ListComp): NewNode = {
    contextStack.pushSpecialContext()
    val tmpVariableName = getUnusedName()

    // Create tmp = list()
    val constructorCallNode =
      createCall(createIdentifierNode("list", Load, lineAndColOf(listComp)), lineAndColOf(listComp))
    val variableAssignNode =
      createAssignmentToIdentifier(tmpVariableName, constructorCallNode, lineAndColOf(listComp))

    // Create tmp.append(x)
    val listVarAppendCallNode = createXDotYCall(
      () => createIdentifierNode(tmpVariableName, Load, lineAndColOf(listComp)),
      "append",
      xMayHaveSideEffects = false,
      lineAndColOf(listComp),
      convert(listComp.elt)
    )

    val comprehensionBlockNode = createComprehensionLowering(
      tmpVariableName,
      variableAssignNode,
      listVarAppendCallNode,
      listComp.generators,
      lineAndColOf(listComp)
    )

    contextStack.pop()

    comprehensionBlockNode
  }

  /** Lowering of {x for y in l for x in y}:
    * {
    *   tmp = set()
    *   <loweringOf>(
    *   for y in l:
    *     for x in y:
    *       tmp.add(x)
    *   )
    *   tmp
    * }
    */
  // TODO test
  def convert(setComp: ast.SetComp): NewNode = {
    contextStack.pushSpecialContext()
    val tmpVariableName = getUnusedName()

    // Create tmp = set()
    val constructorCallNode =
      createCall(createIdentifierNode("set", Load, lineAndColOf(setComp)), lineAndColOf(setComp))
    val variableAssignNode =
      createAssignmentToIdentifier(tmpVariableName, constructorCallNode, lineAndColOf(setComp))

    // Create tmp.add(x)
    val setVarAddCallNode = createXDotYCall(
      () => createIdentifierNode(tmpVariableName, Load, lineAndColOf(setComp)),
      "add",
      xMayHaveSideEffects = false,
      lineAndColOf(setComp),
      convert(setComp.elt)
    )

    val comprehensionBlockNode = createComprehensionLowering(
      tmpVariableName,
      variableAssignNode,
      setVarAddCallNode,
      setComp.generators,
      lineAndColOf(setComp)
    )

    contextStack.pop()

    comprehensionBlockNode
  }

  /** Lowering of {k:v for y in l for k, v in y}:
    * {
    *   tmp = dict()
    *   <loweringOf>(
    *   for y in l:
    *     for k, v in y:
    *       tmp[k] = v
    *   )
    *   tmp
    * }
    */
  // TODO test
  def convert(dictComp: ast.DictComp): NewNode = {
    contextStack.pushSpecialContext()
    val tmpVariableName = getUnusedName()

    // Create tmp = dict()
    val constructorCallNode =
      createCall(createIdentifierNode("dict", Load, lineAndColOf(dictComp)), lineAndColOf(dictComp))
    val variableAssignNode =
      createAssignmentToIdentifier(tmpVariableName, constructorCallNode, lineAndColOf(dictComp))

    // Create tmp[k] = v
    val dictAssigNode = createAssignment(
      createIndexAccess(
        createIdentifierNode(tmpVariableName, Load, lineAndColOf(dictComp)),
        convert(dictComp.key),
        lineAndColOf(dictComp)
      ),
      convert(dictComp.value),
      lineAndColOf(dictComp)
    )

    val comprehensionBlockNode = createComprehensionLowering(
      tmpVariableName,
      variableAssignNode,
      dictAssigNode,
      dictComp.generators,
      lineAndColOf(dictComp)
    )

    contextStack.pop()

    comprehensionBlockNode
  }

  def convert(generatorExp: ast.GeneratorExp): NewNode = ???

  def convert(await: ast.Await): NewNode = {
    // Since the CPG format does not provide means to model async/await,
    // we for now treat it as non existing.
    convert(await.value)
  }

  def convert(yieldExpr: ast.Yield): NewNode = ???

  def convert(yieldFrom: ast.YieldFrom): NewNode = ???

  // In case of a single compare operation there is no lowering applied.
  // So e.g. x < y stay untouched.
  // Otherwise the lowering is as follows:
  //  Src AST:
  //    x < y < z < a
  //  Lowering:
  //    {
  //      tmp1 = y
  //      x < tmp1 && {
  //        tmp2 = z
  //        tmp1 < tmp2 && {
  //          tmp2 < a
  //        }
  //      }
  //    }
  def convert(compare: ast.Compare): NewNode = {
    assert(compare.ops.size == compare.comparators.size)
    var lhsNode = convert(compare.left)

    val topLevelExprNodes =
      lowerComparatorChain(lhsNode, compare.ops, compare.comparators, lineAndColOf(compare))
    if (topLevelExprNodes.size > 1) {
      createBlock(topLevelExprNodes, lineAndColOf(compare))
    } else {
      topLevelExprNodes.head
    }
  }

  private def compopToOpCodeAndFullName(compareOp: ast.icompop): () => (String, String) = { () =>
    {
      compareOp match {
        case ast.Eq    => ("==", Operators.equals)
        case ast.NotEq => ("!=", Operators.notEquals)
        case ast.Lt    => ("<", Operators.lessThan)
        case ast.LtE   => ("<=", Operators.lessEqualsThan)
        case ast.Gt    => (">", Operators.greaterThan)
        case ast.GtE   => (">=", Operators.greaterEqualsThan)
        case ast.Is    => ("is", "<operator>.is")
        case ast.IsNot => ("is not", "<operator>.isNot")
        case ast.In    => ("in", "<operator>.in")
        case ast.NotIn => ("not in", "<operator>.notIn")
      }
    }
  }

  def lowerComparatorChain(
      lhsNode: nodes.NewNode,
      compOperators: Iterable[ast.icompop],
      comparators: Iterable[ast.iexpr],
      lineAndColumn: LineAndColumn
  ): Iterable[nodes.NewNode] = {
    val rhsNode = convert(comparators.head)

    if (compOperators.size == 1) {
      val compareNode = createBinaryOperatorCall(
        lhsNode,
        compopToOpCodeAndFullName(compOperators.head),
        rhsNode,
        lineAndColumn
      )
      Iterable.single(compareNode)
    } else {
      val tmpVariableName = getUnusedName()
      val assignmentNode = createAssignmentToIdentifier(tmpVariableName, rhsNode, lineAndColumn)

      val tmpIdentifierCompare1 = createIdentifierNode(tmpVariableName, Load, lineAndColumn)
      val compareNode = createBinaryOperatorCall(
        lhsNode,
        compopToOpCodeAndFullName(compOperators.head),
        tmpIdentifierCompare1,
        lineAndColumn
      )

      val tmpIdentifierCompare2 = createIdentifierNode(tmpVariableName, Load, lineAndColumn)
      val childNodes = lowerComparatorChain(
        tmpIdentifierCompare2,
        compOperators.tail,
        comparators.tail,
        lineAndColumn
      )

      val blockNode = createBlock(childNodes, lineAndColumn)

      Iterable(
        assignmentNode,
        createBinaryOperatorCall(compareNode, andOpCodeAndFullName(), blockNode, lineAndColumn)
      )
    }
  }

  private def andOpCodeAndFullName(): () => (String, String) = { () =>
    ("and", Operators.logicalAnd)
  }

  /** TODO
    * For now this function compromises on the correctness of the
    * lowering in order to get some data flow tracking going.
    * 1. For constructs like x.func() we assume x to be the
    *    instance which is passed into func. This is not true
    *    since the instance method object gets the instance
    *    already bound/captured during function access.
    *    This becomes relevant for constructs like:
    *    x.func = y.func <- y.func is class method object
    *    x.func()
    *    In this case the instance passed into func is y and
    *    not x. We cannot represent this in th CPG and thus
    *    stick to the assumption that the part before the "."
    *    and the bound/captured instance will be the same.
    *    For reference see:
    *    https://docs.python.org/3/reference/datamodel.html#the-standard-type-hierarchy
    *    search for "Instance methods"
    *
    * 2. No named parameter support. CPG does not supports this.
    */
  def convert(call: ast.Call): nodes.NewNode = {
    val argumentNodes = call.args.map(convert).toSeq

    call.func match {
      case attribute: ast.Attribute =>
        createXDotYCall(
          () => convert(attribute.value),
          attribute.attr,
          xMayHaveSideEffects = !attribute.value.isInstanceOf[ast.Name],
          lineAndColOf(call),
          argumentNodes: _*
        )
      case _ =>
        val receiverNode = convert(call.func)
        createCall(receiverNode, lineAndColOf(call), argumentNodes: _*)
    }
  }

  def convert(constant: ast.Constant): nodes.NewNode = {
    constant.value match {
      case stringConstant: ast.StringConstant =>
        nodeBuilder.stringLiteralNode(stringConstant.value, lineAndColOf(constant))
      case boolConstant: ast.BoolConstant =>
        val boolStr = if (boolConstant.value) "True" else "False"
        nodeBuilder.stringLiteralNode(boolStr, lineAndColOf(constant))
      case intConstant: ast.IntConstant =>
        nodeBuilder.numberLiteralNode(intConstant.value, lineAndColOf(constant))
      case floatConstant: ast.FloatConstant =>
        nodeBuilder.numberLiteralNode(floatConstant.value, lineAndColOf(constant))
      case imaginaryConstant: ast.ImaginaryConstant =>
        nodeBuilder.numberLiteralNode(imaginaryConstant.value + "j", lineAndColOf(constant))
      case ast.NoneConstant =>
        nodeBuilder.numberLiteralNode("None", lineAndColOf(constant))
      case ast.EllipsisConstant =>
        nodeBuilder.numberLiteralNode("...", lineAndColOf(constant))
    }
  }

  /** TODO
    * We currently ignore possible attribute access provider/interception
    * mechanisms like __getattr__, __getattribute__ and __get__.
    */
  def convert(attribute: ast.Attribute): nodes.NewNode = {
    val baseNode = convert(attribute.value)

    createFieldAccess(baseNode, attribute.attr, lineAndColOf(attribute))
  }

  def convert(subscript: ast.Subscript): NewNode = {
    createIndexAccess(convert(subscript.value), convert(subscript.slice), lineAndColOf(subscript))
  }

  def convert(starred: ast.Starred): NewNode = ???

  def convert(name: ast.Name): nodes.NewNode = {
    val memoryOperation = memOpMap.get(name).get
    createIdentifierNode(name.id, memoryOperation, lineAndColOf(name))
  }

  /** Lowering of [1, 2]:
    *   {
    *     tmp = list()
    *     tmp.append(1)
    *     tmp.append(2)
    *     tmp
    *   }
    */
  // TODO test
  def convert(list: ast.List): nodes.NewNode = {
    // Must be a List as part of a Load memory operation because a List literal
    // is not permitted as argument to a Del and List as part of a Store does not
    // reach here.
    assert(memOpMap.get(list).get == Load)
    val tmpVariableName = getUnusedName()

    val listConstructorCall =
      createCall(createIdentifierNode("list", Load, lineAndColOf(list)), lineAndColOf(list))
    val listInstanceAssignment =
      createAssignmentToIdentifier(tmpVariableName, listConstructorCall, lineAndColOf(list))

    val appendCallNodes = list.elts.map { listElement =>
      val elementNode = convert(listElement)

      createXDotYCall(
        () => createIdentifierNode(tmpVariableName, Load, lineAndColOf(list)),
        "append",
        xMayHaveSideEffects = false,
        lineAndColOf(list),
        elementNode
      )
    }

    val listInstanceIdForReturn = createIdentifierNode(tmpVariableName, Load, lineAndColOf(list))

    val blockElements = mutable.ArrayBuffer.empty[nodes.NewNode]
    blockElements.append(listInstanceAssignment)
    blockElements.appendAll(appendCallNodes)
    blockElements.append(listInstanceIdForReturn)
    createBlock(blockElements, lineAndColOf(list))
  }

  /** Lowering of (1, 2):
    *   {
    *     tmp = tuple()
    *     tmp[0] = 1
    *     tmp[1] = 2
    *     tmp
    *   }
    */
  // TODO test
  def convert(tuple: ast.Tuple): NewNode = {
    // Must be a tuple as part of a Load memory operation because a Tuple literal
    // is not permitted as argument to a Del and Tuple as part of a Store does not
    // reach here.
    assert(memOpMap.get(tuple).get == Load)
    val tmpVariableName = getUnusedName()
    val tupleConstructorCallNode =
      createCall(createIdentifierNode("tuple", Load, lineAndColOf(tuple)), lineAndColOf(tuple))
    val tupleVariableAssignNode =
      createAssignmentToIdentifier(tmpVariableName, tupleConstructorCallNode, lineAndColOf(tuple))

    var index = 0
    val tupleElementAssignNodes = tuple.elts.map { tupleElement =>
      val indexAccessNode = createIndexAccess(
        createIdentifierNode(tmpVariableName, Load, lineAndColOf(tuple)),
        nodeBuilder.numberLiteralNode(index, lineAndColOf(tuple)),
        lineAndColOf(tuple)
      )

      index += 1

      createAssignment(indexAccessNode, convert(tupleElement), lineAndColOf(tuple))
    }

    val tupleInstanceReturnIdentifierNode =
      createIdentifierNode(tmpVariableName, Load, lineAndColOf(tuple))

    val blockElements = mutable.ArrayBuffer.empty[nodes.NewNode]
    blockElements.append(tupleVariableAssignNode)
    blockElements.appendAll(tupleElementAssignNodes)
    blockElements.append(tupleInstanceReturnIdentifierNode)
    createBlock(blockElements, lineAndColOf(tuple))
  }

  def convert(slice: ast.Slice): NewNode = ???

  def convert(stringExpList: ast.StringExpList): NewNode = ???

  // TODO Since there is now real concept of reflecting exception handlers
  // semantically in the CPG we just make sure that the variable scoping
  // is right and that we convert the exception handler body.
  // TODO tests
  def convert(exceptHandler: ast.ExceptHandler): NewNode = {
    contextStack.pushSpecialContext()
    val specialTargetLocals = mutable.ArrayBuffer.empty[nodes.NewLocal]
    if (exceptHandler.name.isDefined) {
      val localNode = nodeBuilder.localNode(exceptHandler.name.get, None)
      specialTargetLocals.append(localNode)
      contextStack.addSpecialVariable(localNode)
    }

    val blockNode = createBlock(exceptHandler.body.map(convert), lineAndColOf(exceptHandler))
    addAstChildNodes(blockNode, 1, specialTargetLocals)

    contextStack.pop()

    blockNode
  }

  def convert(arg: ast.Arg): nodes.NewMethodParameterIn = {
    nodeBuilder.methodParameterNode(arg.arg, lineAndColOf(arg))
  }

  def convert(keyword: ast.Keyword): NewNode = ???

  def convert(alias: ast.Alias): NewNode = ???

  def convert(withItem: ast.Withitem): NewNode = ???

  def convert(typeIgnore: ast.TypeIgnore): NewNode = ???

  private def calculateFullNameFromContext(name: String): String = {
    val contextQualName = contextStack.qualName
    if (contextQualName != "") {
      fileName + ":" + contextQualName + "." + name
    } else {
      fileName + ":" + name
    }
  }
}
