package peapod

import org.apache.spark.Logging
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.DataFrame
import org.apache.spark.storage.StorageLevel

import scala.reflect.ClassTag

abstract class EphemeralTask[V: ClassTag](implicit p: Peapod)
  extends Task[V] with Logging {

  protected def generate: V

  protected[peapod] def  build(): V = {
    logInfo("Loading" + dir)
    val generated = generate
    if(shouldPersist()) {
      logInfo("Loading" + dir + " Persisting")
      generated match {
        case g: RDD[_] => g.persist(StorageLevel.MEMORY_AND_DISK).asInstanceOf[V]
        case g: DataFrame => g.cache().asInstanceOf[V]
        case _ => generated
      }
    } else {
      generated
    }
  }
  def exists(): Boolean = false
}
