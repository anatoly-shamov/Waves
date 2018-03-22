package scorex.transaction

import com.wavesplatform.state2._
import monix.eval.Coeval
import scorex.serialization.{BytesSerializable, JsonSerializable}

trait Transaction extends BytesSerializable with JsonSerializable {
  val version: Byte = 1
  val id: Coeval[ByteStr]

  val builder: TransactionBuilder
  val assetFee: (Option[AssetId], Long)
  val timestamp: Long

  override def toString: String = json().toString()

  override def equals(other: Any): Boolean = other match {
    case tx: Transaction => id() == tx.id()
    case _ => false
  }

  override def hashCode(): Int = id().hashCode()
}

object Transaction {

  type Type = Byte

  implicit class TransactionExt(tx: Transaction) {
    def feeDiff(): Portfolio = tx.assetFee match {
      case (Some(asset), fee) =>
        Portfolio(
          balance = 0,
          lease = LeaseBalance.empty,
          assets = Map(asset -> fee))
      case (None, fee) => Portfolio(
        balance = fee,
        lease = LeaseBalance.empty,
        assets = Map.empty)
    }
  }

}
