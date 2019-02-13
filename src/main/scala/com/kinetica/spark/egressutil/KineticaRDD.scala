package com.kinetica.spark.egressutil

import java.sql.{ Connection, ResultSet, PreparedStatement }
import java.util.Properties

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.Row
import org.apache.spark.sql.sources._
import org.apache.spark.sql.types._
import org.apache.spark.{ Partition, SparkContext, TaskContext }

import org.apache.spark.sql.catalyst.encoders.RowEncoder
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.util.CompletionIterator

import com.kinetica.spark.LoaderParams
import com.gpudb.Record;

import com.gpudb.protocol.GetRecordsByColumnRequest;
import com.gpudb.protocol.GetRecordsByColumnResponse;



import scala.util.control.NonFatal

import com.typesafe.scalalogging.LazyLogging

/**
 * Data corresponding to one partition of a Kinetica RDD.
 */
private[kinetica] case class KineticaPartition(startRow: Int, numRows: Int, idx: Int) extends Partition with LazyLogging {
    override def index: Int = idx
}

/**
 * An RDD representing a table in a database accessed via JDBC.  Both the
 * driver code and the workers must be able to access the database; the driver
 * needs to fetch the schema while the workers need to fetch the data.
 */
private[kinetica] class KineticaRDD(
    sc: SparkContext,
    getConnection: () => Connection,
    schema: StructType,
    table: String,
    columns: Array[String],
    filters: Array[Filter],
    partitions: Array[Partition],
    properties: Properties,
    conf: LoaderParams
    )
    extends RDD[Row](sc, Nil) with LazyLogging {
    
    private val  MAX_ROWS_TO_FETCH = 10000
   
    /**
     * Retrieve the list of partitions corresponding to this RDD.
     */
    override def getPartitions: Array[Partition] = partitions

    override def compute(thePart: Partition, context: TaskContext): Iterator[Row] = {

        val part = thePart.asInstanceOf[KineticaPartition]

        // Fetch the rows
        val allRows = KineticaUtils.getRowsFromKinetica( conf.getGpudb, conf.getTablename,
                                                         columns, schema,
                                                         part.startRow, part.numRows,
                                                         MAX_ROWS_TO_FETCH );


        def close() {
        }
        context.addTaskCompletionListener { context => close() }

        allRows
    }
}
