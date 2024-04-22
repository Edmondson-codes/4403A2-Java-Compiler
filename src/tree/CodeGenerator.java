package tree;

import java.util.*;

import machine.Operation;
import machine.StackMachine;
import source.Errors;
import source.VisitorDebugger;
import syms.Predefined;
import syms.SymEntry;
import syms.Type;
import tree.StatementNode.*;

/**
 * class CodeGenerator implements code generation using the
 * visitor pattern to traverse the abstract syntax tree.
 */
public class CodeGenerator implements DeclVisitor, StatementTransform<Code>,
        ExpTransform<Code> {
    /**
     * Current static level of nesting into procedures.
     */
    private int staticLevel;

    /**
     * Table of code for each procedure
     */
    private final Procedures procedures;

    /**
     * Error message handler
     */
    private final Errors errors;

    /**
     * Debug messages are reported through the visitor debugger.
     */
    private final VisitorDebugger debug;


    public CodeGenerator(Errors errors) {
        super();
        this.errors = errors;
        debug = new VisitorDebugger("generating", errors);
        procedures = new Procedures();
    }

    /**
     * Main generate code method for the program.
     */
    public Procedures generateCode(DeclNode.ProcedureNode node) {
        beginGen("Program");
        staticLevel = node.getBlock().getBlockLocals().getLevel();
        assert staticLevel == 1;  // Main program is at static level 1
        /* Generate the code for the main program and all procedures */
        visitProcedureNode(node);
        endGen("Program");
        return procedures;
    }

    /* -------------------- Visitor methods ----------------------------*/

    /**
     * Generate code for a single procedure.
     */
    public void visitProcedureNode(DeclNode.ProcedureNode node) {
        beginGen("Procedure");
        // Generate code for the block
        Code code = visitBlockNode(node.getBlock());
            code.generateOp(Operation.RETURN);
        procedures.addProcedure(node.getProcEntry(), code);
        //System.out.println(node.getProcEntry().getIdent() + "\n" + code);
        endGen("Procedure");
    }

    /**
     * Generate code for a block.
     */
    public Code visitBlockNode(BlockNode node) {
        beginGen("Block");
        /* Generate code to allocate space for local variables on
         * procedure entry.
         */
        Code code = new Code();
        code.genAllocStack(node.getBlockLocals().getVariableSpace());
        /* Generate the code for the body */
        code.append(node.getBody().genCode(this));
        /* Generate code for local procedures.
         * Static level is one greater for the procedures.
         */
        staticLevel++;
        node.getProcedures().accept(this);
        staticLevel--;
        endGen("Block");
        return code;
    }

    /**
     * Code generation for a list of procedures
     */
    public void visitDeclListNode(DeclNode.DeclListNode node) {
        beginGen("DeclList");
        for (DeclNode decl : node.getDeclarations()) {
            decl.accept(this);
        }
        endGen("DeclList");
    }


    //**************  Statement node code generation visit methods

    /**
     * Code generation for an erroneous statement should not be attempted.
     */
    public Code visitStatementErrorNode(StatementNode.ErrorNode node) {
        errors.fatal("PL0 Internal error: generateCode for Statement Error Node",
                node.getLocation());
        return null;
    }


    /**
     * Code generation for an assignment statement.
     */
    public Code visitAssignmentNode(StatementNode.AssignmentNode node) {
        beginGen("Assignment");
        Code code = new Code();
        code.genComment("assignment to " + node.getVariable() + ":");
        /* Generate code to evaluate the expression */
        code.append(node.getExp().genCode(this));
        /* Generate the code to load the address of the variable */
        code.append(node.getVariable().genCode(this));
        /* Generate the store based on the type/size of value */
        code.genStore(node.getExp().getType());
        endGen("Assignment");
        return code;
    }

    /**
     * Generate code for a "read" statement.
     */
    public Code visitReadNode(StatementNode.ReadNode node) {
        beginGen("Read");
        Code code = new Code();
        code.genComment("read to " + node.getLValue() + ":");
        /* Read an integer from standard input */
        code.generateOp(Operation.READ);
        /* Generate the code to load the address of the LValue */
        code.append(node.getLValue().genCode(this));
        /* Generate the store based on the type/size of value */
        code.genStore(node.getLValue().getType().optDereferenceType());
        endGen("Read");
        return code;
    }

    /**
     * Generate code for a "write" statement.
     */
    public Code visitWriteNode(StatementNode.WriteNode node) {
        beginGen("Write");
        Code code = new Code();
        code.genComment("write " + node.getExp() + ":");
        code.append(node.getExp().genCode(this));
        code.generateOp(Operation.WRITE);
        endGen("Write");
        return code;
    }

    /**
     * Generate code for a "call" statement.
     */
    public Code visitCallNode(StatementNode.CallNode node) {
        beginGen("Call");
        SymEntry.ProcedureEntry proc = node.getEntry();
        Code code = new Code();
        code.genComment("call " + proc.getIdent() + ":");
        /* Generate the call instruction. The second parameter is the
         * procedure's symbol table entry. The actual address is resolved
         * at load time.
         */
        code.genCall(staticLevel - proc.getLevel(), proc);
        endGen("Call");
        return code;
    }

    /**
     * Generate code for a statement list
     */
    public Code visitStatementListNode(StatementNode.ListNode node) {
        beginGen("StatementList");
        Code code = new Code();
        for (StatementNode s : node.getStatements()) {
            code.append(s.genCode(this));
        }
        endGen("StatementList");
        return code;
    }

    /**
     * Generate code for an "if" statement.
     */
    public Code visitIfNode(StatementNode.IfNode node) {
        beginGen("If");
        Code code = new Code();
        code.genComment("if " + node.getCondition() + ":");
        /* Generate the code for the if-then-else
         * from the code for its components */
        code.genIfThenElse(node.getCondition().genCode(this),
                node.getThenStmt().genCode(this),
                node.getElseStmt().genCode(this));
        endGen("If");
        return code;
    }

    /**
     * Generate code for a "while" statement.
     */
    public Code visitWhileNode(StatementNode.WhileNode node) {
        beginGen("While");
        Code code = new Code();
        code.genComment("while " + node.getCondition() + ":");
        /* Generate the code to evaluate the condition. */
        code.append(node.getCondition().genCode(this));
        /* Generate the code for the loop body */
        Code bodyCode = node.getLoopStmt().genCode(this);
        /* Add a branch over the loop body on false.
         * The offset is the size of the loop body code plus
         * the size of the branch to follow the body.
         */
        code.genJumpIfFalse(bodyCode.size() + Code.SIZE_JUMP_ALWAYS);
        /* Append the code for the body */
        code.append(bodyCode);
        /* Add a branch back to the condition.
         * The offset is the total size of the current code plus the
         * size of a Jump Always (being generated).
         */
        code.genJumpAlways(-(code.size() + Code.SIZE_JUMP_ALWAYS));
        endGen("While");
        return code;
    }
    //************* Expression node code generation visit methods

    /**
     * Code generation for an erroneous expression should not be attempted.
     */
    public Code visitErrorExpNode(ExpNode.ErrorNode node) {
        errors.fatal("PL0 Internal error: generateCode for ErrorExpNode",
                node.getLocation());
        return null;
    }

    /**
     * Generate code for a constant expression.
     */
    public Code visitConstNode(ExpNode.ConstNode node) {
        beginGen("Const");
        Code code = new Code();
        if (node.getValue() == 0) {
            code.generateOp(Operation.ZERO);
        } else if (node.getValue() == 1) {
            code.generateOp(Operation.ONE);
        } else {
            code.genLoadConstant(node.getValue());
        }
        endGen("Const");
        return code;
    }

    // TODO
    public Code visitArrayOfType(ExpNode.ArrayOfType node) {
        beginGen("Array of Type");

        // create

        endGen("Array of Type");
        return null;
    }

    // TODO
    public Code visitArrayAccess(ExpNode.ArrayAccess node) {
        beginGen("Array Access");

        Code code = new Code();

        ExpNode id = node.getIdentifier();
        ExpNode cond = node.getCond();
        ExpNode lvalue = node.getLvalue();

        // if LValue "[" Condition "]" go to heap and access specified index
        if(id == null && cond != null){
            // access the item at the Condition-th index in the array

        }
        // if LValue.length get length
        else if (id != null && cond == null) {
            // return array length

            // put address on top of stack
            code.gen;



            // get length at address
            code.genLoad(node.getType());

        } else {
            // handled by static checker
        }

        endGen("Array Access");
        return null;
    }

    // TODO: check
    @Override
    public Code visitArrayNode(ExpNode.ArrayNode node) {
        // stores the length at the heap address pointed to by stack, then elements after that
        beginGen("Array Node");

        ExpNode length = node.getLength();
        ExpNode id = node.getIdentifier();

        Code code = new Code();

        // put length of array on stack so will be stored at location with genStore
        code.genLoadConstant(((ExpNode.ConstNode) length).getValue());

        // allocate space on heap for array
        code.genLoadConstant(((ExpNode.ConstNode) length).getValue() * ((ExpNode.ArrayOfType) id).getItemType().getSpace() );  // store list size on top of stack for ALLOC_HEAP
        code.generateOp(Operation.ALLOC_HEAP);  // Allocate the space for list on heap

        // so now the absolute address is on the top of the stack and we need to save it
        // convert absolute address to local
        code.generateOp(Operation.TO_LOCAL);

        // store pointer to heap address in the stack frame
        code.genStore(node.getType());

        endGen("Array Node");
        return null;
    }

    /**
     * Generate operator operands in order
     */
    private Code genArgs(ExpNode left, ExpNode right) {
        beginGen("ArgsInOrder");
        Code code = left.genCode(this);
        code.append(right.genCode(this));
        endGen("ArgsInOrder");
        return code;
    }

    /**
     * Generate code for a binary operator expression.
     */
    public Code visitBinaryNode(ExpNode.BinaryNode node) {
        beginGen("Binary");
        Code code;
        ExpNode left = node.getLeft();
        ExpNode right = node.getRight();
        switch (node.getOp()) {
            case ADD_OP -> {
                code = genArgs(left, right);
                code.generateOp(Operation.ADD);
            }
            case SUB_OP -> {
                code = genArgs(left, right);
                code.generateOp(Operation.NEGATE);
                code.generateOp(Operation.ADD);
                }
            case MUL_OP -> {
                code = genArgs(left, right);
                code.generateOp(Operation.MPY);
            }
            case DIV_OP -> {
                code = genArgs(left, right);
                code.generateOp(Operation.DIV);
            }
            case EQUALS_OP -> {
                code = genArgs(left, right);
                code.generateOp(Operation.EQUAL);
            }
            case LESS_OP -> {
                code = genArgs(left, right);
                code.generateOp(Operation.LESS);
            }
            case NEQUALS_OP -> {
                code = genArgs(left, right);
                code.generateOp(Operation.EQUAL);
                code.genBoolNot();
            }
            case LEQUALS_OP -> {
                code = genArgs(left, right);
                code.generateOp(Operation.LESSEQ);
            }
            case GREATER_OP -> {
                /* Generate argument values in reverse order and use LESS */
                code = genArgs(right, left);
                code.generateOp(Operation.LESS);
            }
            case GEQUALS_OP -> {
                /* Generate argument values in reverse order and use LESSEQ */
                code = genArgs(right, left);
                code.generateOp(Operation.LESSEQ);
            }
            default -> {
                errors.fatal("PL0 Internal error: Unknown operator", node.getLocation());
                code = null;
            }
        }
        endGen("Binary");
        return code;
    }
    /**
     * Generate code for a unary operator expression.
     */
    public Code visitUnaryNode(ExpNode.UnaryNode node) {
        beginGen("Unary");
        Code code = node.getArg().genCode(this);
        switch (node.getOp()) {
            case NEG_OP ->
                code.generateOp(Operation.NEGATE);
            default ->
                errors.fatal("PL0 Internal error: Unknown operator", node.getLocation());
        }
        endGen("Unary");
        return code;
    }

    /**
     * Generate code to dereference an RValue.
     */
    public Code visitDereferenceNode(ExpNode.DereferenceNode node) {
        beginGen("Dereference");
        Code code = node.getLeftValue().genCode(this);
        code.genLoad(node.getType());
        endGen("Dereference");
        return code;
    }

    /**
     * Generating code for an IdentifierNode is invalid because the
     * static checker should have converted all IdentifierNodes to
     * either ConstNodes or VariableNodes.
     */
    public Code visitIdentifierNode(ExpNode.IdentifierNode node) {
        errors.fatal("Internal error: code generator called on IdentifierNode",
                node.getLocation());
        return null;
    }

    /**
     * Generate code for a variable reference.
     * It pushes the address of the variable as an offset from the frame pointer
     */
    public Code visitVariableNode(ExpNode.VariableNode node) {
        beginGen("Variable");
        SymEntry.VarEntry var = node.getVariable();
        Code code = new Code();
        code.genMemRef(staticLevel - var.getLevel(), var.getOffset());
        endGen("Variable");
        return code;
    }


    /**
     * Generate code to perform a bounds check on a sub-range.
     */
    public Code visitNarrowSubrangeNode(ExpNode.NarrowSubrangeNode node) {
        beginGen("NarrowSubrange");
        Code code = node.getExp().genCode(this);
        code.genBoundsCheck(node.getSubrangeType().getLower(),
                node.getSubrangeType().getUpper());
        endGen("NarrowSubrange");
        return code;
    }

    /**
     * Generate code to widen a sub-range to an integer.
     */
    public Code visitWidenSubrangeNode(ExpNode.WidenSubrangeNode node) {
        beginGen("WidenSubrange");
        /* Widening doesn't require anything extra other than
         * generating code for its expression.
         */
        Code code = node.getExp().genCode(this);
        endGen("WidenSubrange");
        return code;
    }
    //**************************** Support Methods

    /**
     * Push current node onto debug rule stack and increase debug level
     */
    private void beginGen(String nodeName) {
        debug.beginDebug(nodeName);
    }

    /**
     * Pop current node from debug rule stack and decrease debug level
     */
    private void endGen(String node) {
        debug.endDebug(node);
    }

}
