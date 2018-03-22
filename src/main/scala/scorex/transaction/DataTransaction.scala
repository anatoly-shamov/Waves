package scorex.transaction

import com.google.common.primitives.{Bytes, Longs, Shorts}
import com.wavesplatform.crypto
import com.wavesplatform.state2._
import monix.eval.Coeval
import play.api.libs.json._
import scorex.account.{PrivateKeyAccount, PublicKeyAccount}

import scorex.transaction.TransactionParser.KeyLength

import scala.util.{Failure, Success, Try}

case class DataTransaction private(sender: PublicKeyAccount,
                                   data: List[DataEntry[_]],
                                   fee: Long,
                                   timestamp: Long,
                                   proofs: Proofs) extends ProvenTransaction with FastHashId {

  override val builder: TransactionBuilder = DataTransaction
  override val assetFee: (Option[AssetId], Long) = (None, fee)

  override val bodyBytes: Coeval[Array[Byte]] = Coeval.evalOnce {
    Bytes.concat(
      Array(builder.typeId),
      Array(version),
      sender.publicKey,
      Shorts.toByteArray(data.size.toShort),
      data.flatMap(_.toBytes).toArray,
      Longs.toByteArray(timestamp),
      Longs.toByteArray(fee))
  }

  implicit val dataItemFormat: Format[DataEntry[_]] = DataEntry.Format

  override val json: Coeval[JsObject] = Coeval.evalOnce {
    jsonBase() ++ Json.obj(
      "data" -> Json.toJson(data),
      "version" -> version)
  }

  override val bytes: Coeval[Array[Byte]] = Coeval.evalOnce(Bytes.concat(bodyBytes(), proofs.bytes()))
}

object DataTransaction extends TransactionBuilderT[DataTransaction] {

  override val typeId: Byte = 12
  override val version: Byte = 1

  val MaxEntryCount: Byte = Byte.MaxValue

  def parseTail(bytes: Array[Byte]): Try[DataTransaction] = Try {
    val parsedVersion = bytes(0)
    if (parsedVersion != version) throw new IllegalArgumentException(s"Invalid version '$parsedVersion', expected '$version'")

    val p0 = KeyLength + 1
    val sender = PublicKeyAccount(bytes.slice(1, p0))

    val entryCount = Shorts.fromByteArray(bytes.drop(p0))
    val (entries, p1) =
      if (entryCount > 0) {
        val parsed = List.iterate(DataEntry.parse(bytes, p0 + 2), entryCount) { case (e, p) => DataEntry.parse(bytes, p) }
        (parsed.map(_._1), parsed.last._2)
      } else (List.empty, p0 + 2)

    val timestamp = Longs.fromByteArray(bytes.drop(p1))
    val feeAmount = Longs.fromByteArray(bytes.drop(p1 + 8))
    val txEi = for {
      proofs <- Proofs.fromBytes(bytes.drop(p1 + 16))
      tx <- create(sender, entries, feeAmount, timestamp, proofs)
    } yield tx
    txEi.fold(left => Failure(new Exception(left.toString)), right => Success(right))
  }.flatten

  def create(sender: PublicKeyAccount,
             data: List[DataEntry[_]],
             feeAmount: Long,
             timestamp: Long,
             proofs: Proofs): Either[ValidationError, DataTransaction] = {
    if (data.lengthCompare(MaxEntryCount) > 0 || data.exists(! _.valid)) {
      Left(ValidationError.TooBigArray)
    } else if (feeAmount <= 0) {
      Left(ValidationError.InsufficientFee)
    } else {
      Right(DataTransaction(sender, data, feeAmount, timestamp, proofs))
    }
  }

  def selfSigned(sender: PrivateKeyAccount,
                 data: List[DataEntry[_]],
                 feeAmount: Long,
                 timestamp: Long): Either[ValidationError, DataTransaction] = {
    create(sender, data, feeAmount, timestamp, Proofs.empty).right.map { unsigned =>
      unsigned.copy(proofs = Proofs.create(Seq(ByteStr(crypto.sign(sender, unsigned.bodyBytes())))).explicitGet())
    }
  }
}
