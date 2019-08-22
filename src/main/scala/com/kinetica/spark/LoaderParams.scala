package com.kinetica.spark;

import com.gpudb.Type;
import com.gpudb.GPUdb;
import com.gpudb.GPUdbBase;
import com.gpudb.GPUdbException;
import com.gpudb.GenericRecord;
import com.gpudb.protocol.ShowTableRequest;
import com.gpudb.protocol.ShowTableResponse;

import java.io.Serializable;
import java.util.TimeZone;
import scala.beans.{ BeanProperty, BooleanBeanProperty };
import com.typesafe.scalalogging.LazyLogging;
import com.kinetica.spark.util.ConfigurationConstants._;

import com.kinetica.spark.ssl.X509KeystoreOverride;
import com.kinetica.spark.ssl.X509TrustManagerOverride;
import com.kinetica.spark.ssl.X509TrustManagerBypass;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

import org.apache.spark.SparkContext;
import org.apache.spark.util.LongAccumulator;

import scala.collection.JavaConverters._;

    
class LoaderParams extends Serializable with LazyLogging {

    @BeanProperty
    var timeoutMs: Int = 10000

    @BeanProperty
    var kineticaURL: String = null

    @BeanProperty
    var streamURL: String = null

    @BeanProperty
    var tableType: Type = null

    @BeanProperty
    var tablename: String = null

    @BeanProperty
    var schemaname: String = ""

    @BooleanBeanProperty
    var tableReplicated: Boolean = false

    @BeanProperty
    var KdbIpRegex: String = null

    @BeanProperty
    var insertSize: Int = 10000

    @BooleanBeanProperty
    var updateOnExistingPk: Boolean = false

    @BeanProperty
    var kusername: String = null

    @BeanProperty
    var kpassword: String = null

    @BeanProperty
    var threads: Int = 4

    @BeanProperty
    var numPartitions: Int = 4
        
    @BeanProperty
    var jdbcURL: String = null

    @BooleanBeanProperty
    var createTable: Boolean = false

    @BooleanBeanProperty
    var mapToSchema: Boolean = true

    @BooleanBeanProperty
    var useSnappy: Boolean = true

    @BeanProperty
    var timeZoneStr: String = null;

    @BeanProperty
    var timeZone: java.util.TimeZone = null;

    @BeanProperty
    var retryCount: Int = 0

    @BooleanBeanProperty
    var alterTable: Boolean = false

    @BeanProperty
    var multiHead: Boolean = true

    @BeanProperty
    var truncateTable: Boolean = false

    @BeanProperty
    var loaderPath: Boolean = false

    @BeanProperty
    var bypassCert: Boolean = false

    @BeanProperty
    var trustStorePath: String = null

    @BeanProperty
    var trustStorePassword: String = null

    @BeanProperty
    var keyStorePath: String = null

    @BeanProperty
    var keyStorePassword: String = null

    @BeanProperty
    var totalRows: LongAccumulator = null

    @BeanProperty
    var convertedRows: LongAccumulator = null

    @BeanProperty
    var failedConversion: LongAccumulator = null

    @BeanProperty
    var truncateToSize: Boolean = false
    
    @BooleanBeanProperty
    var dryRun: Boolean = false
    
    @BooleanBeanProperty
    var flattenSourceSchema: Boolean = false

