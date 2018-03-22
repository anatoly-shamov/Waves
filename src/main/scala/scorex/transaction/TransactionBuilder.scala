package scorex.transaction

import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

trait TransactionBuilder {
  type TransactionT <: Transaction
  def classTag: ClassTag[TransactionT]

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

abstract class TransactionBuilderT[T <: Transaction](implicit override val classTag: ClassTag[T]) extends TransactionBuilder {
  override type TransactionT = T
}
