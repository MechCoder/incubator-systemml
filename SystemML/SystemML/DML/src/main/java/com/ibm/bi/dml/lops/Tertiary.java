/**
 * IBM Confidential
 * OCO Source Materials
 * (C) Copyright IBM Corp. 2010, 2014
 * The source code for this program is not published or otherwise divested of its trade secrets, irrespective of what has been deposited with the U.S. Copyright Office.
 */

package com.ibm.bi.dml.lops;

import com.ibm.bi.dml.lops.LopProperties.ExecLocation;
import com.ibm.bi.dml.lops.LopProperties.ExecType;
import com.ibm.bi.dml.lops.compile.JobType;
import com.ibm.bi.dml.parser.Expression.*;


/**
 * Lop to perform tertiary operation. All inputs must be matrices or vectors. 
 * For example, this lop is used in evaluating A = ctable(B,C,W)
 * 
 * Currently, this lop is used only in case of CTABLE functionality.
 */

public class Tertiary extends Lop 
{
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2013\n" +
                                             "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";
	
	public enum OperationTypes { CTABLE_TRANSFORM, CTABLE_TRANSFORM_SCALAR_WEIGHT, CTABLE_TRANSFORM_HISTOGRAM, CTABLE_TRANSFORM_WEIGHTED_HISTOGRAM, INVALID };	
	OperationTypes operation;
	

	
	/**
	 * Constructor to perform a binary operation.
	 * @param input
	 * @param op
	 */

	public Tertiary(Lop input1, Lop input2, Lop input3, OperationTypes op, DataType dt, ValueType vt) 
	{
		super(Lop.Type.Tertiary, dt, vt);
		init(input1, input2, input3, op, ExecType.MR);
	}
	
	public Tertiary(Lop input1, Lop input2, Lop input3, OperationTypes op, DataType dt, ValueType vt, ExecType et) 
	{
		super(Lop.Type.Tertiary, dt, vt);
		init(input1, input2, input3, op, et);
	}
	
	private void init (Lop input1, Lop input2, Lop input3, OperationTypes op, ExecType et) 
	{
		operation = op;
		this.addInput(input1);
		this.addInput(input2);
		this.addInput(input3);
		input1.addOutput(this);
		input2.addOutput(this);
		input3.addOutput(this);
		
		boolean breaksAlignment = true;
		boolean aligner = false;
		boolean definesMRJob = false;
		
		if ( et == ExecType.MR ) {
			/*
			 *  This lop can be executed in GMR, RAND, REBLOCK jobs
			 */
			lps.addCompatibility(JobType.GMR);
			lps.addCompatibility(JobType.DATAGEN);
			lps.addCompatibility(JobType.REBLOCK);
			
			this.lps.setProperties( inputs, et, ExecLocation.Reduce, breaksAlignment, aligner, definesMRJob );
		}
		else {
			lps.addCompatibility(JobType.INVALID);
			this.lps.setProperties( inputs, et, ExecLocation.ControlProgram, breaksAlignment, aligner, definesMRJob );
		}
	}

	@Override
	public String toString() {
	
		return " Operation: " + operation;

	}

	public static OperationTypes findCtableOperationByInputDataTypes(DataType dt1, DataType dt2, DataType dt3) {
		if ( dt1 == DataType.MATRIX ) {
			if (dt2 == DataType.MATRIX && dt3 == DataType.SCALAR) {
				// F = ctable(A,B) or F = ctable(A,B,1)
				return OperationTypes.CTABLE_TRANSFORM_SCALAR_WEIGHT;
			} else if (dt2 == DataType.SCALAR && dt3 == DataType.SCALAR) {
				// F=ctable(A,1) or F = ctable(A,1,1)
				return OperationTypes.CTABLE_TRANSFORM_HISTOGRAM;
			} else if (dt2 == DataType.SCALAR && dt3 == DataType.MATRIX) {
				// F=ctable(A,1,W)
				return OperationTypes.CTABLE_TRANSFORM_WEIGHTED_HISTOGRAM;
			} else {
				// F=ctable(A,B,W)
				return OperationTypes.CTABLE_TRANSFORM;
			}
		}
		else {
			return OperationTypes.INVALID;
		}
	}

	/**
	 * method to get operation type
	 * @return
	 */
	 
	public OperationTypes getOperationType()
	{
		return operation;
	}

