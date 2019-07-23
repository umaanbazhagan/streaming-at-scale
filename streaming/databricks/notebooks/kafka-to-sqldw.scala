// Databricks notebook source
dbutils.widgets.text("kafka-servers", "", "Kafka consumer group")
dbutils.widgets.text("kafka-topics", "", "Kafka consumer group")
dbutils.widgets.text("sqldw-servername", "")
dbutils.widgets.text("sqldw-user", "serveradmin")
dbutils.widgets.text("sqldw-tempstorage-account", "")
dbutils.widgets.text("sqldw-tempstorage-container", "")
dbutils.widgets.text("sqldw-table", "rawdata_cs")

// COMMAND ----------

val data = spark.readStream
  .format("kafka")
  .option("kafka.bootstrap.servers", dbutils.widgets.get("kafka-servers"))
  .option("subscribe", dbutils.widgets.get("kafka-topics"))
  .option("startingOffsets", "earliest")
  .load()

// COMMAND ----------

import org.apache.spark.sql.types._
import org.apache.spark.sql.functions._

val schema = StructType(
  StructField("eventId", StringType) ::
  StructField("complexData", StructType((1 to 22).map(i => StructField(s"moreData$i", DoubleType)))) ::
  StructField("value", StringType) ::
  StructField("type", StringType) ::
  StructField("deviceId", StringType) ::
  StructField("createdAt", TimestampType) :: Nil)

val dataToWrite = data
  .select(from_json(decode($"body", "UTF-8"), schema).as("eventData"), $"*")
  .select($"eventData.*", $"enqueuedTime".as("enqueuedAt"))
  .select('eventId.as("EventId"), 'Type, 'DeviceId, 'CreatedAt, 'Value, 'ComplexData, 'EnqueuedAt)

// COMMAND ----------

// Helper method to retry an operation up to n times with exponential backoff
@annotation.tailrec
final def retry[T](n: Int, backoff: Int)(fn: => T): T = {
  Thread.sleep(((scala.math.pow(2, backoff) - 1) * 1000).toLong)
  util.Try { fn } match {
    case util.Success(x) => x
    case _ if n > 1 => retry(n - 1, backoff + 1)(fn)
    case util.Failure(e) => throw e
  }
}

// COMMAND ----------

val tempStorageAccount = dbutils.widgets.get("sqldw-tempstorage-account")
val tempStorageContainer = dbutils.widgets.get("sqldw-tempstorage-container")
val serverName = dbutils.widgets.get("sqldw-servername")
val jdbcUrl = s"jdbc:sqlserver://$serverName.database.windows.net;database=streaming"
spark.conf.set(
  s"fs.azure.account.key.$tempStorageAccount.blob.core.windows.net",
  dbutils.secrets.get(scope = "MAIN", key = "sqldw-tempstorage-key"))

df.writeStream
  .format("com.databricks.spark.sqldw")
  .option("url", jdbcUrl)
  .option("user", dbutils.widgets.get("sqldw-user"))
  .option("password", dbutils.secrets.get(scope = "MAIN", key = "sqldw-pass"))
  .option("tempDir", s"wasbs://$tempStorageContainer@$tempStorageAccount.blob.core.windows.net/")
  .option("forwardSparkAzureStorageCredentials", "true")
  .option("dbTable", dbutils.widgets.get("sqldw-table"))
  .option("checkpointLocation", "dbfs:/streaming_at_scale/checkpoints/streaming-sqldw")
  .start()
