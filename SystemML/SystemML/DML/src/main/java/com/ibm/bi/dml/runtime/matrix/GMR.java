/**
 * IBM Confidential
 * OCO Source Materials
 * (C) Copyright IBM Corp. 2010, 2014
 * The source code for this program is not published or otherwise divested of its trade secrets, irrespective of what has been deposited with the U.S. Copyright Office.
 */


package com.ibm.bi.dml.runtime.matrix;

import java.util.ArrayList;
import java.util.HashSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RunningJob;
import org.apache.hadoop.mapred.Counters.Group;

import com.ibm.bi.dml.conf.ConfigurationManager;
import com.ibm.bi.dml.conf.DMLConfig;
import com.ibm.bi.dml.lops.AppendM;
import com.ibm.bi.dml.lops.PartialAggregate.CorrectionLocationType;
import com.ibm.bi.dml.lops.compile.JobType;
import com.ibm.bi.dml.lops.runtime.RunMRJobs;
import com.ibm.bi.dml.lops.runtime.RunMRJobs.ExecMode;
import com.ibm.bi.dml.parser.Expression.DataType;
import com.ibm.bi.dml.parser.Expression.ValueType;
import com.ibm.bi.dml.runtime.controlprogram.ParForProgramBlock.PDataPartitionFormat;
import com.ibm.bi.dml.runtime.instructions.Instruction;
import com.ibm.bi.dml.runtime.instructions.InstructionUtils;
import com.ibm.bi.dml.runtime.instructions.MRJobInstruction;
import com.ibm.bi.dml.runtime.instructions.MRInstructions.PickByCountInstruction;
import com.ibm.bi.dml.runtime.matrix.io.InputInfo;
import com.ibm.bi.dml.runtime.matrix.io.MatrixIndexes;
import com.ibm.bi.dml.runtime.matrix.io.NumItemsByEachReducerMetaData;
import com.ibm.bi.dml.runtime.matrix.io.OutputInfo;
import com.ibm.bi.dml.runtime.matrix.io.TaggedMatrixBlock;
import com.ibm.bi.dml.runtime.matrix.io.TaggedMatrixPackedCell;
import com.ibm.bi.dml.runtime.matrix.mapred.GMRCombiner;
import com.ibm.bi.dml.runtime.matrix.mapred.GMRMapper;
import com.ibm.bi.dml.runtime.matrix.mapred.GMRReducer;
import com.ibm.bi.dml.runtime.matrix.mapred.MRBaseForCommonInstructions;
import com.ibm.bi.dml.runtime.matrix.mapred.MRJobConfiguration;
import com.ibm.bi.dml.runtime.matrix.mapred.MRJobConfiguration.ConvertTarget;
import com.ibm.bi.dml.runtime.matrix.mapred.MRJobConfiguration.MatrixChar_N_ReducerGroups;
import com.ibm.bi.dml.runtime.matrix.sort.PickFromCompactInputFormat;
import com.ibm.bi.dml.runtime.util.MapReduceTool;
import com.ibm.bi.dml.runtime.util.UtilFunctions;

 
public class GMR
{
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2014\n" +
	                                         "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";
		
	/*
	 * inBlockRepresentation: indicate whether to use block representation or cell representation
	 * inputs: input matrices, the inputs are indexed by 0, 1, 2, .. based on the position in this string
	 * inputInfos: the input format information for the input matrices
	 * rlen: the number of rows for each matrix
	 * clen: the number of columns for each matrix
	 * brlen: the number of rows per block
	 * bclen: the number of columns per block
	 * instructionsInMapper: in Mapper, the set of unary operations that need to be performed on each input matrix
	 * aggInstructionsInReducer: in Reducer, right after sorting, the set of aggreagte operations that need 
	 * 							to be performed on each input matrix, 
	 * otherInstructionsInReducer: the mixed operations that need to be performed on matrices after the aggregate operations
	 * numReducers: the number of reducers
	 * replication: the replication factor for the output
	 * resulltIndexes: the indexes of the result matrices that needs to be outputted.
	 * outputs: the names for the output directories, one for each result index
	 * outputInfos: output format information for the output matrices
	 */
	private static final Log LOG = LogFactory.getLog(GMR.class.getName());
	
