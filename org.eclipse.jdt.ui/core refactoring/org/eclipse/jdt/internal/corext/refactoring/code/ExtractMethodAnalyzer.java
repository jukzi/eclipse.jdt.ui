/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */
package org.eclipse.jdt.internal.corext.refactoring.code;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.ArrayInitializer;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.VariableDeclarationExpression;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;

import org.eclipse.jdt.internal.ui.text.java.AnonymousTypeCompletionProposal;

import org.eclipse.jdt.internal.compiler.parser.Scanner;
import org.eclipse.jdt.internal.corext.Assert;
import org.eclipse.jdt.internal.corext.dom.ASTNodes;
import org.eclipse.jdt.internal.corext.dom.Bindings;
import org.eclipse.jdt.internal.corext.dom.CompilationUnitBuffer;
import org.eclipse.jdt.internal.corext.dom.LocalVariableIndex;
import org.eclipse.jdt.internal.corext.dom.Selection;
import org.eclipse.jdt.internal.corext.refactoring.Checks;
import org.eclipse.jdt.internal.corext.refactoring.RefactoringCoreMessages;
import org.eclipse.jdt.internal.corext.refactoring.base.RefactoringStatus;
import org.eclipse.jdt.internal.corext.refactoring.code.flow.FlowContext;
import org.eclipse.jdt.internal.corext.refactoring.code.flow.FlowInfo;
import org.eclipse.jdt.internal.corext.refactoring.code.flow.InOutFlowAnalyzer;
import org.eclipse.jdt.internal.corext.refactoring.code.flow.InputFlowAnalyzer;
import org.eclipse.jdt.internal.corext.refactoring.util.CodeAnalyzer2;

