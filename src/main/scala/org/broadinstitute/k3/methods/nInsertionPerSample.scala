package org.broadinstitute.k3.methods

import org.broadinstitute.k3.variant._

import scala.collection.Map

object nInsertionPerSample {
  def apply(vds: VariantDataset): Map[Int, Int] = {
    vds
      .mapValuesWithKeys((v, s, g) => if (g.isNonRef && v.isInsertion) 1 else 0)
      .foldBySample(0)(_ + _)
  }
}


