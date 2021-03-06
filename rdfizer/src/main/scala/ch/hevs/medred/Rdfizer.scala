package ch.hevs.medred

import rdftools.rdf.RdfTerm
import rdftools.rdf.Iri
import rdftools.rdf.jena._
import rdftools.rdf.RdfTools._
import rdftools.rdf.vocab.RDF
import rdftools.rdf.vocab.DCterms

import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.rdf.model.RDFNode
import org.apache.jena.riot.RDFFormat
import org.apache.jena.riot.RDFDataMgr
import org.apache.jena.vocabulary.XSD

import ch.hevs.medred.vocab.MedRed
import ch.hevs.medred.vocab.PPlan
import rdftools.rdf.vocab.PROV
import ch.hevs.medred.vocab.Shacl
import java.io.FileWriter
import java.io.FileOutputStream
import org.apache.jena.rdf.model.RDFList

class Rdfizer(val prefix: Iri) {

  import Rdfizer._
  import rdftools.rdf.RdfSchema._

  def newIri(str: String) = prefix + str.replaceAll("\\s|/|<|>|\\(|\\)", "")

  def toRdf(item: Item)(implicit m: Model): Unit = item match {
    case sec: Section =>
      val secIri = newIri(sec.name)
      +=(secIri:Iri, RDF.a, MedRed.Section:Iri)
      +=(secIri, DCterms.identifier, lit(sec.name))
      +=(secIri, DCterms.title, lit(sec.label))
      val secItems: Array[RDFNode] = sec.items.map { i =>
        val iti = newIri(i.name)
        toRdf(i)
        +=(iti, MedRed.ofSection, secIri)
        Iri(iti): RDFNode
      }.toArray
      +=(secIri, MedRed.items, m.createList(secItems))
    case question: Question =>
      val qIri = createRdfItem(question)
      +=(qIri, RDF.a, MedRed.Question)
      val choices: Array[RDFNode] =
        question.choices.map { x => toJenaRes(toRdf(x)) }.toArray
      if (choices.length > 0)
        +=(qIri, MedRed.choices, m.createList(choices))
      createRdfVar(question.field, qIri)
    case operation: Operation =>
      val oIri = createRdfItem(operation)
      +=(oIri, RDF.a, MedRed.Operation)
      +=(oIri, MedRed.calculation, operation.field.computation.get)

      createRdfVar(operation.field, oIri)
    case note: Note =>
      val infoIri = newIri(note.name)
      +=(infoIri, RDF.a, MedRed.Information)
      +=(infoIri, DCterms.description, lit(note.label))

  }

  def createRdfVar(field: Field, itemIri: Iri)(implicit m: Model) = {
    val varIri = itemIri + "_var"
    +=(itemIri, PPlan.hasOutputVar, varIri)
    field.validation.foreach { valid =>  
      val shapeIri=itemIri+"_shape"
      +=(itemIri,MedRed.validationShape,shapeIri)
      +=(shapeIri,RDF.a,Shacl.PropertyShape)
      +=(shapeIri,Shacl.path,MedRed.dataValue)
      
      valid.propShapes.foreach{ 
        case min:MinInclShape=> 
          +=(shapeIri,Shacl.minInclusive,lit(min.minVal))
        case min:MinExclShape=> 
          +=(shapeIri,Shacl.minExclusive,lit(min.minVal))
        case max:MaxInclShape=> 
          +=(shapeIri,Shacl.maxInclusive,lit(max.maxVal))
        case max:MaxExclShape=> 
          +=(shapeIri,Shacl.maxExclusive,lit(max.maxVal))      }
           
    }
    val shapeIri=itemIri+"_shape"

    +=(varIri, RDF.a, PPlan.Variable)
    +=(varIri, MedRed.varName, lit(field.variable.varName))
    +=(varIri, MedRed.dataType, Iri(field.variable.varType.getURI))
  }

  def createRdfItem(item: Item)(implicit m: Model): Iri = {
    val itemIri = newIri(item.name)
    +=(itemIri, DCterms.identifier, lit(item.name))
    +=(itemIri, DCterms.title, lit(item.label))
    if (!item.note.isEmpty)
      +=(itemIri, DCterms.description, lit(item.note))
    itemIri
  }

  def toRdf(choice: Choice)(implicit m: Model) = {
    val choiceIri = newIri("choice_"+choice.label +"_"+ choice.value)
    +=(choiceIri, RDF.a, MedRed.Choice)
    +=(choiceIri, DCterms.title, lit(choice.label))
    +=(choiceIri, MedRed.hasValue, lit(choice.value))
    choiceIri
  }

