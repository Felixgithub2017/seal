package edu.nju.pasalab.mt.extraction.util.parser

import java.util.Properties
import scala.collection.JavaConversions._
import edu.stanford.nlp.ie.NERClassifierCombiner
import edu.stanford.nlp.ie.crf.CRFBiasedClassifier
import edu.stanford.nlp.io.IOUtils
import edu.stanford.nlp.ling.CoreAnnotations.AnswerAnnotation
import edu.stanford.nlp.ling.{CoreLabel, Word, HasWord}
import edu.stanford.nlp.parser.lexparser.LexicalizedParser
import edu.stanford.nlp.process.Morphology
import edu.stanford.nlp.tagger.maxent.MaxentTagger
import edu.stanford.nlp.trees.{CollinsHeadFinder, Tree}

import scala.collection.Map
import java.util.concurrent.locks.{ReentrantLock, Lock}
//import scala.concurrent.Lock
import NLPConfig._

/**
  * Created by YWJ on 2016.12.25.
  * Copyright (c) 2016 NJU PASA Lab All rights reserved.
  */
object NLP {
  implicit def list2hasWordList(lst:Seq[String]):java.util.List[_<:HasWord]
  = lst.map( new Word(_) ).toList

  // ----------
  // Parsers
  // ----------
  lazy val stanfordParser = {
    val parser = LexicalizedParser.loadModel(parse.model)
    new {
      def parse(words:List[String], pos:List[String]):Tree = {
        parser.parseStrings(words);
      }
    }
  }
  lazy val parser = stanfordParser
  // ----------
  // Stanford CoreNLP Components
  // ----------
  lazy val tagger = new MaxentTagger(pos.model)

  lazy val collinsHeadFinder = new CollinsHeadFinder()

  lazy val morph:((Morphology=>Any)=>Any) = {
    val morph = new Morphology()
    //val morphLock = new Lock()
    val morphLock = new ReentrantLock()
    val f = { (fn : Morphology=>Any) =>
      morphLock.lock()
      //morphLock.acquire;
      val rtn = fn(morph)
      morphLock.unlock()
      //morphLock.release
      rtn
    }
    f
  }

  lazy val nerCRF:(Array[String], Array[String])=>Array[String] = {
    val classifier = new NERClassifierCombiner(ner.model, ner.aux);
    (words:Array[String], pos:Array[String]) => {
      val offsets:List[Int] = words.foldLeft( (List[Int](), 0) ){
        case ((offsetsSoFar:List[Int], offset:Int), word:String) =>
          (offset :: offsetsSoFar, offset + word.length + 1)
      }._1.reverse
      // (construct CoreLabel sentence)
      val coreSentence = new java.util.ArrayList[CoreLabel](words.length)
      words.zip(pos).zip(offsets)foreach{
        case ((word:String, pos:String), offset:Int) =>
          val label = new CoreLabel
          label.setWord(word)
          label.setOriginalText(word)
          label.setTag(pos)
          label.setBeginPosition(offset)
          label.setEndPosition(offset + word.length)
          coreSentence.add(label)
      }
      // (classify)
      classifier.classifySentence(coreSentence)
      val output:java.util.List[CoreLabel] = classifier.classifySentence(coreSentence);
      // (convert back)
      output.map{ (label:CoreLabel) =>
        label.ner()
      }.toArray
    }
  }

  /**
    * The TrueCase classifier implementation.
    * Takes as input an array of tokens, POS tags, and lemmas,
    * and returns as output the tokens with their true case applied.
    * The length of the tokens, POS tags, and lemmas must match.
    *
    * @return An array of tokens (words as Strings) of the same length
    *         as the input tokens, but with their inferred true case.
    */
  lazy val trueCaser:(Array[String], Array[String], Array[String])=>Array[String] = {
    // Create classifier
    val props:Properties = {
      val p = new Properties
      p.setProperty("loadClassifier", NLPConfig.truecase.model)
      p.setProperty("mixedCaseMapFile", NLPConfig.truecase.disambiguation_list)
      p.setProperty("classBias", NLPConfig.truecase.bias)
      p
    }
    val classifier = new CRFBiasedClassifier[CoreLabel](props);
    classifier.loadClassifierNoExceptions(NLPConfig.truecase.model, props);
    // Set classifier biases
    NLPConfig.truecase.bias.split(",").foreach{ (bias:String) =>
      val terms = bias.split(":")
      classifier.setBiasWeight(terms(0), terms(1).toDouble)
    }
    // Get mixed case map
    val mixedCaseMap:Map[String,String]
    = scala.io.Source.fromInputStream(IOUtils.getInputStreamFromURLOrClasspathOrFileSystem(NLPConfig.truecase.disambiguation_list))
      .getLines
      .map( _.trim.split("""\s+""") )
      .map{ case Array(a:String, b:String) => (a ,b) }
      .toMap
    // Return function
    (words:Array[String], pos:Array[String], lemma:Array[String]) => {
      // (mock offsets)
      val offsets:List[Int] = words.foldLeft( (List[Int](), 0) ){
        case ((offsetsSoFar:List[Int], offset:Int), word:String) =>
          (offset :: offsetsSoFar, offset + word.length + 1)
      }._1.reverse
      // (construct CoreLabel sentence)
      val coreSentence = new java.util.ArrayList[CoreLabel](words.length)
      words.zip(pos).zip(offsets)foreach{
        case ((word:String, pos:String), offset:Int) =>
          val label = new CoreLabel
          label.setWord(word.toLowerCase)
          label.setOriginalText(word)
          label.setTag(pos)
          label.setBeginPosition(offset)
          label.setEndPosition(offset + word.length)
          coreSentence.add(label)
      }
      // (classify)
      val output:java.util.List[CoreLabel] = classifier.classifySentence(coreSentence);
      // (convert back)
      output.map{ (label:CoreLabel) =>
        val word:String = label.word
        label.get(classOf[AnswerAnnotation]) match {
          case "UPPER" => word.toUpperCase
          case "LOWER" => word.toLowerCase
          case "INIT_UPPER" => word.substring(0, 1).toUpperCase + word.substring(1).toLowerCase
          case "O" => mixedCaseMap.get(word).getOrElse(word)
          case _ => word
        }
      }.toArray
    }
  }

  // ----------
  // Methods
  // ----------
  def preload(obj: => Any) { new Thread(){ override def run:Unit = obj }.start }
}

trait CoreLabelSeq extends Seq[CoreLabel] {
  //
  // Trivial overrides (still have to define apply(Int):CoreLabel and length:Int though)
  //
  override def iterator:Iterator[CoreLabel] = new Iterator[CoreLabel] {
    var index:Int = 0
    override def hasNext:Boolean = index < CoreLabelSeq.this.length
    override def next:CoreLabel = { index += 1; apply(index - 1); }
  }

  //
  // Common Methods
  //
  def matches(t:TokensRegex) = t.matches(this)
}
