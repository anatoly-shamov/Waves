package scorex.transaction

import com.wavesplatform.utils.base58Length
import scorex.transaction.assets._
import scorex.transaction.assets.exchange.ExchangeTransaction
import scorex.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import scorex.transaction.smart.SetScriptTransaction

import scala.util.{Failure, Success, Try}

object TransactionParser {

  val TimestampLength = 8
  val AmountLength = 8
  val TypeLength = 1
  val SignatureLength = 64
  val SignatureStringLength: Int = base58Length(SignatureLength)
  val KeyLength = 32
  val KeyStringLength: Int = base58Length(KeyLength)

  private val old: Map[Byte, TransactionBuilder] = Seq[TransactionBuilder](
    GenesisTransaction, PaymentTransaction, IssueTransaction, TransferTransaction, ReissueTransaction,
    BurnTransaction, ExchangeTransaction, LeaseTransaction, LeaseCancelTransaction, CreateAliasTransaction,
    MassTransferTransaction
  ).map { x =>
    x.typeId -> x
  }(collection.breakOut)

  private val modern: Map[(Byte, Byte), TransactionBuilder] = Seq[TransactionBuilder](
    DataTransaction, VersionedTransferTransaction, SetScriptTransaction, SmartIssueTransaction
  ).map { x =>
    ((x.typeId, x.version), x)
  }(collection.breakOut)

  private val all: Map[(Byte, Byte), TransactionBuilder] = old.map {
    case (typeId, builder) => ((typeId, builder.version), builder)
  } ++ modern

  private val byName: Map[String, TransactionBuilder] = (old ++ modern).map {
    case (_, builder) => builder.classTag.runtimeClass.getSimpleName -> builder
  }

  def builderByName(x: String): Option[TransactionBuilder] = byName.get(x)

  def builderFor(typeId: Byte, version: Byte): Option[TransactionBuilder] = all.get((typeId, version))

  def parseBytes(data: Array[Byte]): Try[Transaction] = data
    .headOption
    .fold[Try[Byte]](Failure(new IllegalArgumentException("Can't find the significant byte: the buffer is empty")))(Success(_))
    .flatMap { headByte =>
      if (headByte == 0) modernParseBytes(data.tail)
      else oldParseBytes(headByte, data)
    }

  private def oldParseBytes(tpe: Byte, data: Array[Byte]): Try[Transaction] = old
    .get(tpe)
    .fold[Try[TransactionBuilder]](Failure(new IllegalArgumentException(s"Unknown transaction type (old encoding): '$tpe'")))(Success(_))
    .flatMap(_.parseBytes(data))

  private def modernParseBytes(data: Array[Byte]): Try[Transaction] = {
    if (data.length < 2) Failure(new IllegalArgumentException(s"Can't determine the type and the version of transaction: the buffer has ${data.length} bytes"))
    else {
      val Array(typeId, version) = data.take(2)
      modern
        .get((typeId, version))
        .fold[Try[TransactionBuilder]](Failure(new IllegalArgumentException(s"Unknown transaction type ($typeId) and version ($version) (modern encoding)")))(Success(_))
        .flatMap(_.parseBytes(data))
    }
  }

}