  def toRdf(instr: Instrument)(implicit m:Model):Iri = {
    val instIri = newIri(instr.name)
    +=(instIri, RDF.a, MedRed.Instrument)
    +=(instIri, DCterms.identifier, lit(instr.name))
    val items: Array[RDFNode] = instr.items.map { item =>
      val iri = newIri(item.name)
      toRdf(item)
      +=(iri, MedRed.ofInstrument, instIri)
      Iri(iri): RDFNode
    }.toArray
    var current = Iri("")
    items.foreach { r =>
      val itemi = Iri(r.asResource.getURI)
      if (!current.value.isEmpty)
        +=(itemi, PPlan.isPrecededBy, current)
      current = itemi
    }
    +=(instIri, MedRed.items, m.createList(items))
    instIri
  }
  
  def toRdf(study:Study)(implicit m:Model):Iri={
    val studyIri=newIri(study.name)
    +=(studyIri,RDF.a,MedRed.Study)
    +=(studyIri,DCterms.identifier,lit(study.id))
    +=(studyIri,DCterms.title,lit(study.name))
    +=(studyIri,DCterms.description,lit(study.description))
    val instrs=study.instruments.map{instr=>
      val instrIri=toRdf(instr)
          +=(studyIri,MedRed.instrument,instrIri)

    }
    studyIri
  }

  def toRdf(record:Record)(implicit m:Model):Iri={
    val recordIri=newIri(record.recordId)
    +=(recordIri,RDF.a,Iri("Record"))
    +=(recordIri,DCterms.identifier,lit(record.recordId))
    val values=record.fields.map { field=>
      lit(field.toString):RDFNode
    }.toArray
    +=(recordIri,MedRed.valueList,m.createList(values))
    val varNameList=record.varNames.map(vn=>lit(vn):RDFNode).toArray
    +=(recordIri,MedRed.varNameList,m.createList(varNameList))
    
    recordIri
  }
  
}

object Rdfizer {

  def usage(args:Array[String]) ={
    val usage="Usage: mmlaln [-s] [-r] filename"
    
    if (args.length == 0) println(usage)
    val arglist = args.toList
    type OptionMap = Map[Symbol, Any]

    def nextOption(map : OptionMap, list: List[String]) : OptionMap = {
      def isSwitch(s : String) = (s(0) == '-')
      list match {
        case Nil => map
        case "-s" :: tail =>
                               nextOption(map ++ Map('mode -> "study"), tail)
        case "-r" :: tail =>
                               nextOption(map ++ Map('mode -> "record"), tail)
        case string :: opt2 :: tail if isSwitch(opt2) => 
                               nextOption(map ++ Map('infile -> string), list.tail)
        case string :: Nil =>  nextOption(map ++ Map('infile -> string), list.tail)
        case option :: tail => println("Unknown option "+option) 
                               
          System.exit(1)
          Map()
      }
    }
    val options = nextOption(Map(),arglist)
    options
  }
  
  def newModel(rdfizer:Rdfizer)= {
    val m = ModelFactory.createDefaultModel
    m.setNsPrefix("ex", rdfizer.prefix.path)
    m.setNsPrefix("rdf", RDF.iri.path)
    m.setNsPrefix("medred", MedRed.iri.path)
    m.setNsPrefix("xsd", XSD.getURI)
    m.setNsPrefix("dcterms", DCterms.iri.path)
    m.setNsPrefix("pplan", PPlan.iri.path)
    m.setNsPrefix("sh", Shacl.iri.path)
    m
  }
  
  def main(args: Array[String]): Unit = {
    import collection.JavaConversions._
    
  
    //println(usage(args))
    
    
    
    val rdfizer = new Rdfizer(iri("http://example.org/"))
    implicit val m = newModel(rdfizer)
    
    // load RedCap records in csv
    val records =  CsvImport.loadRecords("src/main/resources/studies/D1NAMOrecords.csv")
    
    // generate RDF for all records
    records.foreach{rec=>
      rdfizer.toRdf(rec)
    }
    
    
    //load RedCap study in csv
    //val study = CsvImport.loadStudy("src/main/resources/studies/WORRK.csv")
    val study = CsvImport.loadStudy("src/main/resources/studies/D1NAMO.csv")
    //val study = CsvImport.loadStudy( "/Users/jpc/git/medred-instruments/redcap-shared/AllInstruments.csv")
    
    // generate RDF for the study questions
    rdfizer.toRdf(study)

    // export to a file
    val file=new FileOutputStream("output.ttl")
    RDFDataMgr.write(file, m, RDFFormat.TURTLE)
    
    /*
    instr.items.foreach {d=>
      d match {
        case s:Section=>println(s.sectionName)
          println(s.items.map { i => i.name }.mkString(","))
        
      }
    }*/
  }
}