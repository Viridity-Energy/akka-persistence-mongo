/**
 *  Copyright (C) 2013-2014 Duncan DeVore. <http://reactant.org>
 */
package akka.persistence.journal.mongo

import akka.actor.ActorLogging
import akka.persistence._
import akka.persistence.journal.SyncWriteJournal
import akka.serialization.SerializationExtension

import com.mongodb.casbah.Imports._

import scala.collection.immutable

class CasbahJournal extends SyncWriteJournal
    with CasbahRecovery
    with CasbahHelper
    with ActorLogging {

  override def configJournal = context.system.settings.config.getConfig("casbah-journal")
  implicit val concern = casbahWriteConcern

  val serialization = SerializationExtension(context.system)

  private[mongo] def msgToBytes(p: PersistentRepr): Array[Byte] = serialization.serialize(p).get
  private[mongo] def msgFromBytes(a: Array[Byte]) = serialization.deserialize(a, classOf[PersistentRepr]).get

  def writeMessages(persistentBatch: immutable.Seq[PersistentRepr]): Unit = {
    val batch = persistentBatch.map(pr => writeJSON(pr.processorId, pr.sequenceNr, msgToBytes(pr)))
    collection.insert(batch:_ *)
  }

  def writeConfirmations(confirmations: immutable.Seq[akka.persistence.PersistentConfirmation]): Unit = {
    val batch = confirmations map { c => confirmJSON(c.processorId, c.sequenceNr, c.channelId) }
    collection.insert(batch:_ *)
  }

  def deleteMessages(messageIds: immutable.Seq[akka.persistence.PersistentId], permanent: Boolean): Unit = {
    if (permanent) {
      val batch = messageIds map { mid => delStatement(mid.processorId, mid.sequenceNr) }
      collection.remove(delOrStatement(batch.toList), concern)
    } else {
      val batch = messageIds map { mid => deleteMarkJSON(mid.processorId, mid.sequenceNr) }
      collection.insert(batch:_ *)
    }
  }

  def deleteMessagesTo(processorId: String, toSequenceNr: Long, permanent: Boolean): Unit = {
    if (permanent)
      collection.remove(delToStatement(processorId, toSequenceNr), concern)
    else {
      val msgs = collection.find(delToStatement(processorId, toSequenceNr)).sort(minSnrSortStatement).toList
      val deletedMsgs = msgs.filter(_.get(MarkerKey) == MarkerDelete)
      val msgsToDelete = msgs.filterNot(msg =>
        deletedMsgs.exists(_.get(SequenceNrKey) == msg.get(SequenceNrKey)))
      val batch = msgsToDelete map { msg => deleteMarkJSON(processorId, msg.get(SequenceNrKey).asInstanceOf[Long]) }
      collection.insert(batch:_ *)
    }
  }

  override def postStop(): Unit = {
    client.close()
  }
}
