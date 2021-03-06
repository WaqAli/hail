package org.broadinstitute.hail

import breeze.linalg.Matrix
import org.broadinstitute.hail.utils._
import org.apache.spark.SparkContext
import org.broadinstitute.hail.annotations.Annotation
import org.broadinstitute.hail.variant.{Genotype, Variant, VariantDataset, VariantMetadata}

object TestUtils {

  import org.scalatest.Assertions._

  def interceptFatal(regex: String)(f: => Any) {
    val thrown = intercept[FatalException](f)
    val p = regex.r.findFirstIn(thrown.getMessage).isDefined
    if (!p)
      println(
        s"""expected fatal exception with pattern `$regex'
           |  Found: ${thrown.getMessage}""".stripMargin)
    assert(p)
  }

  // G(i,j) is genotype of variant j in sample i encoded as -1, 0, 1, 2
  def vdsFromMatrix(sc: SparkContext)(G: Matrix[Int], nPartitions: Int = sc.defaultMinPartitions): VariantDataset = {
    val sampleIds = (0 until G.rows).map(_.toString).toArray

    val rdd = sc.parallelize(
      (0 until G.cols).map { j =>
        (Variant("1", j + 1, "A", "C"),
          (Annotation.empty,
            (0 until G.rows).map { i =>
              val gt = G(i, j)
              assert(gt >= -1 && gt <= 2)
              Genotype(gt)
            }: Iterable[Genotype]
          )
        )
      },
      nPartitions
    ).toOrderedRDD

    new VariantDataset(VariantMetadata(sampleIds), rdd)
  }
}

