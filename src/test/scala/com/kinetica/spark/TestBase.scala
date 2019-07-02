package com.kinetica.spark;

import com.gpudb.GPUdb;
import com.gpudb.GPUdbBase;
import com.gpudb.ColumnProperty;
import com.gpudb.Type;
import com.gpudb.protocol.ClearTableRequest;
import com.gpudb.protocol.CreateTableRequest;
import com.gpudb.protocol.ShowTableResponse;

import com.typesafe.scalalogging.LazyLogging;

import java.net.URL;
import java.time.LocalDateTime;

import org.apache.spark.sql.DataFrame;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.{IntegerType, StringType, StructField, StructType};
import org.scalatest.FunSuite;
import org.scalatest.BeforeAndAfterAll;
import org.scalatest.BeforeAndAfterEach;
import org.scalatest.Suite;

import scala.collection.JavaConversions._;
import scala.collection.JavaConverters._;
import scala.collection.{mutable, immutable};
import scala.util.Random;



/**
 * Convenience methods and shared objects need to go into this fixture
 * trait that the base class will mix-in.  When implementing tests
 * by inheriting from the base test class, all shared test cases (to
 * be run for both the v1 and v2 packages) need to go in a trait
 * that will extend this trait.  For example, look at
 * TestTableNameCollectionExtraction.scala. In order to actually run
 * those tests, we need to create two classes, one for the v1 package
 * and one for the v2 package.  Those classes will extend the base class
 * `SparkConnectorTestBase` and mix in the custom trait that has all the
 * test cases.
 */