    @BooleanBeanProperty
    var failOnError: Boolean = false

        
    def this(sc: Option[SparkContext], params: Map[String, String]) = {
        this()

        // Get a few long accumulators only if the context is given
        sc match {
            case Some(sc) => {
                totalRows = sc.longAccumulator("TotalRows")
                convertedRows = sc.longAccumulator("ParsedRows")
                failedConversion = sc.longAccumulator("UnparsedRows")
            }
            case None => Unit
        }

        require(params != null, "Config cannot be null")
        require(params.nonEmpty, "Config cannot be empty")

        kineticaURL = params.get(KINETICA_URL_PARAM).getOrElse(null)
        streamURL = params.get(KINETICA_STREAMURL_PARAM).getOrElse(null)
        kusername = params.get(KINETICA_USERNAME_PARAM).getOrElse("")
        kpassword = params.get(KINETICA_PASSWORD_PARAM).getOrElse("")
        threads =   params.get(KINETICA_NUMTHREADS_PARAM).getOrElse("4").toInt

        insertSize = params.get(KINETICA_BATCHSIZE_PARAM).getOrElse("10000").toInt
        updateOnExistingPk = params.get(KINETICA_UPDATEONEXISTINGPK_PARAM).getOrElse("false").toBoolean
        tableReplicated = params.get(KINETICA_REPLICATEDTABLE_PARAM).getOrElse("false").toBoolean
        KdbIpRegex = params.get(KINETICA_IPREGEX_PARAM).getOrElse("")
        useSnappy = params.get(KINETICA_USESNAPPY_PARAM).getOrElse("false").toBoolean
        timeZoneStr = params.get(KINETICA_USETIMEZONE_PARAM).getOrElse( null )

        numPartitions = params.get(CONNECTOR_NUMPARTITIONS_PARAM).getOrElse("4").toInt

        // Default setting is 0
        retryCount = params.get(KINETICA_RETRYCOUNT_PARAM).getOrElse("0").toInt
        jdbcURL = params.get(KINETICA_JDBCURL_PARAM).getOrElse(null)
        createTable = params.get(KINETICA_CREATETABLE_PARAM).getOrElse("false").toBoolean

        alterTable = params.get(KINETICA_ALTERTABLE_PARAM).getOrElse("false").toBoolean
        mapToSchema = params.get(KINETICA_MAPTOSCHEMA_PARAM).getOrElse("false").toBoolean
        truncateToSize = params.get(KINETICA_TRUNCATE_TO_SIZE).getOrElse("false").toBoolean

        // 30 mins = 1800 seconds = 1800,000 ms
        timeoutMs = params.get(KINETICA_TIMEOUT_PARAM).getOrElse("1800000").toInt

        multiHead   = params.get(KINETICA_MULTIHEAD_PARAM).getOrElse("false").toBoolean
        failOnError = params.get(KINETICA_ERROR_HANDLING_PARAM).getOrElse("false").toBoolean

        truncateTable = params.get(KINETICA_TRUNCATETABLE_PARAM).getOrElse("false").toBoolean

        loaderPath = params.get(LOADERCODEPATH).getOrElse("false").toBoolean
        dryRun = params.get(KINETICA_DRYRUN).getOrElse("false").toBoolean

        flattenSourceSchema = params.get(KINETICA_FLATTEN_SCHEMA).getOrElse("false").toBoolean

        tablename = params.get(KINETICA_TABLENAME_PARAM).getOrElse(null)
        if(tablename == null) {
            throw new Exception( "Parameter is required: " + KINETICA_TABLENAME_PARAM)
        }

        // Instead of diverging the behavior for spark-submit vs spark shell etc.,
        // just check if a collection name is given, which is indicated by the
        // user (by default, we'll assume that we're to extract a schema name
        // from the table name)
        val tableContainsSchemaName = params.get( KINETICA_TABLENAME_CONTAINS_SCHEMA_PARAM )
                                            .getOrElse("true").toBoolean;
        if( tableContainsSchemaName && (tablename contains ".") ) {
            val tableParams: Array[String] = tablename.split("\\.")
            if (tableParams.length > 1) {
                // A collection name IS given
                schemaname = tableParams( 0 )
                // The remainder is the table name (which is allowed to have periods)
                tablename = tablename.substring( schemaname.length + 1 )
            }
        }

        // Parse the timezone, if any
        if (timeZoneStr != null ) {
            timeZone = TimeZone.getTimeZone( timeZoneStr );
        } else {
            timeZone = TimeZone.getDefault();
        }
        
        
        // SSL
        bypassCert = params.get(KINETICA_SSLBYPASSCERTCHECK_PARAM).getOrElse("false").toBoolean
        trustStorePath =  params.get(KINETICA_TRUSTSTOREJKS_PARAM).getOrElse(null)
        trustStorePassword = params.get(KINETICA_TRUSTSTOREPASSWORD_PARAM).getOrElse(null)
        keyStorePath = params.get(KINETICA_KEYSTOREP12_PARAM).getOrElse(null)
        keyStorePassword = params.get(KINETICA_KEYSTOREPASSWORD_PARAM).getOrElse(null)

        // Set the GPUdb and table type
        this.cachedGpudb = connect();
        
    }   // end constructor

    // below are not serializable so they are created on demand
    @transient
    private var cachedGpudb: GPUdb = null


    def setType(kineticaType: Type): Unit = {
        this.tableType = kineticaType
    }

    def getGpudb(): GPUdb = {
        if (this.cachedGpudb != null) {
            return this.cachedGpudb;
        }
        this.cachedGpudb = connect();
        return this.cachedGpudb;
    }

