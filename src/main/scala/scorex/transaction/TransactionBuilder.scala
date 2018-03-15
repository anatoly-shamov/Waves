package scorex.transaction

import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

abstract class TransactionBuilder {
  type TransactionT <: Transaction
  implicit val classTag: ClassTag[TransactionT] = implicitly[ClassTag[TransactionT]]

  def typeId: Byte
  def version: Byte

  def parseTail(bytes: Array[Byte]): Try[TransactionT]
  def parseBytes(bytes: Array[Byte]): Try[TransactionT] = bytes
    .headOption
    .fold[Try[Byte]](Failure(new IllegalArgumentException("Can't check the type of transaction: the buffer is empty")))(Success(_))
    .flatMap { headByte =>
      if (headByte == typeId) parseTail(bytes.tail)
      else Failure(new IllegalArgumentException(s"An unexpected head byte '$headByte', expected '$typeId'"))
    }
}
