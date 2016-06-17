package peapod

import java.util.concurrent.Executors

import org.apache.spark.Logging
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, Dataset}
import org.apache.spark.storage.StorageLevel

import scala.collection.immutable.HashSet
import scala.collection.parallel.ExecutionContextTaskSupport
import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag

/**
  *
  */
class Pea[+D: ClassTag](task: Task[D]) extends Logging {
  override val toString = task.name
  lazy val versionName = task.versionName
  override val hashCode = task.name.hashCode
  lazy val version = task.version

  lazy val ephemeral = task.isInstanceOf[EphemeralTask[_]]
  def exists = task.exists()

  var children: Set[Pea[_]] = new HashSet[Pea[_]]()
  var parents: Set[Pea[_]] = new HashSet[Pea[_]]()
  var cache: Option[_] = None

  private[peapod] def addParent(pea: Pea[_]) = parents.synchronized {
    parents = parents + pea
  }

  private[peapod] def removeParent(pea: Pea[_]) = parents.synchronized {
    parents = parents - pea
    if(parents.isEmpty) {
      cache match {
        case Some(c) => //unpersist(c.asInstanceOf[D])
        case None =>
      }
    }
  }

  private[peapod] def addChild(pea: Pea[_]) = children.synchronized {
    children = children + pea
  }

  private[peapod] def removeChild(pea: Pea[_]) = children.synchronized {
    children = children - pea
  }

  def apply(): D = get()

  def build(): D = {
    if(! task.exists()) {
      logInfo("Loading" + this + " Deleting")
      task.delete()
      logInfo("Loading" + this + " Generating")
      task.build()
    } else {
      logInfo("Loading" + this + " Reading")
      task.load()
    }
  }

  //TODO: Remove auto-persisting of ephemeral tasks, instead keep track recursivelly of child tasks that need to be un-persisted
  protected def buildCache(): Unit = {
    cache = cache match {
      case None =>
        if (!exists) {
          val par = children.par
          par.tasksupport = Pea.tasksupport
          par.foreach(c => c.get())
        }
        val d = {
          val built = build()
          if ((parents.size > 1 || task.isInstanceOf[AlwaysCache] || ! task.storable  )
            && ! task.isInstanceOf[NeverCache]) {
            persist(built)
          } else {
            built
          }
        }
        Some(d)
      case Some(c) =>
        if ((parents.size > 1 || task.isInstanceOf[AlwaysCache] || ! task.storable  )
          && ! task.isInstanceOf[NeverCache]) {
          Some(persist(c))
        } else {
          cache
        }
    }
    children.foreach(c => c.removeParent(this))
    children.foreach(c => c.unpersist())
    children = children.empty
  }

  def get(): D = this.synchronized {
    buildCache()
    val d = cache match {
      case None =>
        throw new Exception("Cache is empty after being set")
      case Some(c) =>
        c.asInstanceOf[D]
    }
    d
  }

  private def persist[V: ClassTag](d: V): V = {
    (
      d match {
        case d: RDD[_] => d.persist(StorageLevel.MEMORY_AND_DISK)
        case d: DataFrame => d.cache()
        case d: Dataset[_] => d.cache()
        case d: V => d
      }
      ).asInstanceOf[V]
  }

  private def unpersist[V: ClassTag](d: V): V = {
    (
      d match {
        case d: RDD[_] => d.unpersist()
        case d: DataFrame => d.unpersist()
        case d: Dataset[_] => d.unpersist()
        case d: V => d
      }
      ).asInstanceOf[V]
  }

  private[Pea] def unpersist(): Unit = this.synchronized {
    cache = cache match {
      case None => None
      case Some(c) =>
        if (parents.isEmpty && ! task.isInstanceOf[AlwaysCache]) {
          Some(unpersist(c))
        } else {
          Some(c)
        }
    }
  }

  /*
  Define equality and other attributes to be based on the underlying Task classes
  and be unique per Task class
   */
  override def equals(o: Any) = {
    o match {
      case pea: Pea[_] => pea.toString == this.toString
      case _ => false
    }
  }
}

object Pea {
  implicit def getAnyTask[T: ClassTag](task: WrappedTask[T]): T =
    task.get()

  implicit def getAnyTask[T: ClassTag](pea: Pea[T]): T =
    pea.get()

  private val tasksupport =
    new ExecutionContextTaskSupport(
      ExecutionContext.fromExecutor(Executors.newCachedThreadPool())
    )
}