/* package */ class ExtractMethodAnalyzer extends CodeAnalyzer2 {

	public static final int ERROR=					-2;
	public static final int UNDEFINED=				-1;
	public static final int NO=						0;
	public static final int EXPRESSION=				1;
	public static final int ACCESS_TO_LOCAL=		2;
	public static final int RETURN_STATEMENT_VOID=	3;
	public static final int RETURN_STATEMENT_VALUE=	4;
	public static final int MULTIPLE=				5;

	private static final char[] VOID= {'v', 'o', 'i', 'd'};

	private MethodDeclaration fEnclosingMethod;
	private int fMaxVariableId;

	private int fReturnKind;
	
	private FlowInfo fInputFlowInfo;
	private FlowContext fInputFlowContext;
	
	private IVariableBinding[] fArguments;
	private IVariableBinding[] fMethodLocals;
	
	private IVariableBinding fReturnValue;
	private IVariableBinding[] fCallerLocals;
	private IVariableBinding fReturnLocal;
	
	private ITypeBinding[] fExceptions;	
	
	public ExtractMethodAnalyzer(CompilationUnitBuffer buffer, Selection selection) {
		super(buffer, selection, false);
	}
	
	public MethodDeclaration getEnclosingMethod() {
		return fEnclosingMethod;
	}
	
	public int getReturnKind() {
		return fReturnKind;
	}
	
	public boolean extractsExpression() {
		return fReturnKind == EXPRESSION;
	}
	
	public Type getReturnType() {
		AST ast= fEnclosingMethod.getAST();
		switch (fReturnKind) {
			case ACCESS_TO_LOCAL:
				return ASTNodes.getType(ASTNodes.findVariableDeclaration(fReturnValue, fEnclosingMethod));
			case EXPRESSION:
				Expression expression= (Expression)getFirstSelectedNode();
				return Bindings.createType(expression.resolveTypeBinding(), ast);
			case RETURN_STATEMENT_VALUE:
				if (fEnclosingMethod.isConstructor())
					return null;
				return fEnclosingMethod.getReturnType();
			default:
				return ast.newPrimitiveType(PrimitiveType.VOID);
		}
	}
	
	public boolean generateImport() {
		switch (fReturnKind) {
			case EXPRESSION:
				return true;
			default:
				return false;
		}
	}
	
	public IVariableBinding[] getArguments() {
		return fArguments;
	}
	
	public IVariableBinding[] getMethodLocals() {
		return fMethodLocals;
	}
	
	public IVariableBinding getReturnValue() {
		return fReturnValue;
	}
	
	public IVariableBinding[] getCallerLocals() {
		return fCallerLocals;
	}
	
	public IVariableBinding getReturnLocal() {
		return fReturnLocal;
	}
	
//	public String getReturnTypeAsString() {
//		if (fEnclosingMethod.isConstructor())
//			return ""; //$NON-NLS-1$
//			
//		Type type= fEnclosingMethod.getReturnType();
//		if (type == null)
//			return ""; //$NON-NLS-1$
//			
//		return ASTNode2String.perform(type);
//	}
//	
	//---- Activation checking ---------------------------------------------------------------------------
	
	public void checkActivation(RefactoringStatus status) {
		status.merge(getStatus());
		checkExpression(status);
		if (status.hasFatalError())
			return;
			
		fReturnKind= UNDEFINED;
		fMaxVariableId= LocalVariableIndex.perform(fEnclosingMethod);
		if (analyzeSelection(status).hasFatalError())
			return;

		int returns= fReturnKind == NO ? 0 : 1;
		if (fReturnValue != null) {
			fReturnKind= ACCESS_TO_LOCAL;
			returns++;
		}
		if (isExpressionSelected()) {
			fReturnKind= EXPRESSION;
			returns++;
		}
			
		if (returns > 1) {
			status.addFatalError("Ambiguous return value: expression, access to local or return statement extracted."); //$NON-NLS-1$
			fReturnKind= MULTIPLE;
		}
	}
	
	private void checkExpression(RefactoringStatus status) {
		ASTNode[] nodes= getSelectedNodes();
		if (nodes != null && nodes.length == 1) {
			ASTNode node= nodes[0];
			if (node instanceof NullLiteral) {
				status.addFatalError("Cannot extract the single keyword null.");
			} else if (node instanceof Type) {
				status.addFatalError("Currently no support to extract a single type reference.");
			}
		}
	}
	
	//---- Input checking -----------------------------------------------------------------------------------
		
	public void checkInput(RefactoringStatus status, String methodName, IJavaProject scope) {
		ITypeBinding[] arguments= getArgumentTypes();
		ITypeBinding type= fEnclosingMethod.resolveBinding().getDeclaringClass();
		status.merge(Checks.checkMethodInType(type, methodName, arguments, scope));
		status.merge(Checks.checkMethodInHierarchy(type.getSuperclass(), methodName, arguments, scope));
	}
	
	private ITypeBinding[] getArgumentTypes() {
		ITypeBinding[] result= new ITypeBinding[fArguments.length];
		for (int i= 0; i < fArguments.length; i++) {
			result[i]= fArguments[i].getType();
		}
		return result;
	}
	
//	private String getMethodName(IMethodBinding binding) {
//		StringBuffer buffer= new StringBuffer();
//		buffer.append(binding.getDeclaringClass().getName());
//		buffer.append('.');
//		buffer.append(binding.getName());
//		return buffer.toString();
//	}

	private RefactoringStatus analyzeSelection(RefactoringStatus status) {
		fInputFlowContext= new FlowContext(0, fMaxVariableId + 1);
		fInputFlowContext.setConsiderAccessMode(true);
		fInputFlowContext.setComputeMode(FlowContext.ARGUMENTS);
		
		InOutFlowAnalyzer flowAnalyzer= new InOutFlowAnalyzer(fInputFlowContext, getSelection());
		fInputFlowInfo= flowAnalyzer.perform(getSelectedNodes());
		
		if (fInputFlowInfo.branches()) {
			status.addFatalError("Selection contains branch statement but corresponding branch target isn't selected.");
			fReturnKind= ERROR;
			return status;
		}
		if (fInputFlowInfo.isValueReturn()) {
			fReturnKind= RETURN_STATEMENT_VALUE;
		} else  if (fInputFlowInfo.isVoidReturn()) {
			fReturnKind= RETURN_STATEMENT_VOID;
		} else if (fInputFlowInfo.isNoReturn() || fInputFlowInfo.isThrow() || fInputFlowInfo.isUndefined()) {
			fReturnKind= NO;
		}
		
		if (fReturnKind == UNDEFINED) {
			status.addFatalError(RefactoringCoreMessages.getString("FlowAnalyzer.execution_flow")); //$NON-NLS-1$
			fReturnKind= ERROR;
			return status;
		}
		computeInput();
		computeExceptions();
		computeOutput(status);
		if (!status.hasFatalError())
			adjustArgumentsAndMethodLocals();
		compressArrays();
		return status;
	}
	
	private void computeInput() {
		int argumentMode= FlowInfo.READ | FlowInfo.READ_POTENTIAL | FlowInfo.WRITE_POTENTIAL | FlowInfo.UNKNOWN;
		fArguments= fInputFlowInfo.get(fInputFlowContext, argumentMode);
		removeSelectedDeclarations(fArguments);
		fMethodLocals= fInputFlowInfo.get(fInputFlowContext, FlowInfo.WRITE | FlowInfo.WRITE_POTENTIAL);
		removeSelectedDeclarations(fMethodLocals);
	}
	
	private void removeSelectedDeclarations(IVariableBinding[] bindings) {
		Selection selection= getSelection();
		for (int i= 0; i < bindings.length; i++) {
			ASTNode decl= ((CompilationUnit)fEnclosingMethod.getRoot()).findDeclaringNode(bindings[i]);
			if (selection.covers(decl))
				bindings[i]= null;
		}
	}
	
	private void computeOutput(RefactoringStatus status) {
		// First find all writes inside the selection.
		FlowContext flowContext= new FlowContext(0, fMaxVariableId + 1);
		flowContext.setConsiderAccessMode(true);
		flowContext.setComputeMode(FlowContext.RETURN_VALUES);
		FlowInfo returnInfo= new InOutFlowAnalyzer(flowContext, getSelection()).perform(getSelectedNodes());
		IVariableBinding[] returnValues= returnInfo.get(flowContext, FlowInfo.WRITE | FlowInfo.WRITE_POTENTIAL | FlowInfo.UNKNOWN);
		
		int counter= 0;
		flowContext.setComputeMode(FlowContext.ARGUMENTS);
		FlowInfo argInfo= new InputFlowAnalyzer(flowContext, getSelection()).perform(fEnclosingMethod);
		IVariableBinding[] reads= argInfo.get(flowContext, FlowInfo.READ | FlowInfo.READ_POTENTIAL | FlowInfo.UNKNOWN);
		outer: for (int i= 0; i < returnValues.length && counter <= 1; i++) {
			IVariableBinding binding= returnValues[i];
			for (int x= 0; x < reads.length; x++) {
				if (reads[x] == binding) {
					counter++;
					fReturnValue= binding;
					continue outer;
				}
			}
		}
		switch (counter) {
			case 0:
				fReturnValue= null;
				break;
			case 1:
				break;
			default:
				fReturnValue= null;
				status.addFatalError(RefactoringCoreMessages.getString("ExtractMethodAnalyzer.assignments_to_local"));
				return;
		}
		List callerLocals= new ArrayList(5);
		IVariableBinding[] writes= argInfo.get(flowContext, FlowInfo.WRITE);
		for (int i= 0; i < writes.length; i++) {
			IVariableBinding write= writes[i];
			if (getSelection().covers(ASTNodes.findDeclaration(write, fEnclosingMethod)))
				callerLocals.add(write);
		}
		fCallerLocals= (IVariableBinding[])callerLocals.toArray(new IVariableBinding[callerLocals.size()]);
		if (fReturnValue != null && getSelection().covers(ASTNodes.findDeclaration(fReturnValue, fEnclosingMethod)))
			fReturnLocal= fReturnValue;
	}
	
	private void adjustArgumentsAndMethodLocals() {
		for (int i= 0; i < fArguments.length; i++) {
			IVariableBinding argument= fArguments[i];
			if (fInputFlowInfo.hasAccessMode(fInputFlowContext, argument, FlowInfo.WRITE_POTENTIAL)) {
				if (argument != fReturnValue)
					fArguments[i]= null;
				// We didn't remove the argument. So we have to remove the local declaration
				if (fArguments[i] != null) {
					for (int l= 0; l < fMethodLocals.length; l++) {
						if (fMethodLocals[l] == argument)
							fMethodLocals[l]= null;						
					}
				}
			}
		}
	}
	
	private void compressArrays() {
		fArguments= compressArray(fArguments);
		fCallerLocals= compressArray(fCallerLocals);
		fMethodLocals= compressArray(fMethodLocals);
	}
	
	private IVariableBinding[] compressArray(IVariableBinding[] array) {
		if (array == null)
			return null;
		int size= 0;
		for (int i= 0; i < array.length; i++) {
			if (array[i] != null)
				size++;	
		}
		if (size == array.length)
			return array;
		IVariableBinding[] result= new IVariableBinding[size];
		for (int i= 0, r= 0; i < array.length; i++) {
			if (array[i] != null)
				result[r++]= array[i];		
		}
		return result;
	}
	
	//---- Change creation ----------------------------------------------------------------------------------
	
	public void aboutToCreateChange() {
	}

	//---- Exceptions -----------------------------------------------------------------------------------------
	
	public ITypeBinding[] getExceptions() {
		return fExceptions;
	}
	
	private void computeExceptions() {
		fExceptions= ExceptionAnalyzer.perform(fEnclosingMethod, getSelectedNodes());
	}
	
	//---- Special visitor methods ---------------------------------------------------------------------------

	protected void handleNextSelectedNode(ASTNode node) {
		checkParent(node);
		super.handleNextSelectedNode(node);
	}
	
	protected void handleSelectionEndsIn(ASTNode node) {
		invalidSelection(RefactoringCoreMessages.getString("StatementAnalyzer.doesNotCover")); //$NON-NLS-1$
		super.handleSelectionEndsIn(node);
	}
		
	private void checkParent(ASTNode node) {
		ASTNode firstParent= getFirstSelectedNode().getParent();
		do {
			node= node.getParent();
			if (node == firstParent)
				return;
		} while (node != null);
		invalidSelection("Not all selected statements are enclosed by the same parent statement.");
	}
	
	public void endVisit(CompilationUnit node) {
		RefactoringStatus status= getStatus();
		superCall: {
			if (status.hasFatalError())
				break superCall;
			if (!hasSelectedNodes()) {
				status.addFatalError(RefactoringCoreMessages.getString("StatementAnalyzer.only_method_body")); //$NON-NLS-1$
				break superCall;
			}
			fEnclosingMethod= (MethodDeclaration)ASTNodes.getParent(getFirstSelectedNode(), MethodDeclaration.class);
			if (fEnclosingMethod == null) {
				status.addFatalError(RefactoringCoreMessages.getString("StatementAnalyzer.only_method_body")); //$NON-NLS-1$
				break superCall;
			}
			status.merge(LocalTypeAnalyzer.perform(fEnclosingMethod, getSelection()));
		}
		super.endVisit(node);
	}
	
	public boolean visit(AnonymousClassDeclaration node) {
		boolean result= super.visit(node);
		if (isFirstSelectedNode(node)) {
			invalidSelection("Cannot extract the body of a anonymous type declaration. Select whole declaration.");
			return false;
		}
		return result;
	}

	public boolean visit(DoStatement node) {
		boolean result= super.visit(node);
		
		int actionStart= getBuffer().indexAfter(Scanner.TokenNamedo, node.getStartPosition());
		if (getSelection().getOffset() == actionStart) {
			invalidSelection(RefactoringCoreMessages.getString("StatementAnalyzer.after_do_keyword")); //$NON-NLS-1$
			return false;
		}
		
		return result;
	}

	public boolean visit(SuperMethodInvocation node) {
		boolean result= super.visit(node);
		if (getSelection().getVisitSelectionMode(node) == Selection.SELECTED) {
			invalidSelection(RefactoringCoreMessages.getString("StatementAnalyzer.super_or_this")); //$NON-NLS-1$
			return false;
		}
		return result;
	}
	
	public boolean visit(VariableDeclarationFragment node) {
		boolean result= super.visit(node);
		if (isFirstSelectedNode(node)) {
			invalidSelection("Cannot extract a variable declaration fragment. Select whole declaration statement.");
			return false;
		}
		return result;
	}
	
	public void endVisit(ForStatement node) {
		if (getSelection().getEndVisitSelectionMode(node) == Selection.AFTER) {
			if (node.initializers().contains(getFirstSelectedNode())) {
				invalidSelection("Cannot extract initialization part of a for statement.");
			} else if (node.updaters().contains(getLastSelectedNode())) {
				invalidSelection("Cannot extract increment part of a for statement.");
			}
		}
		super.endVisit(node);
	}		
	
	public void endVisit(VariableDeclarationExpression node) {
		checkTypeInDeclaration(node.getType());
		super.endVisit(node);		
	}
			
	public void endVisit(VariableDeclarationStatement node) {
		checkTypeInDeclaration(node.getType());
		super.endVisit(node);		
	}
			
	private boolean isFirstSelectedNode(ASTNode node) {
		return getSelection().getVisitSelectionMode(node) == Selection.SELECTED && getFirstSelectedNode() == node;
	}
	
	private void checkTypeInDeclaration(Type node) {
		if (getSelection().getEndVisitSelectionMode(node) == Selection.SELECTED && getFirstSelectedNode() == node) {
			invalidSelection("Cannot extract parts of a variable declaration. Select whole declaration.");
		}
	}
}

