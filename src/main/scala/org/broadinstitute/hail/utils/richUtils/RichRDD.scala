package org.broadinstitute.hail.utils.richUtils

import org.apache.hadoop
import org.apache.hadoop.fs.FileStatus
import org.apache.hadoop.io.compress.CompressionCodecFactory
import org.apache.spark.rdd.RDD
import org.broadinstitute.hail.driver.HailConfiguration
import org.broadinstitute.hail.sparkextras.ReorderedPartitionsRDD
import org.broadinstitute.hail.utils._

import scala.reflect.ClassTag

class RichRDD[T](val r: RDD[T]) extends AnyVal {
  def countByValueRDD()(implicit tct: ClassTag[T]): RDD[(T, Int)] = r.map((_, 1)).reduceByKey(_ + _)

  def reorderPartitions(oldIndices: Array[Int])(implicit tct: ClassTag[T]): RDD[T] =
    new ReorderedPartitionsRDD[T](r, oldIndices)

  def forall(p: T => Boolean)(implicit tct: ClassTag[T]): Boolean = r.map(p).fold(true)(_ && _)

  def exists(p: T => Boolean)(implicit tct: ClassTag[T]): Boolean = r.map(p).fold(false)(_ || _)

  def writeTable(filename: String, header: Option[String] = None, parallelWrite: Boolean = false) {
    val hConf = r.sparkContext.hadoopConfiguration

    val codecFactory = new CompressionCodecFactory(hConf)
    val codec = Option(codecFactory.getCodec(new hadoop.fs.Path(filename)))
    val headerExt = codec.map(_.getDefaultExtension).getOrElse("")

    hConf.delete(filename, recursive = true) // overwriting by default

    val parallelOutputPath =
      if (parallelWrite) {
        filename
      } else
        hConf.getTemporaryFile(HailConfiguration.tmpDir)

    val rWithHeader = header.map { h =>
        if (parallelWrite)
          r.mapPartitions { it => Iterator(h) ++ it }
        else
          r.mapPartitionsWithIndex { case (i, it) =>
            if (i == 0)
              Iterator(h) ++ it
            else
              it
          }
      }.getOrElse(r)

    codec match {
      case Some(x) => rWithHeader.saveAsTextFile(parallelOutputPath, x.getClass)
      case None => rWithHeader.saveAsTextFile(parallelOutputPath)
    }

    if (!hConf.exists(parallelOutputPath + "/_SUCCESS"))
      fatal("write failed: no success indicator found")

    if (!parallelWrite) {
      val filesToMerge = hConf.glob(parallelOutputPath + "/part-*").sortBy(fs => getPartNumber(fs.getPath.getName))

      val (_, dt) = time {
        hConf.copyMerge(filesToMerge, filename, deleteSource = true)
      }
      info(s"while writing:\n    $filename\n  merge time: ${ formatTime(dt) }")

      hConf.delete(parallelOutputPath, recursive = true)
    }
  }

  def collectOrdered()(implicit tct: ClassTag[T]): Array[T] =
    r.zipWithIndex().collect().sortBy(_._2).map(_._1).toArray
}