	private static void setupDistributedCache(JobConf job, String instructionsInMapper, String[] inputs, long[] rlens, long[] clens) {
		if ( instructionsInMapper != null && instructionsInMapper != "" && InstructionUtils.isDistributedCacheUsed(instructionsInMapper) ) {
			String indexString = ""; // input indices to be placed in Distributed Cache (concatenated) 
			String pathString = "";  // input paths to be placed in Distributed Cache (concatenated) 
			ArrayList<String> pathList = new ArrayList<String>(); // list of paths to be placed in Distributed cache
			
			byte index;
			String[] inst = instructionsInMapper.split(Instruction.INSTRUCTION_DELIM);
			for(int i=0; i < inst.length; i++) {
				if ( inst[i].contains("mvmult") || inst[i].contains(AppendM.OPCODE) ) {
					// example: MR.mvmult.0.1.2
					
					// Determine the index that points to a vector
					byte in1 = Byte.parseByte(inst[i].split(Instruction.OPERAND_DELIM)[2].split(Instruction.DATATYPE_PREFIX)[0]);
					byte in2 = Byte.parseByte(inst[i].split(Instruction.OPERAND_DELIM)[3].split(Instruction.DATATYPE_PREFIX)[0]);
					//TODO: need to handle vector-matrix case!
					//if ( rlens[in1] == 1 || clens[in1] == 1 )
					//	index = in1; // input1 is a vector
					//else 
						index = in2; // input2 is a vector
					//index = Byte.parseByte(inst[i].split(Instruction.OPERAND_DELIM)[3].split(Instruction.DATATYPE_PREFIX)[0]);
					
					if ( !pathList.contains(index) ) {
						pathList.add(inputs[index]);
						if ( indexString.equalsIgnoreCase("") ) {
							indexString += index;
							pathString += inputs[index];
						} 
						else {
							indexString += Instruction.INSTRUCTION_DELIM + index;
							pathString += Instruction.INSTRUCTION_DELIM + inputs[index];
						}
					}
				}
			}
			
			MRJobConfiguration.setupDistCacheInputs(job, indexString, pathString, pathList);
			
			//clean in-memory cache (prevent job interference in local mode)
			if( MRJobConfiguration.isLocalJobTracker(job) )
				MRBaseForCommonInstructions.resetDistCache();
		}
	}
	
