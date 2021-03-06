package is.hail.methods

import is.hail.annotations.Annotation
import is.hail.expr.{TStruct, _}
import is.hail.stats.LeveneHaldane
import is.hail.utils._
import is.hail.variant.{GenericDataset, Genotype, Variant, VariantDataset}
import org.apache.spark.rdd.RDD
import org.apache.spark.util.StatCounter

import scala.collection.mutable


object VariantQCCombiner {
  val header =
    "callRate\t" +
      "AC\t" +
      "AF\t" +
      "nCalled\t" +
      "nNotCalled\t" +
      "nHomRef\t" +
      "nHet\t" +
      "nHomVar\t" +
      "dpMean\tdpStDev\t" +
      "gqMean\tgqStDev\t" +
      "nNonRef\t" +
      "rHeterozygosity\t" +
      "rHetHomVar\t" +
      "rExpectedHetFrequency\tpHWE"

  val signature = TStruct(
    "callRate" -> TFloat64,
    "AC" -> TInt32,
    "AF" -> TFloat64,
    "nCalled" -> TInt32,
    "nNotCalled" -> TInt32,
    "nHomRef" -> TInt32,
    "nHet" -> TInt32,
    "nHomVar" -> TInt32,
    "dpMean" -> TFloat64,
    "dpStDev" -> TFloat64,
    "gqMean" -> TFloat64,
    "gqStDev" -> TFloat64,
    "nNonRef" -> TInt32,
    "rHeterozygosity" -> TFloat64,
    "rHetHomVar" -> TFloat64,
    "rExpectedHetFrequency" -> TFloat64,
    "pHWE" -> TFloat64)
}

class VariantQCCombiner extends Serializable {
  var nNotCalled: Int = 0
  var nHomRef: Int = 0
  var nHet: Int = 0
  var nHomVar: Int = 0

  val dpSC = new StatCounter()

  val gqSC: StatCounter = new StatCounter()

  // FIXME per-genotype

  def merge(g: Genotype): VariantQCCombiner = {
    (Genotype.unboxedGT(g): @unchecked) match {
      case 0 =>
        nHomRef += 1
      case 1 =>
        nHet += 1
      case 2 =>
        nHomVar += 1
      case -1 =>
        nNotCalled += 1
    }

    Genotype.dp(g).foreach { v =>
      dpSC.merge(v)
    }

    Genotype.gq(g).foreach { v =>
      gqSC.merge(v)
    }

    this
  }

  def merge(that: VariantQCCombiner): VariantQCCombiner = {
    nNotCalled += that.nNotCalled
    nHomRef += that.nHomRef
    nHet += that.nHet
    nHomVar += that.nHomVar

    dpSC.merge(that.dpSC)

    gqSC.merge(that.gqSC)

    this
  }

  def HWEStats: (Option[Double], Double) = {
    // rExpectedHetFrequency, pHWE
    val n = nHomRef + nHet + nHomVar
    val nAB = nHet
    val nA = nAB + 2 * nHomRef.min(nHomVar)

    val LH = LeveneHaldane(n, nA)
    (divOption(LH.getNumericalMean, n), LH.exactMidP(nAB))
  }

  def asAnnotation: Annotation = {
    val af = {
      val refAlleles = nHomRef * 2 + nHet
      val altAlleles = nHomVar * 2 + nHet
      divOption(altAlleles, refAlleles + altAlleles)
    }

    val nCalled = nHomRef + nHet + nHomVar
    val hwe = HWEStats
    val callrate = divOption(nCalled, nCalled + nNotCalled)
    val ac = nHet + 2 * nHomVar

    Annotation(
      divNull(nCalled, nCalled + nNotCalled),
      ac,
      af.getOrElse(null),
      nCalled,
      nNotCalled,
      nHomRef,
      nHet,
      nHomVar,
      nullIfNot(dpSC.count > 0, dpSC.mean),
      nullIfNot(dpSC.count > 0, dpSC.stdev),
      nullIfNot(gqSC.count > 0, gqSC.mean),
      nullIfNot(gqSC.count > 0, gqSC.stdev),
      nHet + nHomVar,
      divNull(nHet, nHomRef + nHet + nHomVar),
      divNull(nHet, nHomVar),
      hwe._1.getOrElse(null),
      hwe._2)
  }
}

object VariantQC {
  def results(vds: GenericDataset): RDD[(Annotation, VariantQCCombiner)] = {
    val extract = Genotype.buildGenotypeExtractor(vds.genotypeSignature)
    vds
      .aggregateByVariant(new VariantQCCombiner)((comb, g) => comb.merge(extract(g)),
        (comb1, comb2) => comb1.merge(comb2))
  }

  def apply(vds: GenericDataset, root: String): GenericDataset = {
    val extract = Genotype.buildGenotypeExtractor(vds.genotypeSignature)
    val (newVAS, insertQC) = vds.vaSignature.insert(VariantQCCombiner.signature,
      Parser.parseAnnotationRoot(root, Annotation.VARIANT_HEAD))
    vds.mapAnnotationsWithAggregate(new VariantQCCombiner, newVAS)((comb, v, va, s, sa, g) => comb.merge(extract(g)),
      (comb1, comb2) => comb1.merge(comb2),
      (va, comb) => insertQC(va, comb.asAnnotation))
  }
}
