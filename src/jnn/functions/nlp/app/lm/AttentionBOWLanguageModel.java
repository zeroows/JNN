package jnn.functions.nlp.app.lm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.HashSet;
import java.util.LinkedList;

import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.math3.util.FastMath;

import jnn.functions.composite.LookupTable;
import jnn.functions.composite.lstm.LSTMDecoder;
import jnn.functions.nlp.aux.input.InputSentence;
import jnn.functions.nlp.aux.metrics.ErrorStats;
import jnn.functions.nlp.aux.readers.TextDataset;
import jnn.functions.nlp.words.OutputWordRepresentationLayer;
import jnn.functions.nlp.words.WordRepresentationLayer;
import jnn.functions.nlp.words.features.OutputWordRepresentationSetup;
import jnn.functions.nlp.words.features.WordRepresentationSetup;
import jnn.functions.parametrized.DenseFullyConnectedLayer;
import jnn.functions.parametrized.StaticLayer;
import jnn.neuron.DenseNeuronArray;
import jnn.training.GlobalParameters;
import util.IOUtils;
import vocab.Vocab;

public class AttentionBOWLanguageModel {
	public static final String SOS ="<s>";
	public static final String EOS ="</s>";
	public static final String UNK ="<unkW>";

	public AttentionBOWLanguageModelSpecification setup;
	public WordRepresentationLayer wordRepresentation;
	public WordRepresentationSetup wordRepresentationSetup;

	public WordRepresentationLayer wordAttention;
	public WordRepresentationSetup wordAttentionSetup;
	public StaticLayer positionBias;
	
	public DenseFullyConnectedLayer wordToAttention;

	public OutputWordRepresentationLayer outputWordRepresentation;
	public OutputWordRepresentationSetup outputWordRepresentationSetup;
	public Vocab vocab = new Vocab();

	HashSet<String> validationWords = new HashSet<String>();
	ErrorStats devPP = new ErrorStats();	

	public AttentionBOWLanguageModel(AttentionBOWLanguageModelSpecification setup) {
		this.setup = setup;
		devPP.init();
		buildVocab();
		buildModels();
		buildValidationWords();
	}

	public void save(){
		String file = setup.outputDir + "/model.gz";
		String tmpFile = setup.outputDir + "/model.tmp.gz";
		PrintStream out = IOUtils.getPrintStream(tmpFile);
		vocab.saveVocab(out);
		wordRepresentation.save(out);
		wordAttention.save(out);
		wordToAttention.save(out);
		positionBias.save(out);
		outputWordRepresentation.save(out);
		out.close();
		IOUtils.copyfile(tmpFile, file);
	}