	@SuppressWarnings("unchecked")
	public static JobReturn runJob(MRJobInstruction inst, String[] inputs, InputInfo[] inputInfos, long[] rlens, long[] clens, 
			int[] brlens, int[] bclens, 
			boolean[] partitioned, PDataPartitionFormat[] pformats, int[] psizes,
			String recordReaderInstruction, String instructionsInMapper, String aggInstructionsInReducer, 
			String otherInstructionsInReducer, int numReducers, int replication, boolean jvmReuse, byte[] resultIndexes, String dimsUnknownFilePrefix, 
			String[] outputs, OutputInfo[] outputInfos) 
	throws Exception
	{
		JobConf job;
		job = new JobConf(GMR.class);
		job.setJobName("G-MR");
		
		boolean inBlockRepresentation=MRJobConfiguration.deriveRepresentation(inputInfos);

		//whether use block representation or cell representation
		MRJobConfiguration.setMatrixValueClass(job, inBlockRepresentation);
	
		//added for handling recordreader instruction
		String[] realinputs=inputs;
		InputInfo[] realinputInfos=inputInfos;
		long[] realrlens=rlens;
		long[] realclens=clens;
		int[] realbrlens=brlens;
		int[] realbclens=bclens;
		byte[] realIndexes=new byte[inputs.length];
		for(byte b=0; b<realIndexes.length; b++)
			realIndexes[b]=b;
		
		if(recordReaderInstruction!=null && !recordReaderInstruction.isEmpty())
		{
			assert(inputs.length<=2);
			PickByCountInstruction ins=(PickByCountInstruction) PickByCountInstruction.parseInstruction(recordReaderInstruction);
			PickFromCompactInputFormat.setKeyValueClasses(job, (Class<? extends WritableComparable>) inputInfos[ins.input1].inputKeyClass, 
					inputInfos[ins.input1].inputValueClass);
		    job.setInputFormat(PickFromCompactInputFormat.class);
		    PickFromCompactInputFormat.setZeroValues(job, (NumItemsByEachReducerMetaData)inputInfos[ins.input1].metadata);
		    
			if(ins.isValuePick)
			{
				double[] probs=MapReduceTool.readColumnVectorFromHDFS(inputs[ins.input2], inputInfos[ins.input2], rlens[ins.input2], 
						clens[ins.input2], brlens[ins.input2], bclens[ins.input2]);
			    PickFromCompactInputFormat.setPickRecordsInEachPartFile(job, (NumItemsByEachReducerMetaData) inputInfos[ins.input1].metadata, probs);
			    
			    realinputs=new String[inputs.length-1];
				realinputInfos=new InputInfo[inputs.length-1];
				realrlens=new long[inputs.length-1];
				realclens=new long[inputs.length-1];
				realbrlens=new int[inputs.length-1];
				realbclens=new int[inputs.length-1];
				realIndexes=new byte[inputs.length-1];
				byte realIndex=0;
				for(byte i=0; i<inputs.length; i++)
				{
					if(i==ins.input2)
						continue;
					realinputs[realIndex]=inputs[i];
					realinputInfos[realIndex]=inputInfos[i];
					if(i==ins.input1)
					{
						realrlens[realIndex]=rlens[ins.input2];
						realclens[realIndex]=clens[ins.input2];
						realbrlens[realIndex]=1;
						realbclens[realIndex]=1;
						realIndexes[realIndex]=ins.output;
					}else
					{	
						realrlens[realIndex]=rlens[i];
						realclens[realIndex]=clens[i];
						realbrlens[realIndex]=brlens[i];
						realbclens[realIndex]=bclens[i];
						realIndexes[realIndex]=i;
					}
					realIndex++;
				}
				
			}else
			{
			    PickFromCompactInputFormat.setPickRecordsInEachPartFile(job, (NumItemsByEachReducerMetaData) inputInfos[ins.input1].metadata, ins.cst, 1-ins.cst);
			    realrlens[ins.input1]=UtilFunctions.getLengthForInterQuantile((NumItemsByEachReducerMetaData)inputInfos[ins.input1].metadata, ins.cst);
				realclens[ins.input1]=clens[ins.input1];
				realbrlens[ins.input1]=1;
				realbclens[ins.input1]=1;
				realIndexes[ins.input1]=ins.output;
			}
		}
		
		setupDistributedCache(job, instructionsInMapper, realinputs, realrlens, realclens);

		//set up the input files and their format information
		MRJobConfiguration.setUpMultipleInputs(job, realIndexes, realinputs, realinputInfos, realbrlens, realbclens, 
				true, inBlockRepresentation? ConvertTarget.BLOCK: ConvertTarget.CELL);
		MRJobConfiguration.setInputPartitioningInfo(job, partitioned, pformats, psizes);
		
		//set up the dimensions of input matrices
		MRJobConfiguration.setMatricesDimensions(job, realIndexes, realrlens, realclens);
		MRJobConfiguration.setDimsUnknownFilePrefix(job, dimsUnknownFilePrefix);

		//set up the block size
		MRJobConfiguration.setBlocksSizes(job, realIndexes, realbrlens, realbclens);
		
		//set up unary instructions that will perform in the mapper
		MRJobConfiguration.setInstructionsInMapper(job, instructionsInMapper);
		
		//set up the aggregate instructions that will happen in the combiner and reducer
		MRJobConfiguration.setAggregateInstructions(job, aggInstructionsInReducer);
		
		//set up the instructions that will happen in the reducer, after the aggregation instrucions
		MRJobConfiguration.setInstructionsInReducer(job, otherInstructionsInReducer);
		
		//set up the replication factor for the results
		job.setInt("dfs.replication", replication);

		//set up jvm reuse (incl. reuse of loaded dist cache matrices)
		if( jvmReuse )
			job.setNumTasksToExecutePerJvm(-1);
		
		
		//System.out.println("GMR --> setting blocksize = "+ DMLTranslator.DMLBlockSize);
		/* TODO MP
		if(realbrlens == null || realbrlens.length == 0) {
			job.setInt("DMLBlockSize", DMLTranslator.DMLBlockSize);
		}else {
			job.setInt("DMLBlockSize", realbrlens[0]);
		}*/
		
		//set up what matrices are needed to pass from the mapper to reducer
		HashSet<Byte> mapoutputIndexes=MRJobConfiguration.setUpOutputIndexesForMapper(job, realIndexes, instructionsInMapper, aggInstructionsInReducer, 
				otherInstructionsInReducer, resultIndexes);
		
		MatrixChar_N_ReducerGroups ret=MRJobConfiguration.computeMatrixCharacteristics(job, realIndexes, 
				instructionsInMapper, aggInstructionsInReducer, null, otherInstructionsInReducer, resultIndexes, mapoutputIndexes, false);
		
		MatrixCharacteristics[] stats=ret.stats;
		
		//set up the number of reducers
		MRJobConfiguration.setNumReducers(job, ret.numReducerGroups, numReducers);
		
		// Print the complete instruction
		if (LOG.isTraceEnabled())
			inst.printCompelteMRJobInstruction(stats);
		
		// Update resultDimsUnknown based on computed "stats"
		byte[] dimsUnknown = new byte[resultIndexes.length];
		for ( int i=0; i < resultIndexes.length; i++ ) { 
			if ( stats[i].numRows == -1 || stats[i].numColumns == -1 ) {
				dimsUnknown[i] = (byte)1;
			}
			else {
				dimsUnknown[i] = (byte) 0;
			}
		}
		//MRJobConfiguration.updateResultDimsUnknown(job,resultDimsUnknown);
		
		//set up the multiple output files, and their format information
		MRJobConfiguration.setUpMultipleOutputs(job, resultIndexes, dimsUnknown, outputs, outputInfos, inBlockRepresentation, true);
		
		// configure mapper and the mapper output key value pairs
		job.setMapperClass(GMRMapper.class);
		if(numReducers==0)
		{
			job.setMapOutputKeyClass(Writable.class);
			job.setMapOutputValueClass(Writable.class);
		}else
		{
			job.setMapOutputKeyClass(MatrixIndexes.class);
			if(inBlockRepresentation)
				job.setMapOutputValueClass(TaggedMatrixBlock.class);
			else
				job.setMapOutputValueClass(TaggedMatrixPackedCell.class);
		}
		
		//set up combiner
		if(numReducers!=0 && aggInstructionsInReducer!=null 
				&& !aggInstructionsInReducer.isEmpty())
		{
			job.setCombinerClass(GMRCombiner.class);
		}
	
		//configure reducer
		job.setReducerClass(GMRReducer.class);
		//job.setReducerClass(PassThroughReducer.class);
		
		// By default, the job executes in "cluster" mode.
		// Determine if we can optimize and run it in "local" mode.
		MatrixCharacteristics[] inputStats = new MatrixCharacteristics[inputs.length];
		for ( int i=0; i < inputs.length; i++ ) {
			inputStats[i] = new MatrixCharacteristics(rlens[i], clens[i], brlens[i], bclens[i]);
		}
		ExecMode mode = RunMRJobs.getExecMode(JobType.GMR, inputStats); 
		if ( mode == ExecMode.LOCAL ) {
			job.set("mapred.job.tracker", "local");
			MRJobConfiguration.setStagingDir( job );
		}
		
		//System.out.println("Check mode = " + mode);
		
		//set unique working dir
		MRJobConfiguration.setUniqueWorkingDir(job, mode);
		
		
		RunningJob runjob=JobClient.runJob(job);
		
		Group group=runjob.getCounters().getGroup(MRJobConfiguration.NUM_NONZERO_CELLS);
		//MatrixCharacteristics[] stats=new MatrixCharacteristics[resultIndexes.length];
		for(int i=0; i<resultIndexes.length; i++) {
			// number of non-zeros
			stats[i].nonZero=group.getCounter(Integer.toString(i));
		}
		
		String dir = dimsUnknownFilePrefix + "/" + runjob.getID().toString() + "_dimsFile";
		stats = MapReduceTool.processDimsFiles(dir, stats);
		MapReduceTool.deleteFileIfExistOnHDFS(dir);

		/* Process different counters */
		
/*		Group group=runjob.getCounters().getGroup(MRJobConfiguration.NUM_NONZERO_CELLS);
		Group rowgroup, colgroup;
		
		for(int i=0; i<resultIndexes.length; i++)
		{
			// number of non-zeros
			stats[i].nonZero=group.getCounter(Integer.toString(i));
		//	System.out.println("result #"+resultIndexes[i]+" ===>\n"+stats[i]);
			
			// compute dimensions for output matrices whose dimensions are unknown at compilation time 
			if ( stats[i].numRows == -1 || stats[i].numColumns == -1 ) {
				if ( resultDimsUnknown[i] != (byte) 1 )
					throw new DMLRuntimeException("Unexpected error after executing GMR Job");
			
				rowgroup = runjob.getCounters().getGroup("max_rowdim_"+i);
				colgroup = runjob.getCounters().getGroup("max_coldim_"+i);
				int maxrow, maxcol;
				maxrow = maxcol = 0;
				for ( int rid=0; rid < numReducers; rid++ ) {
					if ( maxrow < (int) rowgroup.getCounter(Integer.toString(rid)) )
						maxrow = (int) rowgroup.getCounter(Integer.toString(rid));
					if ( maxcol < (int) colgroup.getCounter(Integer.toString(rid)) )
						maxcol = (int) colgroup.getCounter(Integer.toString(rid)) ;
				}
				//System.out.println("Resulting Rows = " + maxrow + ", Cols = " + maxcol );
				stats[i].numRows = maxrow;
				stats[i].numColumns = maxcol;
			}
		}
*/		
		
		return new JobReturn(stats, outputInfos, runjob.isSuccessful());
	}