    def getType(): Type = {
        if (this.tableType != null) {
            return this.tableType;
        }
        
        // Need to get a type first
        try {
            this.tableType = Type.fromTable(this.cachedGpudb, getTablename);
            return this.tableType;
        } catch {
            case e: GPUdbException => {
                logger.error( s"Cannot create a type from the table name '$getTablename'; issue: '${e.getMessage()}'" );
                throw e;
            }
        }
    }

    private def connect(): GPUdb = {
        setupSSL()
        logger.info("Connecting to {} as <{}>", kineticaURL, kusername)
        val opts: GPUdbBase.Options = new GPUdbBase.Options()
        opts.setUsername(kusername)
        opts.setPassword(kpassword)
        opts.setThreadCount(threads)
        opts.setTimeout(timeoutMs)
        opts.setUseSnappy(useSnappy)
        val gpudb: GPUdb = new GPUdb(kineticaURL, opts)
        checkConnection(gpudb)
        gpudb
    }

    private def checkConnection(conn: GPUdb): Unit = {
        val CORE_VERSION: String = "version.gpudb_core_version"
        val VERSION_DATE: String = "version.gpudb_version_date"
        val options: java.util.Map[String, String] = new java.util.HashMap[String, String]()
        options.put(CORE_VERSION, "")
        options.put(VERSION_DATE, "")
        try {
            val rsMap: java.util.Map[String, String] = conn.showSystemProperties(options).getPropertyMap
            logger.debug("Connected to {} ({})", rsMap.get(CORE_VERSION), rsMap.get(VERSION_DATE))
        } catch {
            case e: GPUdbException => {
                logger.debug( s"Cannot verify connection health: '${e.getMessage()}'" );
                throw e;
            }
        }
            
    }

    /**
     * Checks if the table exists.
     */
    def hasTable(): Boolean = {
        val gpudb: GPUdb = this.getGpudb
        if (!gpudb.hasTable(this.tablename, null).getTableExists) {
            false
        } else {
            logger.info("Found existing table: {}", this.tablename)
            true
        }
    }

    /**
     * Checks if the table belongs to the collection, if
     * given any.  Returns true if no collection is given, or if
     * one is given AND the table belongs to that collection.
     * false otherwise.  If the table does not exist, return false.
     */
    def doesTableCollectionMatch(): Boolean = {
        val gpudb: GPUdb = this.getGpudb
        // Now check if it's part of the collection, if given any
        if ( !this.schemaname.isEmpty() ) {
            var stOptions = Map( ShowTableRequest.Options.NO_ERROR_IF_NOT_EXISTS -> ShowTableRequest.Options.TRUE );
            
            val rsp: ShowTableResponse = gpudb.showTable( this.tablename, stOptions.asJava );
            if ( rsp.getAdditionalInfo().isEmpty() )
                return false; // the table does not exist, so collection can't match!

            val rspAdditionalInfo: Map[String, String] = rsp.getAdditionalInfo().get( 0 ).asScala.toMap
            if ( rspAdditionalInfo.contains( ShowTableResponse.AdditionalInfo.COLLECTION_NAMES ) ) {
                // Now, is it the same collection?
                val collName: String = rspAdditionalInfo( ShowTableResponse.AdditionalInfo.COLLECTION_NAMES );
                if ( collName == this.schemaname) {
                    // Collection name matches
                    return true;
                }
                else {
                    // Collection name does not match
                    return false;
                }
            }
            // The table does not belong to a collection (but is supposed
            // to), so not quite a match
            return false;
        }
        else
            return true; // no collection name given
    }

    private def setupSSL(): Unit = {
        if (this.bypassCert) {
            logger.info("Installing truststore to bypass certificate check.")
            X509TrustManagerBypass.install()
            return
        }

        var trustManagerList: Array[TrustManager] = null
        if (this.trustStorePath != null) {
            logger.info("Installing custom trust manager: {}", classOf[X509TrustManagerOverride].getName)
            trustManagerList = X509TrustManagerOverride.newManagers(
                this.trustStorePath,
                this.trustStorePassword)
        }

        var keyManagerList: Array[KeyManager] = null
        if (this.keyStorePath != null) {
            logger.info("Installing custom key manager: {}", classOf[X509KeystoreOverride].getName)
            keyManagerList = X509KeystoreOverride.newManagers(
                this.keyStorePath,
                this.keyStorePassword)
        }

        if (trustManagerList == null && keyManagerList == null) {
            // nothing to install
            return
        }

        val sslContext: SSLContext = SSLContext.getInstance("SSL")
        sslContext.init(keyManagerList, trustManagerList, new java.security.SecureRandom())
        //SSLContext.setDefault(sslContext);
        HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory)
    }
}