trait SparkConnectorTestFixture
    extends BeforeAndAfterAll
            with LazyLogging { this: Suite =>

    // Names of the Kinetica Spark connector packages
    val m_default_package = "com.kinetica.spark";
    val m_v1_package      = "com.kinetica.spark.datasourcev1";
    val m_v2_package      = "com.kinetica.spark.datasourcev2";

    // Descriptions of the Kinetica Spark connector packages
    val m_default_package_descr = "[Default Package]";
    val m_v1_package_descr = "[Package DSv1]";
    val m_v2_package_descr = "[Package DSv2]";

    // Which package the tests use
    val m_package_descr   = m_default_package_descr;
    val m_package_to_test = m_default_package;
    
    
    var m_gpudb : GPUdb = null;

    // Various table related options
    val m_clearTableOptions = GPUdbBase.options( ClearTableRequest.Options.NO_ERROR_IF_NOT_EXISTS,
                                                 ClearTableRequest.Options.TRUE );

    val m_createTableOptions = GPUdbBase.options( CreateTableRequest.Options.NO_ERROR_IF_EXISTS,
                                                  CreateTableRequest.Options.TRUE );

    val m_createTableInCollectionOptions = GPUdbBase.options( CreateTableRequest.Options.NO_ERROR_IF_EXISTS,
                                                              CreateTableRequest.Options.TRUE ).asScala;

    // User given properties
    var m_host : String = "";
    var m_username : String = "";
    var m_password : String = "";
    var m_jdbc_url : String = "";

    var m_sparkSession : SparkSession = _;

    // Names of tables that need to be deleted
    var m_tablesToClear : mutable.ListBuffer[String] = mutable.ListBuffer[String]();

    override def beforeAll() {

        // Note: In order to add command-line parameters for the test,
        //       the following needs to be done:
        //   1) Add the parameter to the org.scalatest plugin configuration
        //      entry `argLine` in the POM.  Just add it to the end, with
        //      the format ' -Daaa=${xxx}'.
        //      The whitespace is important.  Also, note that the
        //      'aaa' is what you need to use for the first parameter
        //      to System.getProperty() below.  On the other hand, `xxx`
        //      is what the user would pass in from the command line,
        //      as follows:
        //      > mvn test -Dxxx=some_value
        //      Also note that 'xxx' should start with a 'k' so that it
        //      does not get confused with an environment variable (which
        //      happened with 'url' and 'username', hence 'kurl' and
        //      'kusername').
        //   2) Document the parameter in the README.md file for testing.
        //   3) Back in the POM, add an empty string as the default value
        //      for the user-facing parameter 'xxx' in the <properties>
        //      <!-- Parameters for ScalaTest> section.  Otherwise, the code
        //      gets something like "${xxx}", and System.getProperty()'s
        //      default value (the 2nd parameter) is NEVER applied.
        //      Example: <kurl>""</kurl>
        
        // Parse any user given parameters
        m_host = System.getProperty("url", "http://127.0.0.1:9191");
        // Need to check for an empty string explicitly since the behavior
        // of the scalatest-maven-plugin doesn't actually allow the default
        // value specified above to be used (it always thinks the argument
        // is passed in, even if the user doesn't specify something at the
        // command-line).
        if ( m_host.isEmpty ) {
            m_host = "http://127.0.0.1:9191";
        }
        logger.info( s"User given parameter host URL value: ${m_host}" );

        m_gpudb = new GPUdb( m_host );

        // Get the JDBC URL
        m_jdbc_url = get_jdbc_url();
        
        // Parse user given username and password, if any
        m_username = System.getProperty("username", "");
        m_password = System.getProperty("password", "");
        logger.info( s"User given parameter username value: ${m_username}" );
    }   // end beforeAll


    // Given the GPUdb connection, get the JDBC URL
    def get_jdbc_url(): String = {
        // Get the API version
        val version = GPUdbBase.getApiVersion();
        logger.info(s"Kinetica Version: ${version}");
        val host = m_gpudb.getURL().getHost();
        var jdbc_url : String = "";
        if (s"${version(0)}".toInt > 6)
            jdbc_url = s"jdbc:kinetica://${host}:9191";
        else
            jdbc_url = s"jdbc:simba://${host}:9292";
        logger.info(s"JDBC URL: ${jdbc_url}");
        return jdbc_url;
    }


    /**
     * Create a mutable map with some default spark connector options.
     * Does set values for parameters that have default values (with the
     * default values)--just so that its'
     * Sets connection related parameters that would be common to all
     * tests.
     * Return the map.
     */
    def get_default_spark_connector_options(): mutable.Map[String, String] = {
        var options: mutable.Map[String, String] = null;
        options = mutable.Map[String, String]( "database.url" -> m_host,
                                       "database.jdbc_url" -> m_jdbc_url,
                                       "database.username" -> m_username,
                                       "database.password" -> m_password,
                                       "database.retry_count" -> "0",
                                       "database.timeout_ms" -> "1800000",
                                       "ingester.analyze_data_only" -> "false",
                                       "ingester.batch_size" -> "10000",
                                       "ingester.flatten_source_schema" -> "false",
                                       "ingester.ip_regex" -> "",
                                       "ingester.multi_head" -> "false",
                                       "ingester.num_threads" -> "4",
                                       "ingester.use_snappy" -> "false",
                                       "spark.num_partitions" -> "4",
                                       "table.create" -> "false",
                                       "table.is_replicated" -> "false",
                                       "table.name_contains_schema" -> "true",
                                       "table.truncate" -> "false",
                                       "table.truncate_to_size" -> "false",
                                       "table.update_on_existing_pk" -> "false",
                                       "table.use_templates" -> "false",
                                       "table.append_new_columns" -> "false",
                                       "table.map_columns_by_name" -> "true"
                                       );
        return options;
    }

    // ------- Methods for Testing Kinetica Tables etc. via the Java API -------

    /**
     * Add to the list of tables to clear at the end of the test
     */
    def mark_table_for_deletion_at_test_end( tableName: String ) : Unit = {
        m_tablesToClear += tableName;
        return;
    }
    

    /**
     * Delete the given table.
     */
    def clear_table( tableName: String ) : Unit = {
        val request = new ClearTableRequest();
        request.setTableName( tableName );
        request.setOptions( m_clearTableOptions );
        m_gpudb.clearTable( request );
        return;
    }


    /**
     * Given a table name, get the current table size.
     */
    def get_table_size( table_name: String ) : scala.Long = {
        val show_table = m_gpudb.showTable( s"${table_name}",
                                            immutable.Map[String, String]("get_sizes" -> "true").asJava );
        val table_props = show_table.getProperties().asScala;
        val table_size = show_table.getTotalSize();
        return table_size;
    }


    /**
     * Given a table name, check if the table exists (as is, without any special
     * name handling.)
     */
    def does_table_exist( table_name: String ) : Boolean = {
        return m_gpudb.hasTable( table_name, null ).getTableExists();
    }


    /**
     * Given a table name, check if it belongs to any collection.  If so, return
     * the collection name, otherwise return None.  Also, return None if the
     * table does not exist OR if the table itself is a collection.
     */
    def get_table_collection_name( table_name: String ) : Option[String] = {
        if ( does_table_exist( table_name ) ) {
            val show_table = m_gpudb.showTable( s"${table_name}", null );

            // Check if the given table is actually a collection
            val table_names = show_table.getTableNames().asScala;
            if ( table_names.length > 1 ) {
                return None; // Can't be in another collection
            }

            // If this table is not a collection, then get its collection
            // information, if any
            val info = show_table.getAdditionalInfo().asScala.get( 0 );
            val collection_name = info get ShowTableResponse.AdditionalInfo.COLLECTION_NAMES;
            return Option.apply( collection_name );
        }

        // Table does not exist return an empty string
        return None;
    }
        
    // --------- DataFrame Generator Functions for Testing Convenience ---------

    /**
     * Create a dataframe with two nullable integer columns 'x' and 'y' and
     * generate the given number of random rows.
     */
    def createDataFrameTwoIntNullableColumns( numRows: Int ) : DataFrame = {
        logger.debug("Creating a dataframe with random data in it");
        // Generate random data
        val numColumns = 2;
        val data = (1 to numRows).map(_ => Seq.fill( numColumns )(Random.nextInt) );
        // Generate the schema
        val schema = StructType( StructField( "x", IntegerType, true ) ::
                                 StructField( "y", IntegerType, true ) :: Nil );
        // Generate the RDD from the data
        val rdd = m_sparkSession.sparkContext.parallelize( data.map( x => Row(x:_*) ) );
        // Generate the dataframe from the RDD
        val df = m_sparkSession.sqlContext.createDataFrame( rdd, schema );

        // Double check the dataframe has the desired number of rows
        assert(df.count() == numRows);

        return df;
    }


    /**
     * Create a dataframe with one nullable string column 'timestamp' and
     * generate the given number of rows with random, valid timestamps.
     */
    def createDataFrameOneStringTimestampNullableColumn( numRows: Int ) : DataFrame = {
        logger.debug("Creating a dataframe with random timestamp data in it");
        // Generate some timestamp data
        val numColumns = 1;
        val data = (1 to numRows).map(_ => Seq.fill( numColumns )( LocalDateTime.now().toString() ) );

        // Generate the schema
        val schema = StructType( StructField( "timestamp", StringType, true ) :: Nil );
        // Generate the RDD from the data
        val rdd = m_sparkSession.sparkContext.parallelize( data.map( x => Row(x:_*) ) );
        // Generate the dataframe from the RDD
        val df = m_sparkSession.sqlContext.createDataFrame( rdd, schema );

        // Double check the dataframe has the desired number of rows
        assert(df.count() == numRows);

        return df;
    }


    // -------- Kinetica Table Generator Functions for Testing Convenience ---------

    /**
     * Create a Kinetica table with the given name with two nullable int columns.
     * Generate the given number of random records.  If a collection name is given,
     * ensure that Kinetica puts it in that collection.
     */
    def createKineticaTableTwoIntNullableColumns( tableName: String,
                                                  collectionName: Option[String],
                                                  numRows: Int ) : Unit = {
        
        // Create the table type
        var columns : mutable.ListBuffer[Type.Column] = new mutable.ListBuffer[Type.Column]();
        columns += new Type.Column( "x", classOf[java.lang.Integer], ColumnProperty.NULLABLE );
        columns += new Type.Column( "y", classOf[java.lang.Integer], ColumnProperty.NULLABLE );
    
        val type_ = new Type( columns.toList.asJava );

        // Set the collection name option for table creation
        collectionName match {
            // A collection name is given; set it
            case Some( collName ) => {
                m_createTableInCollectionOptions( CreateTableRequest.Options.COLLECTION_NAME ) = collName;
            }
            // No collection name is given, so ensure the relevant property
            // is absent from the options
            case None => {
                m_createTableInCollectionOptions remove CreateTableRequest.Options.COLLECTION_NAME;
            }
        }
        
        // Delete any pre-existing table with the same name
        clear_table( tableName );

        // Create the table
        m_gpudb.createTable( tableName, type_.create( m_gpudb ),
                             m_createTableInCollectionOptions );
        logger.debug( s"Created table $tableName via the Java API" );

        // Generate some random data
        m_gpudb.insertRecordsRandom( tableName, numRows, null );
        return;
    }

    /**
     * Create a Kinetica table with the given name with one nullable long,
     * timestamp column.  Optionally generate the given number of random
     * records.  If a collection name is given, ensure that Kinetica puts
     * it in that collection.
     */
    def createKineticaTableOneLongTimestampNullableColumn( tableName: String,
                                                           collectionName: Option[String],
                                                           numRows: Int ) : Unit = {
        
        // Create the table type
        var columns : mutable.ListBuffer[Type.Column] = new mutable.ListBuffer[Type.Column]();
        columns += new Type.Column( "timestamp", classOf[java.lang.Long],
                                    ColumnProperty.NULLABLE,
                                    ColumnProperty.TIMESTAMP );
    
        val type_ = new Type( columns.toList.asJava );

        // Set the collection name option for table creation
        collectionName match {
            // A collection name is given; set it
            case Some( collName ) => {
                m_createTableInCollectionOptions( CreateTableRequest.Options.COLLECTION_NAME ) = collName;
            }
            // No collection name is given, so ensure the relevant property
            // is absent from the options
            case None => {
                m_createTableInCollectionOptions remove CreateTableRequest.Options.COLLECTION_NAME;
            }
        }
        
        // Delete any pre-existing table with the same name
        clear_table( tableName );

        // Create the table
        m_gpudb.createTable( tableName, type_.create( m_gpudb ),
                             m_createTableInCollectionOptions );
        logger.debug( s"Created table $tableName via the Java API" );

        // Generate some random data, if desired by the caller
        if ( numRows > 0) {
            m_gpudb.insertRecordsRandom( tableName, numRows, null );
        }
        return;
    }

}   // end trait SparkConnectorTestFixture



/**
 * The base class for all Kinetica Spark connector tests.  Read comments above
 * trait `SparkConnectorTestFixture` to see how to write tests.
 *
 */
class SparkConnectorTestBase
    extends FunSuite
            with BeforeAndAfterEach
            with SparkConnectorTestFixture
            with LazyLogging {

    // Create the spark session before each test
    override def beforeEach() {
        m_sparkSession = SparkSession.builder().appName("Kinetica Spark Connector Tests")
            .master("local")
            .config("", "")
            .getOrCreate();
    }

    // Tear down the spark session after each test
    override def afterEach() {
        // Clear the tables
        m_tablesToClear.toList.foreach( tableName => clear_table( tableName ) );
        // Empty the list content
        m_tablesToClear.clear();
        
        // Stop the spark session
        m_sparkSession.stop();
    }


}  // end SparkConnectorTestBase