	private static String prepMVMult(byte in1, byte in2, byte out) {
		return "MR" + Instruction.OPERAND_DELIM 
						+ "mvmult" + Instruction.OPERAND_DELIM 
						+ in1 + Instruction.DATATYPE_PREFIX + DataType.MATRIX + Instruction.VALUETYPE_PREFIX + ValueType.DOUBLE + Instruction.OPERAND_DELIM  
						+ in2 + Instruction.DATATYPE_PREFIX + DataType.MATRIX + Instruction.VALUETYPE_PREFIX + ValueType.DOUBLE + Instruction.OPERAND_DELIM  
						+ out + Instruction.DATATYPE_PREFIX + DataType.MATRIX + Instruction.VALUETYPE_PREFIX + ValueType.DOUBLE + Instruction.OPERAND_DELIM;  
	}
	
	private static String prepPartialAgg(byte in1, byte out) {
		return "MR" + Instruction.OPERAND_DELIM 
						+ "uak+" + Instruction.OPERAND_DELIM 
						+ in1 + Instruction.DATATYPE_PREFIX + DataType.MATRIX + Instruction.VALUETYPE_PREFIX + ValueType.DOUBLE + Instruction.OPERAND_DELIM  
						+ out + Instruction.DATATYPE_PREFIX + DataType.MATRIX + Instruction.VALUETYPE_PREFIX + ValueType.DOUBLE + Instruction.OPERAND_DELIM;  
	}
	