	@Override
	public String getInstructions(String input1, String input2, String input3, String output) throws LopsException
	{
		StringBuilder sb = new StringBuilder();
		sb.append( getExecType() );
		sb.append( Lop.OPERAND_DELIMITOR );
		sb.append( "ctable" );
		sb.append( OPERAND_DELIMITOR );
		
		if ( getInputs().get(0).get_dataType() == DataType.SCALAR ) {
			sb.append ( getInputs().get(0).prepScalarInputOperand(getExecType()) );
		}
		else {
			sb.append( getInputs().get(0).prepInputOperand(input1));
		}
		sb.append( OPERAND_DELIMITOR );
		
		if ( getInputs().get(1).get_dataType() == DataType.SCALAR ) {
			sb.append ( getInputs().get(1).prepScalarInputOperand(getExecType()) );
		}
		else {
			sb.append( getInputs().get(1).prepInputOperand(input2));
		}
		sb.append( OPERAND_DELIMITOR );
		
		if ( getInputs().get(2).get_dataType() == DataType.SCALAR ) {
			sb.append ( getInputs().get(2).prepScalarInputOperand(getExecType()) );
		}
		else {
			sb.append( getInputs().get(2).prepInputOperand(input3));
		}
		sb.append( OPERAND_DELIMITOR );
		
		sb.append( this.prepOutputOperand(output));
		
		return sb.toString();
	}

	@Override
	public String getInstructions(int input_index1, int input_index2, int input_index3, int output_index) throws LopsException
	{
		StringBuilder sb = new StringBuilder();
		sb.append( getExecType() );
		sb.append( Lop.OPERAND_DELIMITOR );
		switch(operation) {
		/* Arithmetic */
		case CTABLE_TRANSFORM:
			// F = ctable(A,B,W)
			sb.append( "ctabletransform" );
			sb.append( OPERAND_DELIMITOR );
			sb.append( getInputs().get(0).prepInputOperand(input_index1));
			sb.append( OPERAND_DELIMITOR );
			sb.append( getInputs().get(1).prepInputOperand(input_index2));
			sb.append( OPERAND_DELIMITOR );
			sb.append( getInputs().get(2).prepInputOperand(input_index3));
			sb.append( OPERAND_DELIMITOR );
			sb.append( this.prepOutputOperand(output_index));
			
			break;
		
		case CTABLE_TRANSFORM_SCALAR_WEIGHT:
			// F = ctable(A,B) or F = ctable(A,B,1)
			// third input must be a scalar, and hence input_index3 == -1
			if ( input_index3 != -1 ) {
				throw new LopsException(this.printErrorLocation() + "In Tertiary Lop, Unexpected input while computing the instructions for op: " + operation + " \n");
			}
			
			// parse the third input (scalar)
			// if it is a literal, copy val, else surround with the label with
			// ## symbols. these will be replaced at runtime.
			
			int scalarIndex = 2; // index of the scalar input
			
			sb.append( "ctabletransformscalarweight" );
			sb.append( OPERAND_DELIMITOR );
			
			sb.append( getInputs().get(0).prepInputOperand(input_index1));
			sb.append( OPERAND_DELIMITOR );
			
			sb.append( getInputs().get(1).prepInputOperand(input_index2));
			sb.append( OPERAND_DELIMITOR );
			
			sb.append( getInputs().get(scalarIndex).prepScalarInputOperand(getExecType()));
			sb.append( OPERAND_DELIMITOR );
			
			sb.append( this.prepOutputOperand(output_index));
			
			break;
			
		case CTABLE_TRANSFORM_HISTOGRAM:
			// F=ctable(A,1) or F = ctable(A,1,1)
			if ( input_index2 != -1 || input_index3 != -1)
				throw new LopsException(this.printErrorLocation() + "In Tertiary Lop, Unexpected input while computing the instructions for op: " + operation);
			
			// 2nd and 3rd inputs are scalar inputs 
			
			sb.append( "ctabletransformhistogram" );
			sb.append( OPERAND_DELIMITOR );
			
			sb.append( getInputs().get(0).prepInputOperand(input_index1));
			sb.append( OPERAND_DELIMITOR );
			
			sb.append( getInputs().get(1).prepScalarInputOperand(getExecType()) );
			sb.append( OPERAND_DELIMITOR );
			
			sb.append( getInputs().get(2).prepScalarInputOperand(getExecType()) );
			sb.append( OPERAND_DELIMITOR );
			
			sb.append( this.prepOutputOperand(output_index));
			
			break;
		
		case CTABLE_TRANSFORM_WEIGHTED_HISTOGRAM:
			// F=ctable(A,1,W)
			if ( input_index2 != -1 )
				throw new LopsException(this.printErrorLocation() + "In Tertiary Lop, Unexpected input while computing the instructions for op: " + operation);
			
			// 2nd input is the scalar input
			
			sb.append( "ctabletransformweightedhistogram" );
			sb.append( OPERAND_DELIMITOR );
			
			sb.append( getInputs().get(0).prepInputOperand(input_index1));
			sb.append( OPERAND_DELIMITOR );
			
			sb.append( getInputs().get(1).prepScalarInputOperand(getExecType()));
			sb.append( OPERAND_DELIMITOR );
			
			sb.append( getInputs().get(2).prepInputOperand(input_index3));
			sb.append( OPERAND_DELIMITOR );
			
			sb.append( this.prepOutputOperand(output_index));
			
			break;
			
		default:
			throw new UnsupportedOperationException(this.printErrorLocation() + "Instruction is not defined for Tertiary operation: " + operation);
		}
		
		return sb.toString();
	}

 
}