	public void load(){
		String file = setup.outputDir + "/model.gz";
		if(IOUtils.exists(file)){
			BufferedReader in = IOUtils.getReader(file);
			vocab = Vocab.loadVocab(in);
			wordRepresentation = WordRepresentationLayer.load(in, wordRepresentationSetup);		
			wordAttention = WordRepresentationLayer.load(in, wordAttentionSetup);
			wordToAttention = DenseFullyConnectedLayer.load(in);
			positionBias = StaticLayer.load(in);
			outputWordRepresentation = OutputWordRepresentationLayer.load(in, outputWordRepresentationSetup);
			try {
				in.close();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

	public void buildVocab(){
		vocab = new Vocab();
		while(!setup.trainingData.isEnd()){
			InputSentence input = setup.trainingData.read();
			for(String token : input.getTokens()){
				vocab.addWordToVocab(token);
			}
		}
		vocab.addWordToVocab(SOS, 1000000);
		vocab.addWordToVocab(EOS, 1000000);
		vocab.addWordToVocab(UNK, 1000000);
		vocab.sortVocabByCount(setup.maxType);
		vocab.generateHuffmanCodesForNAryTree(3);
	}

	public void buildModels(){
		wordRepresentationSetup = new WordRepresentationSetup(vocab, setup.wordProjectionDim, setup.charProjectionDim, setup.charProjectionDim);		
		wordRepresentationSetup.loadFromString(setup.wordFeatures, setup.word2vecEmbeddings);
		wordRepresentation = new WordRepresentationLayer(wordRepresentationSetup);

		wordAttentionSetup = new WordRepresentationSetup(vocab, setup.bowSize - setup.windowSize + 1, setup.charProjectionDim, setup.charProjectionDim);		
		wordAttentionSetup.loadFromString(setup.wordFeatures, null);

		wordAttention = new WordRepresentationLayer(wordAttentionSetup);
		positionBias = new StaticLayer(setup.bowSize-setup.windowSize);		

		wordToAttention = new DenseFullyConnectedLayer(setup.wordProjectionDim, setup.bowSize-setup.windowSize + 1);
		
		outputWordRepresentationSetup = new OutputWordRepresentationSetup(vocab, getStateDim(), setup.softmaxCharDim, setup.softmaxStateDim, UNK);
		outputWordRepresentationSetup.sequenceType = setup.softmaxType;
		outputWordRepresentation = new OutputWordRepresentationLayer(outputWordRepresentationSetup);		
	}

	public int getStateDim(){
		return (setup.windowSize+1)*setup.wordProjectionDim;
	}

	public void buildValidationWords(){

		for(int i = 0; i < setup.validationData.countSentences(); i++){
			String[] words = setup.validationData.read().tokens;
			for(String word : words){
				validationWords.add(word);
			}
		}
	}

	public void train(int batchSize, int threads, boolean debug) {
		threads = Math.min(threads, batchSize);
		long startTime = System.currentTimeMillis();
		int numberOfWords = 0;

		double loglikelihood = 0;
		int words = 0;
		long inferenceTime = 0;
		long buildUpTime = 0;
		long forwardTime = 0;
		long backwardTime = 0;

		HashSet<String> wordList = new HashSet<String>();
		wordList.add(SOS);
		InputSentence[][] batchesPerThread = setup.trainingData.readBatchesEquivalent(batchSize/threads, threads); 

		for(int t = 0; t < threads; t++){
			for(int i = 0; i < batchesPerThread[0].length; i++){
				numberOfWords += batchesPerThread[t][i].tokens.length;
				for(String word : batchesPerThread[t][i].tokens){
					wordList.add(word);
				}
			}
		}
		long wordRepTime = wordRepresentation.fillCache(wordList, threads, true);

		double norm = 1/(double)numberOfWords;
		//			double norm = 1;

		AttentionBowLanguageModelInstance[] trainInstances = new AttentionBowLanguageModelInstance[threads];
		Thread[] trainThreads = new Thread[threads];

		for(int t = 0; t < threads; t++){
			final int tFinal = t;
			trainInstances[t] = new AttentionBowLanguageModelInstance(t, norm, batchesPerThread[t], wordRepresentation, wordAttention,wordToAttention, outputWordRepresentation, positionBias, SOS, EOS, setup.windowSize, setup.bowSize, setup.mode); 
			trainThreads[t] = new Thread(){
				@Override
				public void run() {
					trainInstances[tFinal].train();
				}
			};
			trainThreads[t].start();
		}
		for(int t = 0; t < threads; t++){
			try {
				trainThreads[t].join();
				loglikelihood+=trainInstances[t].loglikelihood;
				words+=trainInstances[t].words;
				inferenceTime+=trainInstances[t].inferenceTime;
				buildUpTime+=trainInstances[t].buildUpTime;
				forwardTime+=trainInstances[t].forwardTime;
				backwardTime+=trainInstances[t].backwardTime;
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}			
		}

		long commitStart = System.currentTimeMillis();
		wordRepresentation.updateWeightsTimed(0, 0);
		wordAttention.updateWeightsTimed(0, 0);
		wordToAttention.updateWeights(0, 0);
		positionBias.updateWeights(0, 0);
		outputWordRepresentation.updateWeightsTimed(0, 0);
		long commitTime = System.currentTimeMillis() - commitStart;

		//		initialCell.printCommitTimeAndReset();
		//		initialState.printCommitTimeAndReset();
		long computationTime = System.currentTimeMillis() - startTime;
		System.err.println("number of words per second (train) = " + (numberOfWords/(double)computationTime) + "k");
		//		System.err.println("total batch time (train) = " + computationTime);
		//		System.err.println("total inference time (train) = " + inferenceTime);
		//		System.err.println("total build up time (train) = " + buildUpTime);
		//		System.err.println("total forward time (train) = " + forwardTime);
		//		System.err.println("total backward time (train) = " + backwardTime);
		//		System.err.println("total commit time (train) = " + commitTime);
		//		System.err.println("total word rep time (train) = " + wordRepTime);
		System.err.println("ll = " + loglikelihood/words);

		//		wordRepresentation.printCommitTimeAndReset();
		//		decoder.printCommitTimeAndReset();
		//		outputWordRepresentation.printCommitTimeAndReset();
	}

	public boolean validate(int batchSize, int threads) {
		return validate(batchSize, threads, validationWords, setup.validationData);
	}

	public boolean validate(int batchSize, int threads, HashSet<String> wordSet, TextDataset dataset) {
		int sents = (int)dataset.countSentences();
		int words = 0;
		double loglikelihood = 0;

		int wordsKnown = 0;
		double loglikelihoodKnown = 0;

		int wordsUnk = 0;
		double loglikelihoodUnk = 0;

		long startTime = System.currentTimeMillis();
		wordRepresentation.fillCache(wordSet, threads, false);
		while(sents > 0){

			int batchSizeReal = Math.min(batchSize, sents);
			int threadsReal = Math.min(threads, batchSizeReal);
			HashSet<String> wordList = new HashSet<String>();
			wordList.add(SOS);
			InputSentence[][] batchesPerThread = dataset.readBatchesEquivalent(batchSizeReal/threadsReal, threadsReal); 

			AttentionBowLanguageModelInstance[] validationInstances = new AttentionBowLanguageModelInstance[threadsReal];
			Thread[] validationThreads = new Thread[threadsReal];

			for(int t = 0; t < threadsReal; t++){
				final int tFinal = t;
				validationInstances[t] = new AttentionBowLanguageModelInstance(t, 1, batchesPerThread[t], wordRepresentation, wordAttention,wordToAttention, outputWordRepresentation, positionBias, SOS, EOS, setup.windowSize, setup.bowSize, setup.mode); 
				validationThreads[t] = new Thread(){
					@Override
					public void run() {
						validationInstances[tFinal].computeLL();
					}
				};
				validationThreads[t].start();
			}
			for(int t = 0; t < threadsReal; t++){
				try {
					validationThreads[t].join();
					loglikelihood+=validationInstances[t].loglikelihood;
					words+=validationInstances[t].words;
					loglikelihoodKnown+=validationInstances[t].loglikelihoodKnown;
					wordsKnown+=validationInstances[t].wordKnown;
					loglikelihoodUnk+=validationInstances[t].loglikelihoodUnk;
					wordsUnk+=validationInstances[t].wordUnk;
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}			
			}
			sents-=batchesPerThread.length*batchesPerThread[0].length;
		}
		wordRepresentation.emptyCache();

		double pp = FastMath.pow(2,-loglikelihood/FastMath.log(2)/words);
		long computationTime = System.currentTimeMillis() - startTime;
		System.err.println("number of words per second (dev) = " + (words/(double)computationTime) + "k");
		System.err.println("ll = " + loglikelihood/words);
		System.err.println("ll(unk) = " + loglikelihoodUnk/wordsUnk);
		System.err.println("ll(known) = " + loglikelihoodKnown/wordsKnown);
		System.err.println("pp = " + pp);
		devPP.initError();

		devPP.addError(FastMath.exp(-loglikelihood/words));
		devPP.commitError();		
		devPP.displayResults("dev pp", true);
		PrintStream stats = IOUtils.getPrintStream(setup.outputDir + "/stats");
		devPP.displayResults("dev pp", stats, false);
		stats.close();

		PrintStream repOut = IOUtils.getPrintStream(setup.outputDir + "/rep.gz");
		wordRepresentation.printVectors(vocab, 1000000, repOut);
		repOut.close();

		return devPP.isBestIteration();
	}


	public void scoreSentences(int batchSize, int threads, TextDataset dataset) {
		int sents = (int)dataset.countSentences();
		int words = 0;
		double loglikelihood = 0;

		int wordsKnown = 0;
		double loglikelihoodKnown = 0;

		int wordsUnk = 0;
		double loglikelihoodUnk = 0;

		long startTime = System.currentTimeMillis();
		HashSet<String> wordSet = new HashSet<String>();
		for(int i = 0; i < sents; i++){
			String[] tokens = dataset.read().tokens;
			for(String token : tokens){
				wordSet.add(token);
			}
		}

		PrintStream out = IOUtils.getPrintStream(setup.outputDir + "test.scores.gz");

		wordRepresentation.fillCache(wordSet, threads, false);
		while(sents > 0){

			int batchSizeReal = Math.min(batchSize, sents);
			int threadsReal = Math.min(threads, batchSizeReal);
			HashSet<String> wordList = new HashSet<String>();
			wordList.add(SOS);
			LinkedList<int[]> indexes = new LinkedList<int[]>();
			InputSentence[][] batchesPerThread = dataset.readBatchesEquivalent(batchSizeReal/threadsReal, threadsReal, indexes); 

			AttentionBowLanguageModelInstance[] validationInstances = new AttentionBowLanguageModelInstance[threadsReal];
			Thread[] validationThreads = new Thread[threadsReal];

			for(int t = 0; t < threadsReal; t++){
				final int tFinal = t;
				validationInstances[t] = new AttentionBowLanguageModelInstance(t, 1, batchesPerThread[t], wordRepresentation, wordAttention,wordToAttention, outputWordRepresentation, positionBias, SOS, EOS, setup.windowSize, setup.bowSize, setup.mode); 
				validationThreads[t] = new Thread(){
					@Override
					public void run() {
						validationInstances[tFinal].computeLL();
					}
				};
				validationThreads[t].start();
			}
			for(int t = 0; t < threadsReal; t++){
				try {
					validationThreads[t].join();
					loglikelihood+=validationInstances[t].loglikelihood;
					words+=validationInstances[t].words;
					loglikelihoodKnown+=validationInstances[t].loglikelihoodKnown;
					wordsKnown+=validationInstances[t].wordKnown;
					loglikelihoodUnk+=validationInstances[t].loglikelihoodUnk;
					wordsUnk+=validationInstances[t].wordUnk;
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}			
			}
			sents-=batchesPerThread.length*batchesPerThread[0].length;
			for(int[] index : indexes){
				InputSentence sent = validationInstances[index[0]].batchSents[index[1]];
				double ll = validationInstances[index[0]].loglikelihoodPerSentence[index[1]];
				out.println(ll + " ||| " + sent.original);
			}
		}
		wordRepresentation.emptyCache();
		out.close();
		long computationTime = System.currentTimeMillis() - startTime;
		System.err.println("number of words per second (test) = " + (words/(double)computationTime) + "k");
		System.err.println("ll = " + loglikelihood/words);
		System.err.println("ll(unk) = " + loglikelihoodUnk/wordsUnk);
		System.err.println("ll(known) = " + loglikelihoodKnown/wordsKnown);
	}

	public static void main(String[] args){

		Options options = new Options();
		options.addOption("train_file", true, "training file");
		options.addOption("validation_file", true, "validation file");
		options.addOption("test_file", true, "test file");
		options.addOption("lr", true, "learning rate");
		options.addOption("batch_size", true, "batch size");
		options.addOption("iterations", true, "iterations");
		options.addOption("validation_interval", true, "batches till validation");
		options.addOption("threads", true, "number of threads");
		options.addOption("output_dir", true, "output directory");
		options.addOption("word_features", true, "word features separated by commas (e.g. words,capitalization,characters)");
		options.addOption("word2vec_embeddings", true, "word2vec embeddings");
		options.addOption("softmax_function", true, "softmax function (word or character)");
		options.addOption("bow_size", true, "size of the bow context");
		options.addOption("window_size", true, "size of the window context");
		options.addOption("mode", true, "mode 0 -> word to attention, 1 -> word rep to attention");
		options.addOption("word_dim", true, "dimensions for words");

		if(args.length == 0){
			HelpFormatter formatter = new HelpFormatter();
			formatter.printHelp( "java -jar [this program]", options );
			System.exit(0);
		}

		CommandLineParser parser = new BasicParser();
		CommandLine cmd;
		try {
			cmd = parser.parse( options, args);
		} catch (ParseException e) {
			throw new RuntimeException(e);
		}

		GlobalParameters.useMomentumDefault = true;
		GlobalParameters.learningRateDefault = Double.parseDouble(cmd.getOptionValue("lr"));


		AttentionBOWLanguageModelSpecification spec = new AttentionBOWLanguageModelSpecification();

		spec.addDataset(cmd.getOptionValue("train_file"));
		spec.addValidationDataset(cmd.getOptionValue("validation_file"));

		spec.outputDir = cmd.getOptionValue("output_dir");
		spec.wordFeatures = cmd.getOptionValue("word_features");
		spec.word2vecEmbeddings = cmd.getOptionValue("word2vec_embeddings");
		spec.softmaxType = cmd.getOptionValue("softmax_function");
		spec.bowSize = Integer.parseInt(cmd.getOptionValue("bow_size"));
		spec.windowSize = Integer.parseInt(cmd.getOptionValue("window_size"));
		spec.mode = Integer.parseInt(cmd.getOptionValue("mode"));
		spec.wordProjectionDim = Integer.parseInt(cmd.getOptionValue("word_dim"));
		AttentionBOWLanguageModel lm = new AttentionBOWLanguageModel(spec);

		int batchSize = Integer.parseInt(cmd.getOptionValue("batch_size"));
		int threads = Integer.parseInt(cmd.getOptionValue("threads"));
		int validationInterval = Integer.parseInt(cmd.getOptionValue("validation_interval"));
		int iterations = Integer.parseInt(cmd.getOptionValue("iterations"));

		//lm.load();

		for(int i = 0; i < iterations; i++){
			System.err.println(i +" in "+iterations);
			if(i % 100 == 0){
				System.err.println(i);
			}
			boolean debug = false;
			if(i != 0 & i % validationInterval == 0){
				System.err.println("testing");
				debug = true;
				if(lm.validate(batchSize, threads)){
					lm.save();
				}
			}
			lm.train(batchSize, threads, debug);
		}

		spec.addTestDataset(cmd.getOptionValue("test_file"));
		lm.scoreSentences(batchSize, threads, spec.testData);
	}

}