	private static String prepAgg(byte in1, byte out) {
		return "MR" + Instruction.OPERAND_DELIM 
						+ "ak+" + Instruction.OPERAND_DELIM 
						+ in1 + Instruction.DATATYPE_PREFIX + DataType.MATRIX + Instruction.VALUETYPE_PREFIX + ValueType.DOUBLE + Instruction.OPERAND_DELIM  
						+ out + Instruction.DATATYPE_PREFIX + DataType.MATRIX + Instruction.VALUETYPE_PREFIX + ValueType.DOUBLE + Instruction.OPERAND_DELIM
						+ "true" + Instruction.OPERAND_DELIM + CorrectionLocationType.NONE;
	}
	
	public static void main(String[] args) throws Exception {
		/*runJob(MRJobInstruction inst, String[] inputs, InputInfo[] inputInfos, long[] rlens, long[] clens, 
				int[] brlens, int[] bclens, String recordReaderInstruction, String instructionsInMapper, String aggInstructionsInReducer, 
				String otherInstructionsInReducer, int numReducers, int replication, byte[] resultIndexes, String dimsUnknownFilePrefix, 
				String[] outputs, OutputInfo[] outputInfos)*/
		
		ConfigurationManager.setConfig(new DMLConfig("SystemML-config.xml"));
		
		/*MatrixBlock data = LocalFileUtils.readMatrixBlockFromLocal("data/mvmult/w.mtx");
		System.out.println(data.getNumRows() + ", " + data.getNumColumns() + ", " + data.isInSparseFormat());
		*/
		String[] inputs = {"data/mvmult/X.mtx", "data/mvmult/ones.mtx", "data/mvmult/X.mtx", "data/mvmult/ones.mtx"};
		InputInfo[] inputInfos = {InputInfo.BinaryBlockInputInfo, InputInfo.BinaryBlockInputInfo, InputInfo.BinaryBlockInputInfo, InputInfo.BinaryBlockInputInfo};
		long[] rlens = { 4000, 2500, 4000, 2500 };
		long[] clens = { 2500, 1, 2500, 1 };
		int[] brlens = { 1000, 1000, 1000, 1000 };
		int[] bclens = { 1000, 1000, 1000, 1000 };
		
		boolean[] partitioned = { false, false, false, false };
		PDataPartitionFormat[] pformats = { PDataPartitionFormat.NONE, PDataPartitionFormat.NONE, PDataPartitionFormat.NONE, PDataPartitionFormat.NONE };
		int[] psizes = { -1, -1, -1, -1 };
		
		String recordReaderInstruction = null;
		String otherInstructionsInReducer = "";
		int numReducers = 10;
		int replication = 1;
		String dimsUnknownFilePrefix = "data/mvmult/unknownPrefix";
		String[] outputs = {"data/mvmult/out1.mtx", "data/mvmult/out2.mtx"};
		OutputInfo[] outputInfos = {OutputInfo.TextCellOutputInfo, OutputInfo.TextCellOutputInfo};
		
        String instructionsInMapper = prepMVMult((byte)0, (byte)1, (byte)4) + Instruction.INSTRUCTION_DELIM + prepMVMult( (byte)2, (byte)3, (byte)5);
        //String instructionsInMapper = prepPartialAgg((byte)0, (byte)1);
        String aggInstructionsInReducer = prepAgg((byte)4, (byte)6) + Instruction.INSTRUCTION_DELIM + prepAgg((byte)5, (byte)7);
		//System.out.println("Mapper Instructions: " + instructionsInMapper);
		//System.out.println("Reduce Instructions: " + aggInstructionsInReducer);

		byte[] resultIndexes = { 6, 7 };
		
		JobReturn ret = runJob(new MRJobInstruction(JobType.GMR), inputs, inputInfos, rlens, clens, brlens, bclens, 
				partitioned, pformats, psizes, 
				null, instructionsInMapper, aggInstructionsInReducer, otherInstructionsInReducer,
				numReducers, replication, false, resultIndexes, dimsUnknownFilePrefix, outputs, outputInfos);
		
	}

}